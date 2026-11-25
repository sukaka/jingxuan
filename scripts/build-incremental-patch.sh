#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${CRM_PATCH_VERSION:-2.0.3}"
PLATFORM="${CRM_TARGET_PLATFORM:-linux/amd64}"
VERIFY=true

usage() {
  cat >&2 <<'EOF'
用法：./scripts/build-incremental-patch.sh [--skip-tests]

功能：
  一次构建共用同一套应用层 Docker 镜像，同时产出两份离线增量更新包：
    - release/whale-crm-patch-<VERSION>-openeuler-<PLATFORM>.tar.gz (+ .sha256)
    - release/whale-crm-patch-<VERSION>-windows-<ARCH>.zip (+ .sha256)
  仅包含 4 个应用层镜像（crm-api / web / ai-worker / clamav-proxy），不包含 6 个第三方中间件镜像。

环境变量：
  CRM_PATCH_VERSION    增量更新版本号（默认 2.0.3）
  CRM_TARGET_PLATFORM  容器目标平台，仅支持 linux/amd64 或 linux/arm64（默认 linux/amd64）
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      VERIFY=false
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "错误：未知参数：$1" >&2
      usage
      exit 2
      ;;
  esac
done

[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "错误：版本号格式不正确：$VERSION" >&2; exit 2; }
[[ "$PLATFORM" == linux/amd64 || "$PLATFORM" == linux/arm64 ]] || { echo "错误：目标平台仅支持 linux/amd64 或 linux/arm64：$PLATFORM" >&2; exit 2; }

for tool in docker python3 tar gzip npm; do
  command -v "$tool" >/dev/null 2>&1 || { echo "错误：缺少必需命令：$tool" >&2; exit 1; }
done

ARCH="${PLATFORM#linux/}"
PLATFORM_SLUG="${PLATFORM//\//-}"
LINUX_NAME="whale-crm-patch-${VERSION}-openeuler-${PLATFORM_SLUG}"
WIN_NAME="whale-crm-patch-${VERSION}-windows-${ARCH}"

OUTPUT="$ROOT/release"
BUILD_ROOT="$OUTPUT/build/patch-${VERSION}-${PLATFORM_SLUG}"
LINUX_STAGE="$BUILD_ROOT/$LINUX_NAME"
WIN_STAGE="$BUILD_ROOT/$WIN_NAME"

LINUX_TAR="$OUTPUT/${LINUX_NAME}.tar.gz"
LINUX_SHA="$OUTPUT/${LINUX_NAME}.tar.gz.sha256"
WIN_ZIP="$OUTPUT/${WIN_NAME}.zip"
WIN_SHA="$OUTPUT/${WIN_NAME}.zip.sha256"

APPLY_SH="$ROOT/infra/production/apply-patch.sh"
WIN_APPLY_BAT="$ROOT/infra/production/windows/apply-patch.bat"
WIN_APPLY_PS1="$ROOT/infra/production/windows/apply-patch.ps1"

cleanup() {
  if [[ -n "${BUILD_ROOT:-}" && -d "$BUILD_ROOT" ]]; then
    rm -rf "$BUILD_ROOT"
  fi
}
trap cleanup EXIT

# 1. 打包前快速校验脚本存在性、语法与编码
for req in "$APPLY_SH" "$WIN_APPLY_BAT" "$WIN_APPLY_PS1"; do
  [[ -f "$req" ]] || { echo "错误：缺少增量升级脚本：$req" >&2; exit 1; }
done
bash -n "$APPLY_SH"

python3 - "$WIN_APPLY_PS1" "$WIN_APPLY_BAT" <<'PY'
from pathlib import Path
import sys

ps1_path, bat_path = Path(sys.argv[1]), Path(sys.argv[2])

ps1_bytes = ps1_path.read_bytes()
if not ps1_bytes.startswith(b"\xef\xbb\xbf"):
    raise SystemExit("错误：apply-patch.ps1 必须以 UTF-8 BOM (ef bb bf) 开头以兼容 Windows PowerShell 5.1")
try:
    ps1_bytes.decode("utf-8-sig")
except UnicodeDecodeError as exc:
    raise SystemExit(f"错误：apply-patch.ps1 不是有效的 UTF-8 编码：{exc}") from exc
if b"\r\n" not in ps1_bytes or b"\n" in ps1_bytes.replace(b"\r\n", b""):
    raise SystemExit("错误：apply-patch.ps1 必须使用 CRLF (\\r\\n) 行尾")

bat_bytes = bat_path.read_bytes()
if bat_bytes.startswith(b"\xef\xbb\xbf"):
    raise SystemExit("错误：apply-patch.bat 不得包含 UTF-8 BOM")
if any(b >= 128 for b in bat_bytes):
    raise SystemExit("错误：apply-patch.bat 必须为纯 ASCII 以避免 cmd.exe 乱码")
if b"\r\n" not in bat_bytes or b"\n" in bat_bytes.replace(b"\r\n", b""):
    raise SystemExit("错误：apply-patch.bat 必须使用 CRLF (\\r\\n) 行尾")
PY

if [[ "$VERIFY" == true ]]; then
  ./scripts/verify.sh
  python3 -m unittest discover -s infra/production/tests -p 'test_*.py' -v
else
  echo "明确跳过测试；仅在已单独完成验证时使用。"
fi

# 2. 一次性构建后端 JAR、前端 dist 与 4 个应用层 Docker 镜像（绝不重复 build，不包含 6 个第三方中间件镜像）
./mvnw -B -pl apps/crm-api -am -DskipTests package
npm --prefix apps/crm-web ci
npm --prefix apps/crm-web run build

APP_IMAGES=(
  "whale-crm/crm-api:${VERSION}"
  "whale-crm/web:${VERSION}"
  "whale-crm/ai-worker:${VERSION}"
  "whale-crm/clamav-proxy:${VERSION}"
)

for image in "${APP_IMAGES[@]}"; do
  case "$image" in
    */crm-api:*)
      docker build --platform "$PLATFORM" \
        --build-arg JAR_FILE=apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar \
        -t "$image" -f apps/crm-api/Dockerfile "$ROOT"
      ;;
    */web:*)
      docker build --platform "$PLATFORM" -t "$image" -f apps/crm-web/Dockerfile "$ROOT"
      ;;
    */ai-worker:*)
      docker build --platform "$PLATFORM" -t "$image" -f apps/ai-worker/Dockerfile "$ROOT"
      ;;
    */clamav-proxy:*)
      docker build --platform "$PLATFORM" -t "$image" -f infra/production/clamav-proxy/Dockerfile infra/production/clamav-proxy
      ;;
  esac
