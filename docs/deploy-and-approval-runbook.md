# Deploy & Approval Runbook — ReCiter-PubMed-Retrieval-Tool

A durable how-to for building, deploying, and approving releases of the
ReCiter-PubMed-Retrieval-Tool Spring Boot microservice to EKS via AWS CodeBuild /
CodePipeline. This is an operational runbook, not an incident report. Read it before
touching the pipeline, the `PubMedService` CodeBuild project, or either buildspec.

> Conventions in this doc:
> - "confirmed" = verified against a repo file or a live `aws` CLI call on 2026-06-13.
> - "as configured" = asserted from project memory / prior sessions but not re-verified
>   from a file or the API in this writing; treat these as facts to re-check (they are
>   listed in the handoff `keyClaims`).
> - Region is `us-east-1` throughout. Account `665083158573` (IAM user `reciter`).

---

## 1. Architecture

### Build & deploy system

A single AWS **CodeBuild project named `PubMedService`** builds the service and
deploys it to the EKS cluster. Confirmed live configuration:

| Property | Value | Source |
|----------|-------|--------|
| CodeBuild project name | `PubMedService` | `aws codebuild batch-get-projects` |
| Image | `aws/codebuild/amazonlinux2-x86_64-standard:5.0` | confirmed live |
| Compute type | `BUILD_GENERAL1_SMALL` | confirmed live |
| Environment type | `LINUX_CONTAINER` | confirmed live |
| Source type | `GITHUB` (`wcmc-its/ReCiter-PubMed-Retrieval-Tool`) | confirmed live |
| Buildspec | `k8-buildspec.yml` | confirmed live |
| Buildspec runtime | `java: corretto11` | `k8-buildspec.yml` line 6 |

The same `PubMedService` project is driven by **both** the Dev and prod pipelines.
The pipeline injects a `BRANCH` environment variable (`#{SourceVariables.BranchName}`)
that the buildspec uses to decide which environment to target (`dev` vs `master`).

### Two buildspecs

| File | Purpose | Status |
|------|---------|--------|
| `buildspec.yml` | Legacy Elastic Beanstalk packaging (artifacts: jar + `.ebextensions` + `Procfile`) | retained, **not** the EKS path |
| `k8-buildspec.yml` | Kubernetes / EKS build + image push + `kubectl set image` deploy | **active** path used by `PubMedService` |

The EKS deploy uses `k8-buildspec.yml`. `buildspec.yml` is kept for the legacy
Beanstalk flow and as a minimal compile/test definition; do not delete it without
confirming nothing references it.

### Curated tests — load test is NEVER run

Both buildspecs run a curated subset of unit tests and deliberately exclude the live
load tests:

```bash
mvn clean install \
  -Dtest=PubmedEFetchHandlerTest,PubMedUriParserCallableTest,PubMedArticleRetrievalServiceTest \
  -DfailIfNoTests=false
```

(`buildspec.yml` line 17; `k8-buildspec.yml` line 50.)

The load tests `ReCiterPubmedApiLoadTest` / `LoadTest` are **never** executed in CI:
they make live HTTP calls to a running service and return **403 Forbidden** when no
service is up. Do not add them to the `-Dtest` list and do not remove the
`-Dtest=` filter (which would run the whole suite, including the load tests).

> Runtime-verification gap: the curated unit tests parse local fixtures with no
> DOCTYPE, and the load test is skipped, so neither catches live-path regressions in
> the SAX parser / HTTP client. For changes touching the parser, HTTP client, deps,
> or the Boot version, **boot the jar and run a real query** (see Section 7).

### CodeBuild environment variables — MUST be preserved

The `PubMedService` project relies on exactly four environment variables. Confirmed
present live:

- `REPOSITORY_URI` — ECR repo URI for the image (`<acct>.dkr.ecr.us-east-1.amazonaws.com/<repo>`)
- `EKS_KUBECTL_ROLE_ARN` — IAM role assumed to run `kubectl` against the cluster
- `EKS_CLUSTER_NAME` — EKS cluster name (also used as the kubectl `-n` namespace in the buildspec)
- `DOCKER_HUB_CREDENTIALS_ARN` — Secrets Manager ARN for Docker Hub login (avoids anonymous pull rate limits)

