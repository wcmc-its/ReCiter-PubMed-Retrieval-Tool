# REST API Conventions — Design & Client-Impact (#130)

**Status:** Design proposal. **No code changes here** — this doc exists so the breaking pieces are coordinated with the consuming ReCiter client before implementation, per the issue.
**Issue:** #130 · **Related:** #136 (Boot 3 — the trailing-slash routes 404 there, so the two are coupled).
**Date:** 2026-06-14 · refs are against `dev` (`2a7689d`).

---

## 1. Problems (from the Jun 2026 review)

| # | Problem | Location | Breaking to clients? |
|---|---------|----------|----------------------|
| P1 | Trailing-slash routes (`/query-complex/`, `/query-number-pubmed-articles/`) rely on legacy matching removed by default in Spring 6 / Boot 3 → will 404 after the upgrade | controller `@RequestMapping` values | **YES** (see §2) |
| P2 | Primary search term is a path variable (`/query/{query}`) containing spaces and `[]` — proxies/Tomcat may reject; belongs in a query param or body | `GET /query/{query}` | **No** — unused by ReCiter (§2) |
| P3 | Inconsistent return types (`int`, `List<…>`, `ResponseEntity<List<…>>`); no URL versioning | all 3 query endpoints | Partly |
| P4 | `fields` Squiggly filter is force-lowercased and unvalidated → typos silently yield empty objects | controller `~95–115` | **No** — unused by ReCiter (§2) |

---

## 2. Client-impact analysis (evidence-based)

The only in-workspace consumer is the main **ReCiter** app. It calls exactly three endpoints, all via `PUBMED_SERVICE` (env var) + `RestTemplate`:

| Endpoint (as called) | ReCiter call site | Shape |
|----------------------|-------------------|-------|
| `POST /pubmed/query-complex/` | `pubmed/retriever/PubMedArticleRetriever.java:46,51` | body `PubMedQuery` → `PubMedArticle[]` |
| `POST /pubmed/query-number-pubmed-articles/` | `xml/retriever/pubmed/AbstractRetrievalStrategy.java:270` | body `PubMedQuery` → `int` |
| `GET /pubmed/ping` | `Application.java:218` | health probe |

ReCiter prepends `/pubmed` only when `PUBMED_SERVICE` doesn't already end in `/pubmed`, and **hardcodes the trailing slash** on both POSTs.

**Decisive consequences:**
- ReCiter does **not** use `GET /query/{query}` or the `fields` param. So **P2 and P4 are internal-only** — they can be fixed unilaterally with zero client coordination. (`/query/{query}` has no in-workspace consumer at all; confirm no external/manual users before removing it, but it is safe to *augment*.)
- The **breaking surface is precisely the two trailing-slash POST paths** ReCiter hardcodes. Any rename, de-slashing, or version-prefixing of those **breaks ReCiter unless coordinated**.
- That same trailing slash is what **404s under Boot 3** (Spring 6 sets `setUseTrailingSlashMatch(false)` permanently). So #130's P1 and the #136 migration must land together or ReCiter retrieval breaks in prod.

---

## 3. Proposed canonical API

Target a clean, versioned surface while keeping a **compatibility window**:

| Concern | Proposal |
|---------|----------|
| Versioning | Introduce a `/v1` segment: `/pubmed/v1/articles`, `/pubmed/v1/article-count`. Frees future breaking changes to go to `/v2`. |
| Trailing slashes | Canonical paths have **no** trailing slash. During transition, explicitly map both slash and no-slash (an explicit dual `@RequestMapping(path = {"/x", "/x/"})` works even on Boot 3, since the 404 is only from *implicit* matching). |
| Search term placement (P2) | Keep term in the **body** (already true for the two POSTs ReCiter uses). For the GET, add `GET /pubmed/v1/articles?term=...` (query param, URL-encoded) and deprecate the `{query}` path variable. |
| Return types (P3) | Standardize on `ResponseEntity<T>` with explicit status codes. Count returns `{"count": N}` JSON, not a bare `int`. |
| `fields` validation (P4) | Validate the Squiggly expression against the `PubMedArticle` schema; return `400` with the offending token instead of silently emptying objects. Drop the blanket `toLowerCase()` (it already relies on `ACCEPT_CASE_INSENSITIVE_PROPERTIES`). |

---

## 4. Migration strategy (coordinated, phased)

Because the breaking surface is small (two POST paths) and the client is in the same org, a **dual-route transition** is cheap and zero-downtime:

1. **Tool, additive (non-breaking):** add the new canonical routes *and* keep the existing trailing-slash routes by mapping both path forms explicitly. Fix P2/P4 (internal) in the same release. Ship to dev → prod. Nothing breaks.
2. **Client (ReCiter):** switch `PubMedArticleRetriever`/`AbstractRetrievalStrategy` to the new no-slash (`/v1`) paths. Deploy.
3. **Tool, removal (breaking, later):** once ReCiter is confirmed on the new paths in prod, drop the legacy trailing-slash routes. This is the only step that requires the explicit slash-matching shim to be removed — and by then nothing calls it.

This sequencing means **P1 is resolved before the Boot 3 cutover** (#136): after step 1, the tool no longer depends on implicit trailing-slash matching, so the Boot 3 upgrade can't 404 the ReCiter integration.

---

## 5. Recommended decisions / open questions for the ReCiter-client owners

- **OK to add a `/v1` segment?** (Cleanest, but ReCiter must update `PUBMED_SERVICE` path handling.) If not, a slash-tolerant shim on the existing paths is the minimum viable fix for the Boot 3 blocker.
- **Is `GET /query/{query}` used by anything outside this workspace** (manual ops, dashboards, other services)? If truly unused, P2 collapses to "delete it." If used, add the query-param form and deprecate.
- **Count response shape:** bare `int` → `{"count": N}` is cleaner but is itself a breaking change for ReCiter's `int` deserialization. Either keep `int` for `/v1` or coordinate the DTO change.

## 6. Sequencing recommendation

Do **step 1 (additive)** as a small near-term PR — it is non-breaking, fixes the internal P2/P4 cleanly, and **unblocks the Boot 3 migration** by removing the implicit-trailing-slash dependency. Defer steps 2–3 to be coordinated with the ReCiter deploy cadence. Treat P1 (slash-tolerance) as a **hard prerequisite checklist item inside #136**, not an independent effort.
