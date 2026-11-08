#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
VERSION="${CRM_RELEASE_VERSION:-2.0.0}"
PLATFORM="${CRM_TARGET_PLATFORM:-linux/amd64}"
VERIFY=true
if [[ "${1:-}" == "--skip-tests" ]]; then
  VERIFY=false
elif [[ $# -gt 0 ]]; then
  echo "用法：./scripts/build-installer.sh [--skip-tests]" >&2
  exit 2
fi
[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "版本号格式不正确" >&2; exit 2; }
[[ "$PLATFORM" == linux/amd64 || "$PLATFORM" == linux/arm64 ]] || { echo "目标平台仅支持 linux/amd64 或 linux/arm64" >&2; exit 2; }

NAME="whale-crm-${VERSION}-${PLATFORM//\//-}"
OUTPUT="$ROOT/release"
STAGE="$OUTPUT/build/$NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE/images"

if [[ "$VERIFY" == true ]]; then
  ./scripts/verify.sh
else
  echo "明确跳过测试；仅在已单独完成验证时使用。"
fi

./mvnw -B -pl apps/crm-api -am -DskipTests package
npm --prefix apps/crm-web ci
npm --prefix apps/crm-web run build

cp infra/production/compose.yml infra/production/install.sh infra/production/README.md "$STAGE/"
cp infra/production/.env.example infra/production/Caddyfile.core infra/production/Caddyfile.files "$STAGE/"
sed -i.bak "s|^RELEASE_VERSION=.*|RELEASE_VERSION=${VERSION}|" "$STAGE/.env.example"
rm -f "$STAGE/.env.example.bak"
chmod 755 "$STAGE/install.sh"
bash -n "$STAGE/install.sh"
RELEASE_VERSION="$VERSION" docker compose --env-file infra/production/tests/compose-test.properties -f "$STAGE/compose.yml" config --quiet

IMAGES=(
  "whale-crm/crm-api:${VERSION}"
  "whale-crm/web:${VERSION}"
  "whale-crm/ai-worker:${VERSION}"
  "whale-crm/clamav-proxy:${VERSION}"
  "mysql:8.4.8"
  "redis:7.4.2-alpine"
  "apache/kafka:4.1.2"
  "minio/minio:RELEASE.2025-09-07T16-13-09Z"
  "clamav/clamav:1.4.3"
  "caddy:2.11.4-alpine"
)

for image in "${IMAGES[@]:0:4}"; do
  case "$image" in
    */crm-api:*) docker build --platform "$PLATFORM" --build-arg JAR_FILE=apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar -t "$image" -f apps/crm-api/Dockerfile "$ROOT" ;;
    */web:*) docker build --platform "$PLATFORM" -t "$image" -f apps/crm-web/Dockerfile "$ROOT" ;;
    */ai-worker:*) docker build --platform "$PLATFORM" -t "$image" -f apps/ai-worker/Dockerfile "$ROOT" ;;
    */clamav-proxy:*) docker build --platform "$PLATFORM" -t "$image" -f infra/production/clamav-proxy/Dockerfile infra/production/clamav-proxy ;;
  esac
done

for image in "${IMAGES[@]:4}"; do
  docker pull --platform "$PLATFORM" "$image"
done

printf '%s\n' "${IMAGES[@]}" > "$STAGE/images/images.txt"
ARCHIVE_GROUPS=(
  "application|${IMAGES[*]:0:4}"
  "mysql|${IMAGES[4]}"
  "redis|${IMAGES[5]}"
  "kafka|${IMAGES[6]}"
  "minio|${IMAGES[7]}"
  "clamav|${IMAGES[8]}"
  "caddy|${IMAGES[9]}"
)
for group in "${ARCHIVE_GROUPS[@]}"; do
  read -r -a images <<< "${group#*|}"
  echo "打包镜像：${group%%|*}"
  docker save "${images[@]}" | gzip -1 > "$STAGE/images/${group%%|*}.tar.gz"
done

cat > "$STAGE/manifest.txt" <<EOF
RELEASE_VERSION=$VERSION
TARGET_PLATFORM=$PLATFORM
BUILD_TIME_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_MODE=offline-images
EOF

tar -czf "$OUTPUT/${NAME}.tar.gz" -C "$OUTPUT/build" "$NAME"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT" && sha256sum "${NAME}.tar.gz" > "${NAME}.tar.gz.sha256")
else
  (cd "$OUTPUT" && shasum -a 256 "${NAME}.tar.gz" > "${NAME}.tar.gz.sha256")
fi
ls -lh "$OUTPUT/${NAME}.tar.gz" "$OUTPUT/${NAME}.tar.gz.sha256"