done

# 3. 导出并严格校验唯一一份应用镜像归档（仅含 4 个目标架构应用镜像）
rm -rf "$BUILD_ROOT"
mkdir -p "$LINUX_STAGE/images" "$WIN_STAGE/images" "$OUTPUT"

SHARED_ARCHIVE="$BUILD_ROOT/application.tar.gz"
SAVE_PLATFORM_ARGS=()
if docker save --help 2>&1 | grep -q -- '--platform'; then
  SAVE_PLATFORM_ARGS=(--platform "$PLATFORM")
fi

echo "导出应用层离线镜像（仅 4 个应用镜像，目标平台 ${PLATFORM}）..."
docker save "${SAVE_PLATFORM_ARGS[@]}" "${APP_IMAGES[@]}" | gzip -1 > "$SHARED_ARCHIVE"

python3 - "$SHARED_ARCHIVE" "$PLATFORM" "${APP_IMAGES[@]}" <<'PY'
import json
import sys
import tarfile

archive_path = sys.argv[1]
expected_platform = sys.argv[2]
expected_refs = sys.argv[3:]
forbidden_prefixes = ("mysql:", "redis:", "apache/kafka:", "minio/minio:", "clamav/clamav:", "caddy:")


def norm_ref(ref: str) -> str:
    for prefix in ("docker.io/library/", "docker.io/"):
        if ref.startswith(prefix):
            return ref[len(prefix):]
    return ref


