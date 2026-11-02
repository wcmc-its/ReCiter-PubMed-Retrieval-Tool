# Design: Shared cross-thread rate limiter and Retry-After on EFetch (Issue #117)

**Status:** Design only. This document does **not** implement the change; issue #117 remains open.
**Repo:** `wcmc-its/ReCiter-PubMed-Retrieval-Tool`
**Runtime:** Java 11, Spring Boot 2.7.18 (`pom.xml:13,19`)
**Scope of code reviewed:** `origin/dev` tip (worktree `docs/deploy-runbook-and-rate-limiter`, HEAD `7b1fada`).

> **Important currency note.** Issue #117 was filed against `master` and cites line numbers
> (`service:215-225`, `PubMedUriParserCallable uses raw URL.openStream()`) and a premise of
> "high-volume EFetch **batches**." The code on `origin/dev` has since been refactored: there is
> **no longer** an EFetch pagination loop or a work-stealing thread pool, and retrieval issues a
> **single** EFetch per query. The *underlying gap* the issue describes (no shared rate limiter;
> EFetch ignores rate-limit headers; per-request-only Retry-After; blocking sleep on a servlet
> thread) is still real, just for a different concurrency reason (multiple pods + multiple
> concurrent servlet requests, not intra-request batch fan-out). This document establishes
> current behavior from the actual `origin/dev` code and re-frames the gap accordingly.

---

## 1. Current behavior (established from code)

### 1.1 Retrieval flow — single EFetch, no batch fan-out, no thread pool

`PubMedArticleRetrievalService.retrieve(String)` runs synchronously on the calling servlet thread:

1. ESearch determines the matching count (`service:111-112`).
2. If `count > RETRIEVAL_THRESHOLD` (2000, `service:54`) it hard-refuses with an `IOException`
   (`service:114-116`) — mapped to HTTP 502 by `GlobalExceptionHandler` via the marker string
   `"exceeded the threshold level"` (`GlobalExceptionHandler.java:29,34`).
3. If `count == 0` it returns an empty list with no EFetch round-trip (`service:120-122`).
4. Otherwise it builds **one** EFetch URL and runs **one** `PubMedUriParserCallable.call()`
   synchronously, in-line, returning its result (`service:126-141`).

The class javadoc states this explicitly: because the allowed count (2000) is well below
`DEFAULT_RETMAX` (10000, `PubmedXmlQuery.java:11`), "every allowed query is satisfied by a single
EFetch request — there is no need to paginate over retstart or fan the fetches out across a thread
pool" (`service:99-105`).

A repo-wide grep confirms this: there is **no** `ExecutorService`, `Executors.*`,
`newWorkStealingPool`, `invokeAll`, or `submit(` anywhere under `src/main/java/`. The
`PubMedUriParserCallable` still `implements Callable<...>` (`PubMedUriParserCallable.java:20`) but
is now invoked directly via `.call()` on the servlet thread, not submitted to a pool.

**Consequence for #117:** intra-request EFetch concurrency no longer exists. Concurrency that can
collectively exceed the NCBI quota now comes from two other sources (see 1.5).

### 1.2 Retry configuration (Spring `@Retryable`)

`retrieve(...)` is annotated (`service:107-108`):

```java
@Retryable(maxAttempts = 7, value = IOException.class,
    backoff = @Backoff(random = true, delay = 1500, maxDelay = 9000), listeners = {"retryListener"})
```

- **Attempts:** `maxAttempts = 7` (1 initial + 6 retries).
- **Backoff:** randomized between `delay = 1500` ms and `maxDelay = 9000` ms per attempt
  (`random = true`). This is the only inter-attempt wait; there is no exponential `multiplier`.
- **Triggers on:** `IOException` only.
- **Listener:** `RetryListener` (`RetryListener.java`) logs the retry count on each `onError`
  (`RetryListener.java:24-27`); it does not alter backoff or honor any rate-limit signal.
- **Recovery:** `@Recover recoverRetrieve(...)` re-throws the final `IOException` after attempts
  are exhausted (`service:158-162`).
- `@EnableRetry` is on `Application` (`Application.java:11`).

