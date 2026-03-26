# Backyard — Monorepo Architecture (Dev / Stage / Prod)

> Just me, messing around with containers and clusters in my own backyard.

This repo is a monorepo used to evolve a “production-like” Kubernetes setup:
- Local development with `kind` + `Helm`
- CI validation (JUnit test results)
- Container build + publish to **GHCR**
- GitOps deploy to GCP with **Argo CD + Helm** (stage & prod, 2 replicas each)

## Repo Layout

```
backyard/
├── services/
│   └── playground/                           # Spring Boot API service (Phase 1)
├── infra/
│   ├── kind/
│   │   └── cluster-config.yaml              # kind cluster topology + host port mapping
│   ├── helm/
│   │   └── playground/                       # Helm chart (values-stage.yaml, values-prod.yaml)
│   └── argocd/
│       └── applications/                     # Argo CD Application manifests for GCP
│           ├── playground-stage.yaml
│           └── playground-prod.yaml
├── scripts/
│   ├── deploy-service-playground-kind.sh    # Deploy to local kind
│   ├── log-service-playground-kind.sh       # Stream logs from both replicas, filter by level
│   └── set-playground-image-tag.sh           # Set image tag for GCP promote/revert
├── .github/workflows/
│   ├── service-playground.yml             # PR build: run tests + publish junit summary to Checks
│   └── service-playground-docker.yml     # main push: build multi-arch image and push to GHCR
└── (root) mvnw / .mvn/wrapper             # Maven Wrapper (fixed Maven version)
```

## Environments (Dev / Stage / Prod)

We use **namespace** to separate environments in Kubernetes:
- `dev`: local kind now uses namespace `playground-dev`
- **stage**: GCP stage uses namespace `playground-stage` and `values-stage.yaml` (2 replicas)
- **prod**: GCP prod uses namespace `playground-prod` and `values-prod.yaml` (2 replicas)

Spring profile currently uses:
- `dev`: local readable console logs + DEBUG-friendly behavior
- non-`dev`: structured JSON logs (stdout) + INFO

## Service: `playground`

API endpoint:
- `GET /api/v1/echo`
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
- `http://127.0.0.1:30001/api/v1/echo?...`

### 3. Verify
```bash
curl "http://127.0.0.1:30001/api/v1/echo?message=hello&from=myself"
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

## GCP Deploy (Argo CD + Helm)

Stage runs 1 replica and prod runs 2 replicas. Argo CD syncs from this repo; the image tag is in the values files so you can **promote** (new SHA) or **revert** (previous SHA) by changing Git and letting Argo CD sync.

**Prerequisites:** GKE cluster with Argo CD installed; Argo CD has access to this Git repo.

**Bootstrap (one-time):** With `kubectl` pointing at GKE:
```bash
kubectl apply -f infra/argocd/applications/playground-stage.yaml
kubectl apply -f infra/argocd/applications/playground-prod.yaml
```

**Promote (deploy new image):**
```bash
./scripts/set-playground-image-tag.sh <git-sha> [stage|prod|both]
git add infra/helm/playground/values-*.yaml && git commit -m "chore(playground): set image to <git-sha>" && git push
```

**Revert (roll back):** Run the same script with the **previous** Git SHA, then commit and push. Argo CD will sync and roll the Deployment back.

Before first deploy, replace `REPLACE_WITH_GIT_SHA` in `values-stage.yaml` and `values-prod.yaml` with a real SHA (e.g. via the script above). Set `ingress.hosts[0].host` in each file when you have stage/prod hostnames.

For temporary local access during debugging (without public ingress), see:
- `docs/service-playground/k8s-port-forward.md`

---

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

### GCP Deploy (Argo CD + Helm) — implemented

- Stage runs 1 replica and prod runs 2 replicas; Argo CD syncs from this repo.
- **Promote**: run `./scripts/set-playground-image-tag.sh <git-sha> [stage|prod|both]`, commit and push; Argo CD syncs.
- **Revert**: run the same script with the previous SHA, commit and push.
- Bootstrap: `kubectl apply -f infra/argocd/applications/playground-stage.yaml` (and `playground-prod.yaml`) on the GKE cluster where Argo CD runs.