with tarfile.open(archive_path, "r:gz") as tf:
    manifest_member = tf.extractfile("manifest.json")
    if manifest_member is None:
        raise SystemExit(f"错误：{archive_path} 缺少 manifest.json")
    manifest = json.loads(manifest_member.read())
    if not manifest:
        raise SystemExit(f"错误：{archive_path} 中 manifest.json 为空")
    if len(manifest) != len(expected_refs):
        raise SystemExit(
            f"错误：{archive_path} 镜像数量为 {len(manifest)}，预期仅包含 {len(expected_refs)} 个应用镜像"
        )

    found_tags: set[str] = set()
    for entry in manifest:
        cfg_member = tf.extractfile(entry["Config"])
        if cfg_member is None:
            raise SystemExit(f"错误：{archive_path} 缺少镜像配置 {entry['Config']}")
        cfg = json.loads(cfg_member.read())
        actual = f"{cfg.get('os')}/{cfg.get('architecture')}"
        if actual != expected_platform:
            raise SystemExit(
                f"错误：{archive_path} 中镜像 {entry.get('RepoTags')} 架构为 {actual}，预期 {expected_platform}"
            )
        for tag in entry.get("RepoTags") or []:
            normalized = norm_ref(tag)
            if normalized.startswith(forbidden_prefixes):
                raise SystemExit(f"错误：增量包不得包含第三方中间件镜像：{normalized}")
            found_tags.add(normalized)

    missing = [ref for ref in expected_refs if norm_ref(ref) not in found_tags]
    if missing:
        raise SystemExit(
            f"错误：{archive_path} 缺少预期应用镜像标签 {missing}（实际包含：{sorted(found_tags)}）"
        )
    extra = sorted(found_tags - {norm_ref(ref) for ref in expected_refs})
    if extra:
        raise SystemExit(f"错误：{archive_path} 包含非预期镜像标签 {extra}")
PY

GIT_COMMIT="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
BUILD_TIME_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