**Critical:** `aws codebuild update-project --environment` **REPLACES the entire
environment block**. Any update that passes `--environment` must re-list all four
variables or they will be silently dropped, breaking the deploy. See Section 4(c).

### Runtime topology (what gets deployed)

- EKS cluster `reciter`, namespace `reciter` *(as configured)*.
- Deployments: **`reciter-pubmed-dev`** and **`reciter-pubmed-prod`** (rendered from
  `kubernetes/k8-deployment.yaml` via `sed` token substitution in the buildspec).
- Each pod is **2 containers**: the app (`reciter-pubmed`, port 5000) and an `nginx`
  sidecar (port 80) that reverse-proxies `/pubmed/*` to `localhost:5000`. A healthy
  rolled pod therefore shows **`2/2` Ready**.
- App container runs as non-root UID/GID `10001`, `JAVA_OPTIONS=-Xmx2000m`,
  `PUBMED_API_KEY` from secret `pubmed-secret`, `SERVER_PORT` from configmap
  `env-config-dev` / `env-config-prod`.
- Health endpoint: `GET /pubmed/ping` → `Healthy` (used by liveness/readiness probes
  on port 5000; nginx liveness/readiness use `/nginx-health` on port 80).
- HPA: `hpa-reciter-pubmed-dev` / `hpa-reciter-pubmed-prod`, min 1 / max 4 replicas,
  CPU 80% / memory 85% targets.
- Nodes: `nodeSelector: lifecycle: Ec2Spot`.

---

## 2. Pipeline flow

Both pipelines have the **same three-stage shape** (confirmed live):
`Source → ManualApproval → Build`. There is **no separate CodePipeline Deploy
stage** — the deploy is performed inside the CodeBuild `Build` stage by the buildspec
(`docker push` + `aws eks update-kubeconfig` + `kubectl set image`). Approval gates
the **build+deploy**, not a downstream deploy action.

### Dev flow

Pipeline: **`ReCiter-Pubmed-Retrieval-Tool-Dev`** (Source branch `dev`, confirmed).

```
push / merge to `dev`
  → CodePipeline Source (GitHub, branch dev)
  → ManualApproval gate            (agent/user may approve dev)
  → Build (CodeBuild PubMedService, BRANCH=dev)
        mvn curated tests → docker build/push $REPOSITORY_URI:dev-<n>...
        aws eks update-kubeconfig --role-arn $EKS_KUBECTL_ROLE_ARN
        kubectl set image deployment/reciter-pubmed-dev reciter-pubmed=$REPOSITORY_URI:<tag> -n $EKS_CLUSTER_NAME
  → verify: pod reciter-pubmed-dev rolled, 2/2 Ready, /pubmed/ping = Healthy
```

Dev approvals are routine and may be approved by the agent or user.

Verify a dev rollout:

```bash
aws eks update-kubeconfig --name reciter --region us-east-1   # or assume EKS_KUBECTL_ROLE_ARN
kubectl -n reciter rollout status deployment/reciter-pubmed-dev
kubectl -n reciter get pods -l app=reciter-pubmed-dev          # expect 2/2 Ready
kubectl -n reciter get deployment reciter-pubmed-dev \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'   # confirm new tag
```

> A second, parallel dev pipeline `ReCiter-Pubmed-Retrieval-Tool-Dev_V2` also exists
> (confirmed in `list-pipelines`). Confirm which dev pipeline is the live one before
> approving — do not approve both for the same change.

### Prod flow

Pipeline: **`ReCiter-Pubmed-Retrieval-Tool-prod`** (Source branch `master`, confirmed).

```
merge to `master`
  → CodePipeline Source (GitHub, branch master)
  → ManualApproval gate            (NON-hotfix: mrj4001 only — see Section 3)
  → Build (CodeBuild PubMedService, BRANCH=master)
        mvn curated tests → docker build/push $REPOSITORY_URI:master-<n>...
        kubectl set image deployment/reciter-pubmed-prod reciter-pubmed=$REPOSITORY_URI:<tag> -n $EKS_CLUSTER_NAME
  → verify: pod reciter-pubmed-prod rolled, 2/2 Ready
```

Verify a prod rollout (swap `dev`→`prod` in the dev commands above):

