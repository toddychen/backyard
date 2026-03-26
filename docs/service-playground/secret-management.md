# Secret Management

How API keys and secrets are managed across environments.

## Overview

| Environment | Secret Source | How Java reads it |
|---|---|---|
| Local dev | `secrets/api_keys.json` (gitignored) | `GoogleMapsConfig.loadFromFile()` |
| Stage / Prod (GKE) | GCP Secret Manager via Workload Identity | `GoogleMapsConfig.loadFromSecretManager()` |

---

## Local Dev Setup

1. The file `secrets/api_keys.json` is gitignored — never committed.
2. Fill in the key:
```json
{
  "google_maps_api_key": "YOUR_KEY_HERE"
}
```
3. Run the service with the `dev` profile — it reads the file automatically.

---

## GKE Setup (Stage / Prod)

### Step 1 — Store secret in GCP Secret Manager (UI)

1. GCP Console → **Security → Secret Manager → Create Secret**
2. Name: `google_maps_api_key`
3. Value: paste the API key
4. Click Create

### Step 2 — Create GCP Service Account (UI)

1. GCP Console → **IAM & Admin → Service Accounts → Create Service Account**
2. Name: `playground-sa`
3. Click Done (no need to grant roles here)

### Step 3 — Grant Secret Manager access to the Service Account (UI)

1. GCP Console → **IAM & Admin → IAM → Grant Access**
2. New principal: `playground-sa@YOUR_GCP_PROJECT_ID.iam.gserviceaccount.com`
3. Role: `Secret Manager Secret Accessor`
4. Click Save

### Step 4 — Bind K8s Service Accounts to GCP Service Account (CLI)

This links the GKE pods to the GCP service account via Workload Identity.
Run once per environment:

```bash
# Stage
gcloud iam service-accounts add-iam-policy-binding \
  playground-sa@YOUR_GCP_PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:YOUR_GCP_PROJECT_ID.svc.id.goog[playground-stage/playground-stage]"

# Prod (when ready)
gcloud iam service-accounts add-iam-policy-binding \
  playground-sa@YOUR_GCP_PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="serviceAccount:YOUR_GCP_PROJECT_ID.svc.id.goog[playground-prod/playground-prod]"
```

**`--member` format explained:**
```
serviceAccount:YOUR_GCP_PROJECT_ID.svc.id.goog[playground-stage/playground-stage]
               ^project-id                  ^k8s-namespace  ^k8s-serviceaccount-name
```

### Step 5 — Apply K8s ServiceAccount via Helm

The Helm chart creates a K8s ServiceAccount annotated with the GCP service account.
This is already handled in `infra/helm/playground/templates/serviceaccount.yaml`.

---

## How it works end-to-end (GKE)

```
Pod runs with K8s ServiceAccount (playground-stage)
        ↓
Workload Identity links it to GCP ServiceAccount (playground-sa)
        ↓
playground-sa has Secret Manager Accessor role
        ↓
Java calls Secret Manager API at startup — no credentials file needed
        ↓
API key loaded into memory, added to every outbound Google Maps request
```

---

## Adding a new secret

1. Add it to GCP Secret Manager (Step 1 above)
2. Grant `playground-sa` access if it's a different GCP service (Step 3)
3. Add a new `loadFromSecretManager()` call in the relevant config class
4. Add it to `secrets/api_keys.json` for local dev (leave blank in the repo template)
