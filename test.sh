#!/usr/bin/env bash
set -euo pipefail

IMAGE="tubignat/orchestrator:latest"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is required but not installed or not in PATH." >&2
  exit 1
fi

echo "Pulling $IMAGE..."
docker pull "$IMAGE" >/dev/null

echo "Starting..."
CID=$(docker run -d -P "$IMAGE")

EXPOSED_PORT=$(docker inspect -f '{{range $p, $_ := .Config.ExposedPorts}}{{println $p}}{{end}}' "$CID" | head -n1)

if [[ -z "${EXPOSED_PORT:-}" ]]; then
  echo "Image does not expose any ports; cannot determine port to map." >&2
  docker rm -f "$CID" >/dev/null 2>&1 || true
  exit 1
fi

HOST_PORT=$(docker port "$CID" "$EXPOSED_PORT" | head -n1 | awk -F: '{print $NF}')

if [[ -z "${HOST_PORT:-}" ]]; then
  echo "Failed to resolve host port mapping for $EXPOSED_PORT" >&2
  docker rm -f "$CID" >/dev/null 2>&1 || true
  exit 1
fi

for i in {1..120}; do
  if curl -s "http://localhost:${HOST_PORT}" | grep -q .; then
    break
  fi
  sleep 1
done

echo "Started on port $HOST_PORT"

echo "Uploading sample app bundles..."

sh sample-bundle.sh my-app
sh sample-bundle.sh test

curl -X POST "http://localhost:${HOST_PORT}/deploy?name=my-app" \
     --header "Content-Type: application/octet-stream" \
     --data-binary @./my-app.tar.gz

rm my-app.tar.gz

curl -X POST "http://localhost:${HOST_PORT}/deploy?name=test" \
     --header "Content-Type: application/octet-stream" \
     --data-binary @./test.tar.gz

rm test.tar.gz

echo "Set up complete"
echo ""
echo ""
echo "Loaded two sample app bundles. Access them via URLs:"
echo "    http://my-app.localhost:${HOST_PORT}"
echo "    http://test.localhost:${HOST_PORT}"
echo ""
echo "NOTE: subdomain routing with localhost works in Chrome and Firefox, but doesn't work in Safari"
echo "To test with Safari, add domains to /etc/hosts and pass it to the orchestrator"
echo "    docker run -p 80:8080 -e DOMAIN=kineto.local tubignat/orchestrator:latest"

echo ""
echo "Commands:"
echo "    curl -X GET \"http://localhost:${HOST_PORT}/status?name=my-app\""
echo "    curl -X GET \"http://localhost:${HOST_PORT}/logs?name=my-app\""
echo "    curl -X POST \"http://localhost:${HOST_PORT}/start?name=my-app\""
echo "    curl -X POST \"http://localhost:${HOST_PORT}/stop?name=my-app\""

echo ""
echo "To deploy a new app:"
echo "    sh sample-bundle.sh new-app"
echo "    curl -X POST \"http://localhost:${HOST_PORT}/deploy?name=new-app\" \\"
echo "       --header \"Content-Type: application/octet-stream\" \\"
echo "       --data-binary @./new-app.tar.gz"