```bash
kubectl -n reciter rollout status deployment/reciter-pubmed-prod
kubectl -n reciter get pods -l app=reciter-pubmed-prod          # expect 2/2 Ready
kubectl -n reciter get deployment reciter-pubmed-prod \
  -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
```

---

## 3. Approval policy

**Non-hotfix production deploys MUST be approved by `mrj4001`** at the
`ReCiter-Pubmed-Retrieval-Tool-prod` ManualApproval gate. The agent and the requesting
user (`paa2013`) must **not** rubber-stamp routine or catch-up prod releases — leave
the gate pending and hand off / notify `mrj4001`.

**Hotfixes are the carve-out** and may be fast-tracked (approved promptly by
agent/user) — e.g. a live-outage fix such as the EFetch DOCTYPE-parsing hotfix. Use
judgment: a "hotfix" is a narrowly-scoped fix for an active production defect, not a
bundle of accumulated changes.

**Dev approvals** are routine and may be approved by the agent/user.

### Notification channel

The approval gate notifies the SNS topic **`reciter-pipeline-validation`**
(`arn:aws:sns:us-east-1:665083158573:reciter-pipeline-validation`, confirmed).

Current email subscribers (confirmed live, 2026-06-13):

| Endpoint | Protocol |
|----------|----------|
| `paa2013@med.cornell.edu` | email |
| `szd2013@med.cornell.edu` | email |

> **ENABLEMENT GAP (action item, not a permanent fact):** `mrj4001` is **not** yet a
> subscriber of `reciter-pipeline-validation`, yet policy requires them to approve
> non-hotfix prod deploys. To close the gap, `mrj4001` needs:
> 1. an **email subscription** to the topic, and
> 2. console **`codepipeline:PutApprovalResult`** access (federated/SSO — `mrj4001`
>    is a CWID, not an IAM user) *(as configured)*.
>
> Subscribe (then `mrj4001` must click the confirmation email):
> ```bash
> aws sns subscribe \
>   --topic-arn arn:aws:sns:us-east-1:665083158573:reciter-pipeline-validation \
>   --protocol email \
>   --notification-endpoint mrj4001@med.cornell.edu
> ```
> Until this is done, `mrj4001` will not receive gate notifications; coordinate the
> hand-off out of band.

---

## 4. Gotchas (read before approving or editing the project)

### (a) Stale executions pile up at the ManualApproval gate

Because approvals are manual and several executions can queue (e.g. a superseded
older build still holding a token), **the token surfaced at the gate may not belong
to the execution you want to deploy.** Approving a stale token returns an `approvedAt`
timestamp but does **not** advance the latest revision.

Always map **approval token → execution → revision** before acting, and **reject**
stale gate-holders so the latest can advance.

```bash
PIPE=ReCiter-Pubmed-Retrieval-Tool-prod

# What is sitting at the gate right now (token + which execution holds it)?
aws codepipeline get-pipeline-state --name "$PIPE" \
  --query 'stageStates[?stageName==`ManualApproval`].actionStates[0].latestExecution' \
  --output json

# Map that execution to its source revision (commit) so you know what you'd ship.
aws codepipeline list-pipeline-executions --pipeline-name "$PIPE" \
  --max-items 10 \
  --query 'pipelineExecutionSummaries[*].{id:pipelineExecutionId,status:status,rev:sourceRevisions[0].revisionId,summary:sourceRevisions[0].revisionSummary}' \
  --output table
```

Reject a stale holder so a newer execution can take the gate:

```bash
aws codepipeline put-approval-result \
  --pipeline-name "$PIPE" \
  --stage-name ManualApproval \
  --action-name ManualApproval \
  --token <STALE_TOKEN> \
  --result status=Rejected,summary='superseded by newer execution'
```

### (b) `put-approval-result --result` shorthand breaks on a comma in `summary=`

The CLI shorthand parses `key=value,key=value`, so a **comma inside the `summary`
text** is read as a new key and the call fails. Either omit commas, or pass the
result as JSON.

Broken (comma inside summary):
```bash
# FAILS — the comma after "approved" is parsed as a field separator
--result status=Approved,summary='approved, deploying master'
```

Works (no comma):
```bash
--result status=Approved,summary='approved deploying master'
```

