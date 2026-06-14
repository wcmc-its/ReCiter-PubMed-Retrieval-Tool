# Boot 3 / Java 17 / jakarta Migration — Scoping & Sequencing (#136)

**Status:** Scoping plan for a **deferred** epic. This is sequencing, not a green light. Per #136, the trigger to *start* is an org mandate or a CVE only a 3.x patch fixes — **not a calendar date**.
**Issue:** #136 · **Related:** #130 (trailing-slash routes 404 under Boot 3 — a hard prerequisite), #113/#146 (2.7 bump), #120/#153 (springdoc), #122/#142 (tests).
**Date:** 2026-06-14 · refs against `dev` (`2a7689d`).

---

## 1. What changed since the issue was filed — both gates are now cleared

The issue named two prerequisites for even considering this. Both are now done on `dev` (awaiting dev→master promotion):

| Gate (per #136) | Status |
|-----------------|--------|
| **Springfox removal** (the "hard blocker" — Springfox is incompatible with Boot 3) | ✅ Done — #120/#153 replaced Springfox with springdoc-openapi 1.8.0 |
| **Real unit-test safety net in CI** (#122 — parser edge cases, query-drop, threshold) | ✅ Done — #142/#122; 20 tests run in CodeBuild |
| Bounded 2.5→2.7 stepping stone (#113) | ✅ Done — #146 (Boot 2.7.18, still javax) |

**Implication:** the migration is materially de-risked versus when it was deferred. The remaining work is a Boot 2.7→3.x dependency/version coordination, not a code rewrite (see §2).

---

## 2. Migration surface assessment — small, and dependency-driven

This is a ~2,650-LOC internal service. The actual code-level jakarta surface is **near-zero**:

- The only `javax.*` imports are `javax.xml.parsers.*` / `javax.xml.*` (4 files). **These are JAXP/SAX from the JDK (`java.xml` module) — NOT part of the `javax → jakarta` rename.** They stay unchanged on Java 17 / Boot 3.
- **No** `javax.servlet`, `javax.persistence`, `javax.validation`, `javax.annotation` usage in `src/main` on `dev`. So there is no namespace rewrite of application code.
- Controllers use Spring abstractions (`ResponseEntity`, `@RequestMapping`) only — these survive Boot 3 with minor semantics changes (trailing slash, see §4).

So the migration is **driven by dependencies and the runtime**, not source rewrites:

| Component | From (dev) | To (Boot 3) | Risk |
|-----------|------------|-------------|------|
| Spring Boot | 2.7.18 | 3.3.x / 3.4.x (only OSS-security-supported line) | medium — transitive churn |
| Java | 11 | 17 (Corretto) | low — see `MigrationFromJDk11ToAWSCorretto17` (but see §3) |
| Embedded Tomcat | 9.x | 10.x (jakarta) | low — transitive via Boot |
| springdoc-openapi | 1.8.0 | 2.x (requires Boot 3 / jakarta) | low — same project, config 1:1 |
| squiggly-filter-jackson | 1.3.18 | **unverified on Boot 3 / Jackson 2.15+** | **HIGH — the real wildcard (§3)** |
| Lombok | 1.18.34 | ok for 17 | low |
| reciter-pubmed-model | 2.0.3 | unchanged (no javax.servlet; jackson/dynamodb/lombok only) | low |
| zerocode-tdd / testng / mockito | test scope | verify Boot 3 / Java 17 compat | low–medium |

---

## 3. Risk register

1. **`squiggly-filter-jackson:1.3.18` (highest risk).** Unmaintained; pins to older Jackson internals. Boot 3 ships Jackson 2.15+. If Squiggly breaks, the `fields` feature needs a replacement (e.g. Jackson `@JsonFilter`/`FilterProvider`, or `josdejong/squiggly` fork). **Phase 0 must dependency-dry-run this before committing.**
2. **The existing `MigrationFromJDk11ToAWSCorretto17` branch is a trap — do not use it as a base.** It has diverged badly from current `dev`: it *deletes* the very tests #122 added (`PubMedArticleRetrievalServiceTest` −191, `PubmedEFetchHandlerTest` −30), removes `GlobalExceptionHandler`/`HttpClientConfig`/`logback-spring.xml`, and adds header-dump debug cruft ("dumping all the headers… for debugging the protocal", "commented this code"). Start the migration **fresh from `dev`**; harvest at most isolated ideas (e.g. `ProxyConfig`) by cherry-pick, not the branch.
3. **Trailing-slash 404 (#130 P1).** Spring 6 removes implicit trailing-slash matching; ReCiter calls `POST …/query-complex/` and `…/query-number-pubmed-articles/` with hardcoded trailing slashes. **This breaks the ReCiter integration on cutover unless #130 step 1 (slash-tolerant routes) lands first.** Hard prerequisite, not optional.
4. **Deploy pipeline / image.** CodeBuild now builds corretto11 (#147). Java 17 needs the buildspec/image bumped to corretto17 and the Dockerfile base image updated — coordinate with the (recently repaired) prod pipeline.

---

## 4. Phased plan (when triggered)

**Phase 0 — De-risk (no production change):**
- Dependency dry-run on a throwaway branch: bump Boot → 3.3.x, Java → 17, springdoc → 2.x; resolve the tree; **specifically prove `squiggly-filter-jackson` works or pick a replacement.** Output: a go/no-go with the exact replacement plan for any incompatible dep.
- Confirm the test suite (#122) is green as the regression net **before** touching anything.

**Phase 1 — API slash-tolerance (#130 step 1):** land the additive, slash-tolerant routes so the Boot 3 cutover can't 404 ReCiter. Ships independently, non-breaking.

**Phase 2 — Boot 3 + jakarta + Tomcat 10:** bump Boot to 3.x, springdoc to 2.x; apply any jakarta touch-ups (expected minimal per §2); fix `application.properties` keys renamed in Boot 3; run the full suite + runtime boot (ping, real query, count, swagger, rate limiter).

**Phase 3 — Java 17 / Corretto:** set `java.version=17`, bump CodeBuild + Dockerfile to corretto17, remove any `--source 11` constraints. Re-run suite + runtime.

**Phase 4 — Cleanup:** drop legacy trailing-slash routes once ReCiter is on the new paths (#130 step 3); delete the stale `MigrationFromJDk11ToAWSCorretto17` branch.

Each phase is independently buildable/verifiable on JDK 17, gated by the #122 suite + a runtime boot smoke (the same battery used in `plans/DEV-PROMOTION-READINESS.md`).

---

## 5. Trigger & decision

Unchanged from #136: **do not start without an org mandate or a Boot-3-only CVE.** Java 11 is supported into ~2027; Boot 2.7 is past free OSS EOL, which is the main standing pressure. When the trigger arrives, Phase 0's squiggly dry-run is the single most important gate — schedule it first.

**Bottom line:** the blockers the issue cited are gone; the migration is now mostly a dependency-coordination project with one genuine wildcard (Squiggly) and one hard cross-service prerequisite (#130 trailing slashes). Estimated as a small number of sequential PRs, not an open-ended rewrite.
