#!/usr/bin/env bash
# Set image.tag in Helm values for GCP (stage and/or prod).
# Use for promote (new SHA) or revert (previous SHA).
# Usage: ./scripts/set-playground-image-tag.sh <git-sha> [stage|prod|both]
# Example: ./scripts/set-playground-image-tag.sh 082c5528152e9cf1137aac7ff79cfc1c96218d10 both

set -e
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

SHA="${1:?Usage: $0 <git-sha> [stage|prod|both]}"
ENV="${2:-both}"

update_tag() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    echo "Error: $file not found" >&2
    exit 1
  fi
  if [[ "$(uname)" == Darwin ]]; then
    sed -i '' 's/^  tag: ".*"/  tag: "'"$SHA"'"/' "$file"
  else
    sed -i 's/^  tag: ".*"/  tag: "'"$SHA"'"/' "$file"
  fi
  echo "Updated $file -> image.tag: $SHA"
}

case "$ENV" in
  stage)
    update_tag "infra/helm/playground/values-stage.yaml"
    ;;
  prod)
    update_tag "infra/helm/playground/values-prod.yaml"
    ;;
  both)
    update_tag "infra/helm/playground/values-stage.yaml"
    update_tag "infra/helm/playground/values-prod.yaml"
    ;;
  *)
    echo "Error: env must be stage, prod, or both" >&2
    exit 1
    ;;
esac

echo "Done. Commit and push to trigger Argo CD sync (or sync manually)."