Works (JSON form — safe with commas/quotes):
```bash
aws codepipeline put-approval-result \
  --pipeline-name ReCiter-Pubmed-Retrieval-Tool-prod \
  --stage-name ManualApproval \
  --action-name ManualApproval \
  --token <LIVE_TOKEN> \
  --result '{"status":"Approved","summary":"approved by mrj4001, master catch-up"}'
```

### (c) `update-project --environment` REPLACES the whole environment block

Any `aws codebuild update-project` that touches `--environment` must re-list **all
four** env vars (`REPOSITORY_URI`, `EKS_KUBECTL_ROLE_ARN`, `EKS_CLUSTER_NAME`,
`DOCKER_HUB_CREDENTIALS_ARN`) or they are dropped and the deploy breaks. Read the
current values first, change only what you intend, and write them all back.

```bash
# 1. Dump the current environment so you don't lose anything.
aws codebuild batch-get-projects --names PubMedService \
  --query 'projects[0].environment' --output json

# 2. Re-apply ALL four env vars even when you are only changing the image.
aws codebuild update-project --name PubMedService --environment '{
  "type": "LINUX_CONTAINER",
  "image": "aws/codebuild/amazonlinux2-x86_64-standard:5.0",
  "computeType": "BUILD_GENERAL1_SMALL",
  "privilegedMode": true,
  "environmentVariables": [
    {"name":"REPOSITORY_URI",            "value":"<value>", "type":"PLAINTEXT"},
    {"name":"EKS_KUBECTL_ROLE_ARN",      "value":"<value>", "type":"PLAINTEXT"},
    {"name":"EKS_CLUSTER_NAME",          "value":"<value>", "type":"PLAINTEXT"},
    {"name":"DOCKER_HUB_CREDENTIALS_ARN","value":"<value>", "type":"PLAINTEXT"}
  ]
}'

# 3. Verify all four survived.
aws codebuild batch-get-projects --names PubMedService \
  --query 'projects[0].environment.environmentVariables[*].name' --output json
```

> `privilegedMode: true` is required because the buildspec runs `docker build`/`docker
> push` (confirmed live via `aws codebuild batch-get-projects --names PubMedService` on
> 2026-06-13).

### (d) Any buildspec edit must keep three things

When editing `buildspec.yml` or `k8-buildspec.yml`, preserve all of:

1. **`runtime-versions: java: corretto11`** — the AL2 `standard:5.0` image's TLS
   truststore + corretto11 resolves Maven Central; do not revert to `openjdk11` or an
   older image.
2. **An explicit `kubectl` install** — modern CodeBuild images do **not** bundle
   `kubectl`. `k8-buildspec.yml` pins it:
   ```yaml
   - curl -sSLo /usr/local/bin/kubectl https://dl.k8s.io/release/v1.29.10/bin/linux/amd64/kubectl
   - chmod +x /usr/local/bin/kubectl
   - kubectl version --client      # note: no --short (removed in modern kubectl)
   ```
3. **`aws ecr get-login-password`** for the ECR login — AWS CLI v2 removed
   `aws ecr get-login`:
   ```yaml
   - aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${REPOSITORY_URI%%/*}
   ```

Also keep the curated `-Dtest=...` filter (Section 1) so the load tests never run.

---

## 5. Background — why the image and runtime matter

From roughly **July 2025 until 2026-06**, the prod deploy was **silently dead**. The
`PubMedService` CodeBuild project ran on the deprecated **`aws/codebuild/standard:3.0`**
image (Ubuntu 18.04, EOL). Its outdated TLS truststore could not authenticate to
Maven Central, so `mvn clean install` failed with `Could not transfer artifact ...
peer not authenticated` and the build exited non-zero. This was the long-standing
"always-red AWS CodeBuild (PubMedService)" check on every `master` PR. Local builds
(modern JDK 11 + cached `~/.m2`) succeeded, which masked the breakage.

Consequence: live prod ran image tag
`master-374.2025-07-03.04.48.11.c73d3492` — **July 2025 (commit `c73d349`)** *(as
configured)* — so ~11 months of merges were undeployed.

**Fix (2026-06):** the project image was switched to **`aws/codebuild/amazonlinux2-x86_64-standard:5.0`**
(confirmed live) and the buildspecs were modernized in **PR #147** (`openjdk11` →
`corretto11`, explicit pinned `kubectl` install, `aws ecr get-login` →
`aws ecr get-login-password`). Dev was validated end-to-end (first successful deploy in
~11 months); the prod catch-up was the deliberate follow-up gated on `mrj4001`.

