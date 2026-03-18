# Backyard — Monorepo Architecture (Dev / Stage / Prod)

> Just me, messing around with containers and clusters in my own backyard.

This repo is a monorepo used to evolve a “production-like” Kubernetes setup:
- Local development with `kind` + `Helm`
- CI validation (JUnit test results)
- Container build + publish to **GHCR**
- (Planned) GitOps-style deploy to GCP with **Argo CD + Helm**

## Repo Layout

```
backyard/
├── services/
│   └── playground/                           # Spring Boot API service (Phase 1)
├── infra/
│   ├── kind/
│   │   └── cluster-config.yaml              # kind cluster topology + host port mapping
│   └── helm/
│       └── playground/                     # Helm chart for playground
├── scripts/
│   ├── deploy-service-playground-kind.sh  # Deploy to local kind
│   └── log-service-playground-kind.sh     # Stream logs from both replicas, filter by level
├── .github/workflows/
│   ├── service-playground.yml             # PR build: run tests + publish junit summary to Checks
│   └── service-playground-docker.yml     # main push: build multi-arch image and push to GHCR
└── (root) mvnw / .mvn/wrapper             # Maven Wrapper (fixed Maven version)
```

## Environments (Dev / Stage / Prod)

We use **namespace** to separate environments in Kubernetes:
- `dev`: local kind now uses namespace `playground-dev`
- `stage`: reserved (to be added with `values-stage.yaml` / namespace `playground-stage`)
- `prod`: reserved (to be added with `values-prod.yaml` / namespace `playground-prod`)

Spring profile currently uses:
- `dev`: local readable console logs + DEBUG-friendly behavior
- non-`dev`: structured JSON logs (stdout) + INFO

## Service: `playground`

API endpoint:
- `GET /api/echo`
  - `message` (required)
  - `from` (optional)

Health endpoints (used by probes):
- `/actuator/health/liveness`
- `/actuator/health/readiness`

## Logging Behavior

`dev` profile:
- Logback uses a console/text appender (developer-friendly)

Non-`dev`:
- Logback uses `logstash-logback-encoder` to emit **structured JSON logs** to stdout

In Kubernetes, logs are accessed via `kubectl logs` (not written to log files inside the pod).

## Container Images (GHCR)

For `playground`, the image is published as:
- `ghcr.io/<owner>/service-playground:<git-sha>`

Publishing:
- Trigger: push to `main` (and changes under `services/playground/**`)
- Build: multi-arch (both `linux/amd64` and `linux/arm64`) using `docker/build-push-action`
- Tag: the Git commit SHA (`${{ github.sha }}`)

## CI: Tests + JUnit Summary in Checks

Workflow: `.github/workflows/service-playground.yml`

Trigger:
- PR opened/updated (and later commits) when files under `services/playground/**` change

Steps:
- Run `./mvnw -f services/playground/pom.xml -B test`
- Publish JUnit results via `dorny/test-reporter@v2`
  - JUnit XML path: `services/playground/target/surefire-reports/TEST-*.xml`
  - Creates a visible **Checks** entry with pass/fail summary

## Local Setup (kind + Helm)

Prerequisites:
- `docker`
- `kubectl`
- `kind`
- `helm`

### 1. Create / Recreate kind cluster

Cluster config:
- `infra/kind/cluster-config.yaml`
- 3 nodes: 1 control-plane + 2 workers
- fixed host port mapping for NodePort: `30001 -> 30001`

Example:
```bash
kind create cluster --name backyard-kind --config infra/kind/cluster-config.yaml
```

Verify:
```bash
kubectl get nodes -o wide
```

### 2. Deploy `playground` to kind (2 replicas)

You need the Git SHA tag you want to deploy (from GHCR):
- `ghcr.io/toddychen/service-playground:<sha>`

Deploy script:
```bash
./scripts/deploy-service-playground-kind.sh <git-sha>
```

Defaults used by the script:
- namespace: `playground-dev`
- Helm release name: `service-playground`
- Helm chart: `infra/helm/playground`
- Values: `infra/helm/playground/values-kind.yaml`

NodePort is fixed:
- `http://127.0.0.1:30001/api/echo?...`

### 3. Verify
```bash
curl "http://127.0.0.1:30001/api/echo?message=hello&from=myself"
```

## Handy Developer Scripts

### Deploy
```bash
./scripts/deploy-service-playground-kind.sh <git-sha>
```

### Logs (stream both replicas, filter by level)
```bash
./scripts/log-service-playground-kind.sh INFO
./scripts/log-service-playground-kind.sh ERROR
```

## GitHub → Image → Local Deploy Flow

1. Edit code under `services/playground/`
2. `push` to a branch:
   - PR triggers tests + JUnit summary in Checks
3. Merge to `main`:
   - `service-playground-docker.yml` builds multi-arch image and pushes to GHCR
4. Use the new tag (`<git-sha>`) to deploy to local kind:
```bash
./scripts/deploy-service-playground-kind.sh <git-sha>
```

## Future Work / Placeholders (Pre-reserved)

### Additional services
- `services/dory/` (Phase 2+)

For each new service, we’ll add:
- a Helm chart under `infra/helm/<service>/`
- CI workflows under `.github/workflows/`
- an image publish workflow to GHCR using `:<git-sha>` tagging

### Web / Frontend
- `web/` (dashboard later)

We’ll likely follow the same patterns:
- separate Helm chart (or shared umbrella chart)
- environment-driven values for dev/stage/prod

### GCP GitOps plan (Argo CD + Helm)
- We will store Helm values per environment (dev/stage/prod)
- Argo CD will reconcile those values into a GKE cluster namespaces
- Image tags will be driven by `${git-sha}` produced by CI

