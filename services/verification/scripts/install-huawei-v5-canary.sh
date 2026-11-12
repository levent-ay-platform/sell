#!/usr/bin/env bash
# Malak: isolated Huawei PRIVATE canary. No CRM changes or public ports.
# Explicit --install-canary required. NEVER mounts the real HTTPS gateway.
set -Eeuo pipefail
umask 077

ROOT="$(cd -- "$(dirname -- "$0")/../../.." && pwd)"
cd "$ROOT"
NAME="malak-v5-canary"
IMAGE="malak/huawei-arabic-v5:private-canary"
DOCKERFILE="services/verification/docker/HuaweiCinV5.Dockerfile"
EXPECTED_SHA="$(printenv MALAK_EXPECT_HEAD || true)"
MODE="--preflight"
if (( $# > 0 )); then MODE="$1"; fi
die(){ printf 'BLOCKED: %s\n' "$*" >&2; exit 1; }

case "$MODE" in --preflight|--install-canary|--verify-canary) ;; *) die 'invalid command' ;; esac
command -v git >/dev/null || die 'git required'
command -v docker >/dev/null || die 'Docker required'
[[ "$(uname -s)" == "Linux" ]] || die 'Huawei must be Linux'
[[ -f "$DOCKERFILE" ]] || die 'Missing model Dockerfile'
[[ "$EXPECTED_SHA" =~ ^[a-f0-9]{40}$ ]] || die 'Set MALAK_EXPECT_HEAD to audited 40-char SHA'
[[ "$(git rev-parse HEAD)" == "$EXPECTED_SHA" ]] || die 'Wrong Git commit'
git diff --quiet && git diff --cached --quiet || die 'Dirty checkout'
docker info --format '{{.ServerVersion}}' >/dev/null || die 'Docker daemon unavailable'

if [[ "$MODE" == "--preflight" || "$MODE" == "--install-canary" ]]; then
  mem_free="$(awk '/MemAvailable:/ {print $2}' /proc/meminfo)"
  [[ "$mem_free" =~ ^[0-9]+$ ]] || die 'RAM preflight unavailable'
  (( mem_free >= 1258291 )) || die 'Need >=1.2GiB free RAM before canary'
fi

if [[ "$MODE" == "--preflight" ]]; then
  echo 'PASS: audited checkout, Docker and resource preflight'
  echo 'No containers built, modified or started'
  exit 0
fi

if [[ "$MODE" == "--verify-canary" ]]; then
  docker container inspect "$NAME" >/dev/null 2>&1 || die 'Canary not installed'
  [[ "$(docker inspect -f '{{.State.Running}}' "$NAME")" == "true" ]] || die 'Not running'
  [[ "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$NAME")" == "none" ]] || die 'Network isolation missing'
  [[ "$(docker inspect -f '{{.HostConfig.Memory}}' "$NAME")" == "805306368" ]] || die '768MiB cap missing'
  docker exec "$NAME" test -S /run/malak-ocr/ocr-v5.sock || die 'Private OCR socket unavailable'
  echo 'PASS: isolated Huawei v5 worker; no app endpoint or legal verification'
  exit 0
fi

# Do not stop, replace, modify or restart ANY existing service.
if docker container inspect "$NAME" >/dev/null 2>&1; then
  die 'Canary exists; never replace automatically'
fi
docker build --pull --file "$DOCKERFILE" --tag "$IMAGE" .
probe="$(docker run --rm --network none --read-only \
  --user 10001:10001 --pids-limit 64 --cpus 1 \
  --memory 768m --memory-swap 768m --cap-drop ALL \
  --security-opt no-new-privileges \
  --tmpfs /work:rw,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=33554432 \
  "$IMAGE" --self-test)"
[[ "$probe" == '{"ok":true,"engine":"arabic-v5","modelReady":true}' ]] ||
  die 'Public-model-only self-test failed'

docker run -d --name "$NAME" --network none --read-only \
  --user 10001:10001 --pids-limit 64 --cpus 1 \
  --memory 768m --memory-swap 768m --cap-drop ALL \
  --security-opt no-new-privileges --restart no \
  --tmpfs /work:rw,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=33554432 \
  --tmpfs /run/malak-ocr:rw,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=1048576 \
  "$IMAGE" --serve >/dev/null
ready=0
for _ in $(seq 1 30); do
  if docker exec "$NAME" test -S /run/malak-ocr/ocr-v5.sock 2>/dev/null; then
    ready=1; break
  fi
  sleep 1
done
[[ "$ready" -eq 1 ]] || die 'Canary socket did not become ready'
echo 'PASS: Huawei v5 warm OCR canary (private only, not exposed to Android)'
echo 'Next: separately reviewed owner-authenticated TLS gateway'