This retry wraps the **whole** `retrieve` method (ESearch + EFetch). It reacts to thrown
`IOException`s; it does **not** read NCBI rate-limit headers to decide its wait.

### 1.3 Rate-limit / Retry-After handling — ESearch only, honored once, blocking

Rate-limit handling exists **only on the ESearch path** and is invoked from `executeReadingBody`
(`service:239-248`), which wraps the ESearch HTTP POST:

```java
try (CloseableHttpResponse response = pubMedHttpClient.execute(httppost)) {
    if (shouldRetryAfterRateLimit(response, query)) {           // service:241
        try (CloseableHttpResponse retryResponse = pubMedHttpClient.execute(httppost)) {
            return readBody(retryResponse);
        }
    }
    return readBody(response);
}
```

`shouldRetryAfterRateLimit(...)` (`service:268-292`):

- Reads `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `Retry-After` headers (`service:269-271`),
  fully null/length guarded because NCBI omits them on error pages.
- Logs limit/remaining when both present (`service:273-276`).
- If `X-RateLimit-Remaining == 0` **and** a `Retry-After` header is present, it
  `Thread.sleep(Retry-After * 1000)` then returns `true` so the caller replays the request **once**
  (`service:278-291`).

Limitations of the current handling, all confirmed in code:

- **ESearch-only.** EFetch does not call this path at all (see 1.4).
- **Honored once.** A single replay; if the replay is also throttled the method returns the
  throttled body (no second wait inside this method — only the outer `@Retryable` may re-enter).
- **Blocking.** `Thread.sleep` runs on the servlet worker thread (`service:283`), tying up a Tomcat
  thread for the full advertised interval.
- **Per-request only.** The decision is local to one request/thread; nothing is shared across
  threads or pods.

### 1.4 EFetch fetch — raw connection, no header inspection

EFetch is fetched inside `PubMedUriParserCallable.preprocessSpecialCharacters(...)`
(`PubMedUriParserCallable.java:46-71`):

```java
URL url = new URL(inputSource.getSystemId());
if (!EXPECTED_HOST.equalsIgnoreCase(url.getHost())) { ... }       // SSRF guard, line 50
URLConnection connection = url.openConnection();                  // line 53
connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);             // 5_000, line 23/54
connection.setReadTimeout(READ_TIMEOUT_MILLIS);                   // 60_000, line 26/55
try (InputStream inputStream = connection.getInputStream()) {     // line 56
    xml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
}
```

- It uses a **raw `java.net.URLConnection`**, not the pooled `pubMedHttpClient`, and **never
  inspects** `X-RateLimit-*` or `Retry-After` on the EFetch response.
- It has an SSRF host guard (`EXPECTED_HOST = "www.ncbi.nlm.nih.gov"`,
  `PubMedUriParserCallable.java:29,50`) and connect/read timeouts, but no throttling.
- A 429/throttled EFetch surfaces only as an `IOException` from `getInputStream()` (e.g. HTTP 429),
  which bubbles up to the outer `@Retryable` and is retried with the **random 1.5–9 s** backoff —
  it does **not** read or honor the server's `Retry-After`.

> The issue's phrase "PubMedUriParserCallable uses raw `URL.openStream()`" is directionally correct:
> current code uses `url.openConnection().getInputStream()` (equivalent raw JDK fetch). The key
> claim — EFetch ignores rate-limit headers — holds.

### 1.5 Actual sources of concurrency against the shared NCBI key

With batch fan-out gone, concurrent NCBI calls now come from:

1. **Multiple concurrent servlet requests within one pod.** Each request runs ESearch + (up to one)
   EFetch on its own Tomcat worker thread; the HTTP client pool allows up to
   `MAX_TOTAL_CONNECTIONS = 50` / `MAX_CONNECTIONS_PER_ROUTE = 50` simultaneous connections to NCBI
   (`HttpClientConfig.java:32-33`). Nothing throttles how many of those 50 fire per second.
2. **Multiple pods.** `kubernetes/k8-deployment.yaml` defines an HPA with
   `minReplicas: 1`, `maxReplicas: 4` (`k8-deployment.yaml:135-136`). Up to 4 pods can run
   concurrently, each with its own 50-connection pool and its own local rate-limit view.
3. **Other services sharing the same NCBI API key** (see 2.2).

None of these coordinate. Each request, each pod, observes the per-key quota independently.

---

## 2. NCBI E-utilities limits

> The following are NCBI's documented E-utilities policy limits, stated here as the constraint the
> design must respect. They are not asserted from this repo's code; see the keyClaims list for the
> verifier.

- **Without an API key: 3 requests/second** per source.
- **With an API key (`PUBMED_API_KEY`): 10 requests/second.** The key is read in
  `PubmedXmlQuery` via `System.getenv("PUBMED_API_KEY")` (`PubmedXmlQuery.java:68`) and appended to
  both ESearch and EFetch URLs (`PubmedXmlQuery.java:100-104,131-135`).
- **The quota is enforced PER API KEY (or per source IP when no key is used), and is shared across
  ALL usage of that key.** NCBI rate-limits the credential, not the process. Exceeding it returns
  **HTTP 429** with a `Retry-After` header and `X-RateLimit-Remaining: 0`.

### 2.2 The key is shared across multiple ReCiter services

Per the workspace architecture, the same `PUBMED_API_KEY` is (or can be) used by more than one
service that talks to NCBI — at minimum this PubMed Retrieval Tool, and potentially the main
ReCiter app and the Scopus Retrieval Tool's NCBI usage. **A per-service limiter therefore cannot,
by itself, guarantee the org stays under 10 req/s**, because it has no visibility into the other
services' consumption of the same credential. (This cross-service sharing should be confirmed
against actual deployment env config — see keyClaims.)

---

## 3. The gap (#117)

| # | Gap | Evidence |
|---|-----|----------|
| G1 | **No shared rate limiter.** Each request/thread/pod observes the quota independently; nothing caps the aggregate request rate. | No limiter in code; `HttpClientConfig` allows 50 concurrent conns; HPA allows 4 pods. |
| G2 | **EFetch ignores rate-limit headers entirely.** EFetch fetches via raw `URLConnection` and never reads `X-RateLimit-*` / `Retry-After`. | `PubMedUriParserCallable.java:53-58`. |
| G3 | **Retry-After honored per-request only, and only on ESearch, only once.** | `service:241-247`, `service:268-292`. |
| G4 | **Backoff blocks a servlet thread.** `Thread.sleep` on the Tomcat worker. | `service:283`. |
| G5 | **`@Retryable` backoff is rate-limit-blind.** On a 429 it waits a random 1.5–9 s instead of the server-advertised `Retry-After`. | `service:107-108`. |
| G6 | **Per-service scope may be insufficient.** The NCBI key is one budget shared across multiple services. | Section 2.2 (verify against env). |

---

## 4. Options

All three options share a common, non-negotiable building block: **both ESearch and EFetch must
acquire a permit before issuing their HTTP call, and both must honor a 429 `Retry-After` by
returning that permit / suspending the limiter rather than immediately replaying.** They differ in
*where the budget lives* and *who shares it*.

### Option 1 — Per-pod in-process token bucket

**Mechanism.** Add a single Spring-managed bean wrapping a token bucket — e.g. Guava
`RateLimiter.create(permitsPerSecond)` or Resilience4j `RateLimiter` — sized to a **fraction** of
the NCBI quota so that `pods × per-pod-rate ≤ key-quota`. With `maxReplicas: 4` and a 10 req/s key,
each pod gets ~2 req/s (leaving headroom for other services). ESearch (`executeReadingBody`) and
EFetch (`PubMedUriParserCallable`, which would need the bean injected instead of being a bare
POJO) both `acquire()` before their call. On a 429, read `Retry-After` and `sleep`/`drain` the
bucket for that interval before allowing the next acquire.

**Honoring Retry-After.** Centralize the existing `shouldRetryAfterRateLimit` logic into the
limiter: on `Remaining == 0` / 429, the limiter blocks all permit grants for `Retry-After` seconds.
This makes EFetch honor Retry-After (closes G2/G3) and applies one coordinated wait per pod
instead of each thread sleeping independently (mitigates, but does not eliminate, G4 — see cons).

**Pros.**
- No new infrastructure; pure in-process library. Lowest operational cost.
- Closes G1 *within a pod*, G2, G3, G5 fully. Small, well-contained change.
- Guava is already an indirect candidate; Resilience4j integrates cleanly with Spring Boot 2.7.

**Cons / failure modes.**
- **Does not coordinate across pods.** Correctness depends on the static fraction
  (`per-pod-rate = key-quota / maxReplicas / safety-factor`). If the HPA scales beyond expectation,
  or other services consume the key, the aggregate can still exceed quota (G1 partial, G6 open).
- Static sizing wastes quota when fewer than `maxReplicas` pods are running.
- If implemented with a blocking `acquire()`, still parks a servlet thread (G4 persists unless
  paired with async handling; out of scope here).

**Effort:** Small (~1–2 days). One bean, two call-site changes, sizing config, unit tests with a
clock-injected limiter.

### Option 2 — Shared cross-pod distributed rate limiter

**Mechanism.** Put the token bucket in a store all pods share — ElastiCache **Redis** (atomic
token-bucket via a Lua script / `bucket4j-redis`), or a **DynamoDB**-backed counter (atomic
conditional updates on a per-second / sliding-window key). Every ESearch and EFetch acquires a
permit from the shared bucket before calling NCBI, so all pods draw down **one** budget sized to the
key quota (with headroom).

**Honoring Retry-After.** On a 429 from NCBI, write the `Retry-After` deadline into the shared store
(e.g. a `pausedUntil` timestamp). All pods consult it before acquiring and back off until it passes
— so a throttle observed by *one* pod immediately throttles *all* pods. This is the only option that
makes Retry-After genuinely cross-pod coordinated (closes G1, G2, G3, G4-coordination, G5).

**Pros.**
- True shared budget across all pods of *this service*; correct regardless of pod count.
- Throttle signals propagate to every pod.

**Cons / failure modes.**
- **New infrastructure dependency** (ElastiCache or a DynamoDB table). Note DynamoDB SDK was
  deliberately **excluded** from the build (`pom.xml` comment near line 92: "exclude the transitive
  DynamoDB SDK so it is not bundled"), so DynamoDB would mean re-introducing a dependency that was
  intentionally dropped.
- **Per-call coordination latency** on the hot path (a Redis/Dynamo round-trip before every NCBI
  call). For a service whose queries are small (≤2000 articles, single EFetch) this overhead may be
  a meaningful fraction of total latency.
- **Failure mode:** if the limiter store is unreachable, must choose fail-open (risk exceeding
  quota) or fail-closed (block all NCBI traffic). Either is a new outage surface.
- Still does not cover **other services** using the same key (G6 open) unless they share the same
  store.

**Effort:** Medium–Large (~1–2 weeks). New infra (CDK changes in ReCiter-CDK), client library,
atomic-bucket logic, failure-mode policy, integration tests against a real store.

### Option 3 — Org-level / per-API-key quota awareness

**Mechanism.** Recognize that the unit of enforcement is the **API key**, shared across ReCiter,
this tool, and the Scopus tool's NCBI usage. The limiter (token bucket + Retry-After pause state)
is keyed by the API key and shared by **every** service that uses it — e.g. a shared Redis bucket
namespaced by key, or a dedicated "NCBI gateway" service all NCBI traffic routes through that owns
the single budget. This is Option 2 generalized to the org boundary.

**Honoring Retry-After.** Identical to Option 2 but the `pausedUntil` / token state is global to the
key, so a 429 seen by any service throttles all services using that key.

**Pros.**
- The **only** option that can actually guarantee the org stays under the NCBI per-key limit
  (closes G6, and by extension G1 at the true boundary).
- A single gateway centralizes Retry-After handling, logging, and key rotation.

**Cons / failure modes.**
- **Largest blast radius and coordination cost.** Requires changes/agreement across multiple repos
  and teams; a shared gateway becomes a critical-path single point of failure for all NCBI traffic.
- All of Option 2's infra and failure-mode concerns, amplified.
- May be over-engineered if, in practice, this tool is the dominant consumer of the key (verify
  actual key usage across services before committing).

**Effort:** Large (multi-repo, multi-week). Cross-repo coordination (see `cross-repo` workflows),
new shared component, ownership/on-call questions.

---

## 5. Comparison

| Criterion | Opt 1 (per-pod bucket) | Opt 2 (cross-pod store) | Opt 3 (per-key/org) |
|---|---|---|---|
| Closes G1 (shared limiter) | Within a pod only | Across this service's pods | Across all services (true) |
| Closes G2 (EFetch honors headers) | Yes | Yes | Yes |
| Closes G3 (Retry-After cross-coordinated) | Per-pod | Cross-pod | Cross-service |
| Handles unexpected pod scale-out | No (static fraction) | Yes | Yes |
| Handles other services on same key | No | No | Yes |
| New infrastructure | None | Redis or DynamoDB | Redis/gateway + multi-repo |
| Hot-path latency cost | Negligible | Per-call round-trip | Per-call round-trip |
| New failure surface | None | Limiter store | Gateway (critical path) |
| Effort | Small (1–2 d) | Medium–Large (1–2 w) | Large (multi-week) |

---

## 6. Recommendation (phased)

**Phase 0 — Unify and fix the header handling (do regardless of which budget model wins).**
Extract the rate-limit/Retry-After logic out of `executeESearch`/`executeReadingBody` into a single
collaborator consulted by **both** ESearch and EFetch. To do that, EFetch must first move off the
raw `URLConnection` onto the pooled `pubMedHttpClient` (so it can read response headers) — or at
minimum read the `URLConnection` headers it currently discards. Make `@Retryable`/the limiter honor
the server's `Retry-After` on a 429 instead of the random 1.5–9 s backoff. This alone closes G2,
G3, and G5 and is a prerequisite for every other phase. **Small effort; highest value-per-effort.**

**Phase 1 — Ship Option 1 (per-pod in-process token bucket).** Add a Resilience4j (or Guava)
limiter bean sized to `key-quota ÷ maxReplicas ÷ safety-factor` (e.g. 10 ÷ 4 ÷ ~1.2 ≈ 2 req/s/pod),
made configurable via a property so it can be tuned without a rebuild. This gives correct behavior
for the common case (≤4 pods, this tool as the main NCBI consumer) with **zero new
infrastructure**. Document the static-sizing assumption as a known limitation.

**Phase 2 — Promote to Option 2 (shared cross-pod limiter) only if measured 429s persist** after
Phase 1, or if the service routinely runs near `maxReplicas`. Prefer **Redis/ElastiCache** over
DynamoDB to avoid re-introducing the deliberately-excluded DynamoDB SDK. Define the fail-open vs
fail-closed policy up front.

**Phase 3 — Option 3 (org-level) only with cross-team commitment** and after confirming the actual
distribution of NCBI key usage across services. Treat it as a coordinated multi-repo initiative, not
a change to this repo alone. If this tool turns out to be the dominant consumer, Phase 3 may never be
necessary.

**Rationale.** The biggest correctness defect with the smallest fix is the unified Phase 0 header
handling — it makes EFetch honor Retry-After and stops the rate-limit-blind backoff. Phase 1 then
caps aggregate rate cheaply and correctly for the realistic deployment (HPA max 4). The expensive,
infra-bearing options (2 and 3) are justified only by *evidence* (observed 429s, real multi-service
contention), so they are deferred behind measurement rather than built speculatively.

---

## 7. Out of scope / open questions for the verifier

- Confirm NCBI's current published limits (3/s no key, 10/s with key) and that the quota is
  per-key/per-source — these are stated from policy, not from repo code.
- Confirm whether `PUBMED_API_KEY` is actually the **same** key used by the main ReCiter app and the
  Scopus tool in the deployed environment (determines whether G6 / Option 3 is real).
- Async (non-blocking) request handling to fully eliminate G4 (servlet-thread blocking) is a larger
  architectural change and is intentionally left out of this design.

---

*Design only — no code changed. Issue #117 stays open.*