Takeaway for operators: **never downgrade the CodeBuild image or the buildspec
runtime.** A green local build is not evidence the pipeline builds — verify the
CodeBuild run itself (Section 6).

---

## 6. IaC caveat — the pipeline is NOT in CDK

The deploy pipeline and the `PubMedService` CodeBuild project are **not** modeled by
the current ReCiter-CDK. As configured, only the **ACM / ECR / S3 / RDS / Public**
stacks are actually deployed from CDK; the CDK additionally models a **Fargate**
architecture that is **not in use** (prod runs on EKS, not Fargate).

Practical implications:

- Make pipeline / CodeBuild changes via **AWS CLI or the console**, not by editing
  CDK and running `cdk deploy`. A CDK deploy will not touch `PubMedService` and could
  drift the Fargate model further from reality.
- Because there is no IaC source of truth for these resources, **dump-before-edit**
  (Section 4c) and re-verify after every change.
- Record any manual change (image, env vars, buildspec branch logic) in project
  memory so the next operator knows the live state.

---

## 7. Runtime verification (do this for parser / HTTP / deps / Boot changes)

A build can be fully green while the live retrieval path is 100% broken (the EFetch
DOCTYPE regression is the canonical example: every real NCBI EFetch response begins
with `<!DOCTYPE PubmedArticleSet PUBLIC ...>`, which a DOCTYPE-disallowing SAX parser
rejected → HTTP 500 on every query, with all unit tests still passing). For any change
touching the SAX parser, HTTP client, dependencies, or the Spring Boot version,
**runtime-verify** before approving a deploy.

Local (JDK 11 required — Lombok 1.18.34 does not run on JDK 17+):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home
export PUBMED_API_KEY=<key>
mvn -B -ntp clean package -DskipTests
java -jar target/reciter-pubmed-retrieval-tool-1.1.0.jar --server.port=8083 &
# Expect HTTP 200 with parsed articles (NOT a 500):
curl -s 'http://localhost:8083/pubmed/ping'                       # -> Healthy
curl -s 'http://localhost:8083/pubmed/query/Kukafka%20R%5Bau%5D' | head -c 400
```

Post-deploy (in-cluster), confirm a real query against the rolled pod, not just
`/pubmed/ping`:

```bash
kubectl -n reciter exec deploy/reciter-pubmed-dev -c reciter-pubmed -- \
  wget -qO- 'http://localhost:5000/pubmed/query/Kukafka%20R%5Bau%5D' | head -c 400
```

---

## 8. Quick reference

| Thing | Value |
|-------|-------|
| CodeBuild project | `PubMedService` (image `aws/codebuild/amazonlinux2-x86_64-standard:5.0`, buildspec `k8-buildspec.yml`) |
| Dev pipeline | `ReCiter-Pubmed-Retrieval-Tool-Dev` (branch `dev`) — also `..._Dev_V2` exists |
| Prod pipeline | `ReCiter-Pubmed-Retrieval-Tool-prod` (branch `master`) |
| Pipeline stages | `Source → ManualApproval → Build` (Build does the deploy; no separate Deploy stage) |
| Deployments | `reciter-pubmed-dev`, `reciter-pubmed-prod` (ns `reciter`, cluster `reciter`) |
| Healthy pod | `2/2` (app + nginx sidecar); `GET /pubmed/ping` → `Healthy` |
| CodeBuild env vars (preserve all 4) | `REPOSITORY_URI`, `EKS_KUBECTL_ROLE_ARN`, `EKS_CLUSTER_NAME`, `DOCKER_HUB_CREDENTIALS_ARN` |
| Approval (non-hotfix prod) | `mrj4001` only; hotfixes fast-trackable; dev = agent/user |
| Notification topic | `reciter-pipeline-validation` (subs: `paa2013@`, `szd2013@`; `mrj4001` NOT yet subscribed) |
| Curated tests | `PubmedEFetchHandlerTest`, `PubMedUriParserCallableTest`, `PubMedArticleRetrievalServiceTest`; load test never run |
| IaC | NOT in CDK — change via AWS CLI/console |