write_patch_readme() {
  local dest="$1"
  local os_kind="$2"
  if [[ "$os_kind" == "linux" ]]; then
    cat > "$dest" <<EOF
# 鲸选美学 CRM 离线增量更新包（${VERSION} · openEuler / Linux ${ARCH}）

## 1. 增量更新包内容
- 本包**仅包含 4 个应用层 \`${PLATFORM}\` 镜像**（\`crm-api\`、\`web\`、\`ai-worker\`、\`clamav-proxy\`），位于 \`images/application.tar.gz\`。
- **不包含** MySQL、Redis、Kafka、MinIO、ClamAV、Caddy 等第三方中间件镜像，升级过程**不会重启中间件容器**。
- 升级仅原地更新现有安装目录 \`.env\` 中的 \`RELEASE_VERSION=${VERSION}\`，**绝不覆盖**既有密钥、端口、HTTPS 证书或业务数据卷。

## 2. 校验与解压
\`\`\`bash
sha256sum -c ${LINUX_NAME}.tar.gz.sha256
tar -xzf ${LINUX_NAME}.tar.gz
cd ${LINUX_NAME}
\`\`\`

## 3. 一键增量升级
\`\`\`bash
# 传入现有安装目录（含 .env 与 compose.yml）
./apply-patch.sh /path/to/现有安装目录

# 如需加密备份 .env（推荐）
BACKUP_ENCRYPTION_PASSPHRASE='你的备份口令' ./apply-patch.sh /path/to/现有安装目录

# 如已手动完成备份，可跳过升级前自动备份
./apply-patch.sh --skip-backup /path/to/现有安装目录
\`\`\`

## 4. 一键回滚
升级前脚本会自动将原版本号写入 \`<现有安装目录>/runtime/last-release-version\`。只要本地保留旧版 4 个应用镜像，即可一键回滚：
\`\`\`bash
./apply-patch.sh --rollback /path/to/现有安装目录
# 或显式指定回滚版本号：
./apply-patch.sh --rollback --target /path/to/现有安装目录 --to-version 2.0.2
\`\`\`
> 提示：若升级版本包含数据库结构迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库结构回滚（2.0.3 本身无新增 Flyway SQL 迁移）。
EOF
  else
    cat > "$dest" <<EOF
# 鲸选美学 CRM 离线增量更新包（${VERSION} · Windows Docker Desktop ${ARCH}）

## 1. 增量更新包内容
- 本包**仅包含 4 个应用层 \`${PLATFORM}\` 镜像**（\`crm-api\`、\`web\`、\`ai-worker\`、\`clamav-proxy\`），位于 \`images\\application.tar.gz\`。
- **不包含** MySQL、Redis、Kafka、MinIO、ClamAV、Caddy 等第三方中间件镜像，升级过程**不会重启中间件容器**。
- 升级仅原地更新现有安装目录 \`.env\` 中的 \`RELEASE_VERSION=${VERSION}\`，**绝不覆盖**既有密钥、端口、HTTPS 证书或业务数据卷。

## 2. 校验与解压
在 PowerShell 中校验 SHA-256 并解压到短路径本地目录（例如 \`C:\\whale-crm-patch\`）：
\`\`\`powershell
Get-FileHash ${WIN_NAME}.zip -Algorithm SHA256
Get-Content ${WIN_NAME}.zip.sha256
Expand-Archive -Path .\\${WIN_NAME}.zip -DestinationPath C:\\whale-crm-patch
\`\`\`

## 3. 一键增量升级
进入解压后的 \`${WIN_NAME}\` 目录，选择以下任意方式：
\`\`\`powershell
# 方式 A：双击 apply-patch.bat 按提示输入现有安装目录，或在命令行传入目录
apply-patch.bat C:\\whale-crm\\whale-crm-2.0.2-windows-amd64

# 方式 B：PowerShell 直接执行
.\\apply-patch.ps1 C:\\whale-crm\\whale-crm-2.0.2-windows-amd64

# 如已手动备份，可跳过自动备份
.\\apply-patch.ps1 -TargetDir C:\\whale-crm\\whale-crm-2.0.2-windows-amd64 -SkipBackup
\`\`\`

## 4. 一键回滚
升级前脚本会自动将原版本号写入 \`<现有安装目录>\\runtime\\last-release-version\`。只要本地保留旧版 4 个应用镜像，即可一键回滚：
\`\`\`powershell
apply-patch.bat -Rollback C:\\whale-crm\\whale-crm-2.0.2-windows-amd64
# 或显式指定回滚版本号：
.\\apply-patch.ps1 -Rollback -TargetDir C:\\whale-crm\\whale-crm-2.0.2-windows-amd64 -ToVersion 2.0.2
\`\`\`
> 提示：若升级版本包含数据库结构迁移，Flyway 迁移仅向前执行，应用回滚不等同于数据库结构回滚（2.0.3 本身无新增 Flyway SQL 迁移）。
EOF
  fi
}

# 4. 组装 openEuler/Linux 增量更新包
cp "$APPLY_SH" "$LINUX_STAGE/apply-patch.sh"
chmod 755 "$LINUX_STAGE/apply-patch.sh"
bash -n "$LINUX_STAGE/apply-patch.sh"
printf '%s\n' "${APP_IMAGES[@]}" > "$LINUX_STAGE/images/images.txt"
ln "$SHARED_ARCHIVE" "$LINUX_STAGE/images/application.tar.gz" 2>/dev/null || cp "$SHARED_ARCHIVE" "$LINUX_STAGE/images/application.tar.gz"
write_patch_readme "$LINUX_STAGE/PATCH_README.md" linux
cat > "$LINUX_STAGE/manifest.txt" <<EOF
PATCH_VERSION=$VERSION
RELEASE_VERSION=$VERSION
TARGET_PLATFORM=$PLATFORM
TARGET_OS=linux
GIT_COMMIT=$GIT_COMMIT
BUILD_TIME_UTC=$BUILD_TIME_UTC
INSTALL_MODE=incremental-patch
EOF

# 5. 组装 Windows 增量更新包（复用同一份 application.tar.gz）
cp "$WIN_APPLY_BAT" "$WIN_STAGE/apply-patch.bat"
cp "$WIN_APPLY_PS1" "$WIN_STAGE/apply-patch.ps1"
printf '%s\n' "${APP_IMAGES[@]}" > "$WIN_STAGE/images/images.txt"
ln "$SHARED_ARCHIVE" "$WIN_STAGE/images/application.tar.gz" 2>/dev/null || cp "$SHARED_ARCHIVE" "$WIN_STAGE/images/application.tar.gz"
write_patch_readme "$WIN_STAGE/PATCH_README.md" windows
cat > "$WIN_STAGE/manifest.txt" <<EOF
PATCH_VERSION=$VERSION
RELEASE_VERSION=$VERSION
TARGET_PLATFORM=$PLATFORM
TARGET_OS=windows
GIT_COMMIT=$GIT_COMMIT
BUILD_TIME_UTC=$BUILD_TIME_UTC
INSTALL_MODE=incremental-patch
EOF

# 6. 打包前检查暂存目录不含任何敏感文件
python3 - "$LINUX_STAGE" "$WIN_STAGE" <<'PY'
from pathlib import Path
import sys

for raw in sys.argv[1:]:
    stage = Path(raw)
    for p in stage.rglob("*"):
        if not p.is_file():
            continue
        name = p.name.lower()
        if name in (".env", "initial-admin.txt") or name.endswith((".pem", ".key", ".p12", ".jks")):
            raise SystemExit(f"错误：暂存目录包含禁止打入增量包的敏感文件：{p}")
PY

# 7. 生成 Linux tar.gz + sha256（原子落盘）
TMP_LINUX_TAR="${LINUX_TAR}.tmp"
rm -f "$TMP_LINUX_TAR"
COPYFILE_DISABLE=1 tar -czf "$TMP_LINUX_TAR" -C "$BUILD_ROOT" "$LINUX_NAME"
mv "$TMP_LINUX_TAR" "$LINUX_TAR"
if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT" && sha256sum "${LINUX_NAME}.tar.gz" > "${LINUX_NAME}.tar.gz.sha256" && sha256sum -c "${LINUX_NAME}.tar.gz.sha256")
else
  (cd "$OUTPUT" && shasum -a 256 "${LINUX_NAME}.tar.gz" > "${LINUX_NAME}.tar.gz.sha256" && shasum -a 256 -c "${LINUX_NAME}.tar.gz.sha256")
fi

# 8. 生成 Windows zip + sha256（已压缩 tar.gz 使用 ZIP_STORED，其余使用 ZIP_DEFLATED，校验 CRC）
python3 - "$WIN_STAGE" "$WIN_NAME" "$WIN_ZIP" "$WIN_SHA" <<'PY'
import hashlib
import os
from pathlib import Path
import sys
import zipfile

stage = Path(sys.argv[1])
root_name = sys.argv[2]
zip_path = Path(sys.argv[3])
sha_path = Path(sys.argv[4])
tmp_zip = zip_path.with_suffix(zip_path.suffix + ".tmp")

if tmp_zip.exists():
    tmp_zip.unlink()

all_files = sorted(p for p in stage.rglob("*") if p.is_file())
with zipfile.ZipFile(tmp_zip, mode="w", allowZip64=True) as zf:
    for file_path in all_files:
        rel = file_path.relative_to(stage)
        arcname = f"{root_name}/{rel.as_posix()}"
        if rel.as_posix().startswith("images/") and file_path.name.endswith(".tar.gz"):
            zf.write(file_path, arcname=arcname, compress_type=zipfile.ZIP_STORED)
        else:
            zf.write(file_path, arcname=arcname, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)

with zipfile.ZipFile(tmp_zip, mode="r") as zf:
    bad = zf.testzip()
    if bad is not None:
        raise SystemExit(f"错误：生成的 Windows 增量 ZIP 校验失败，损坏条目：{bad}")

os.replace(tmp_zip, zip_path)

digest = hashlib.sha256()
with open(zip_path, "rb") as f:
    for chunk in iter(lambda: f.read(4 * 1024 * 1024), b""):
        digest.update(chunk)

sha_line = f"{digest.hexdigest()}  {zip_path.name}\n"
sha_path.write_bytes(sha_line.encode("ascii"))
print(f"SHA256: {sha_line.strip()}")
PY

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT" && sha256sum -c "${WIN_NAME}.zip.sha256")
elif command -v shasum >/dev/null 2>&1; then
  (cd "$OUTPUT" && shasum -a 256 -c "${WIN_NAME}.zip.sha256")
fi

ls -lh "$LINUX_TAR" "$LINUX_SHA" "$WIN_ZIP" "$WIN_SHA"
