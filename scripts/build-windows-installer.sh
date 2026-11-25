#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

VERSION="${CRM_RELEASE_VERSION:-2.0.2}"
PLATFORM="${CRM_TARGET_PLATFORM:-linux/amd64}"
VERIFY=true
REUSE_IMAGES_FROM=""
REUSE_TMP=""

usage() {
  cat >&2 <<'EOF'
用法：./scripts/build-windows-installer.sh [--skip-tests] [--reuse-images-from <dir-or-tar.gz>]

环境变量：
  CRM_RELEASE_VERSION   发布版本号（默认 2.0.2）
  CRM_TARGET_PLATFORM   容器目标平台，仅支持 linux/amd64 或 linux/arm64（默认 linux/amd64）
  CRM_PULL_IMAGES       设为 always 时强制重新拉取第三方基础镜像（默认复用本地同架构镜像）
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-tests)
      VERIFY=false
      shift
      ;;
    --reuse-images-from)
      [[ $# -ge 2 ]] || { echo "错误：--reuse-images-from 缺少路径参数" >&2; usage; exit 2; }
      REUSE_IMAGES_FROM="$2"
      shift 2
      ;;
    --reuse-images-from=*)
      REUSE_IMAGES_FROM="${1#*=}"
      [[ -n "$REUSE_IMAGES_FROM" ]] || { echo "错误：--reuse-images-from 缺少路径参数" >&2; usage; exit 2; }
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

[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "版本号格式不正确：$VERSION" >&2; exit 2; }
[[ "$PLATFORM" == linux/amd64 || "$PLATFORM" == linux/arm64 ]] || { echo "目标平台仅支持 linux/amd64 或 linux/arm64：$PLATFORM" >&2; exit 2; }

command -v python3 >/dev/null 2>&1 || { echo "错误：未找到 python3，无法执行校验与 ZIP 打包。" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "错误：未找到 docker 命令。" >&2; exit 1; }

ARCH="${PLATFORM#linux/}"
NAME="whale-crm-${VERSION}-windows-${ARCH}"
OUTPUT="$ROOT/release"
STAGE="$OUTPUT/build/$NAME"
ZIP_PATH="$OUTPUT/${NAME}.zip"
SHA_PATH="$OUTPUT/${NAME}.zip.sha256"
WIN_SRC="$ROOT/infra/production/windows"

cleanup() {
  if [[ -n "$REUSE_TMP" && -d "$REUSE_TMP" ]]; then
    rm -rf "$REUSE_TMP"
  fi
  if [[ -n "${STAGE:-}" && -d "$STAGE" ]]; then
    rm -rf "$STAGE"
  fi
}
trap cleanup EXIT

for required in install.ps1 install.bat manage.bat README.md; do
  [[ -f "$WIN_SRC/$required" ]] || { echo "错误：缺少 Windows 安装包源文件：$WIN_SRC/$required" >&2; exit 1; }
done

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

ARCHIVE_GROUPS=(
  "application|${IMAGES[*]:0:4}"
  "mysql|${IMAGES[4]}"
  "redis|${IMAGES[5]}"
  "kafka|${IMAGES[6]}"
  "minio|${IMAGES[7]}"
  "clamav|${IMAGES[8]}"
  "caddy|${IMAGES[9]}"
)

verify_image_archive() {
  local archive_path="$1"
  local expected_platform="$2"
  shift 2
  python3 - "$archive_path" "$expected_platform" "$@" <<'PY'
import json
import sys
import tarfile

archive_path = sys.argv[1]
expected_platform = sys.argv[2]
expected_refs = sys.argv[3:]


def norm_ref(ref: str) -> str:
    if ref.startswith("docker.io/library/"):
        return ref[len("docker.io/library/"):]
    if ref.startswith("docker.io/"):
        return ref[len("docker.io/"):]
    return ref


with tarfile.open(archive_path, "r:gz") as tf:
    try:
        manifest_member = tf.extractfile("manifest.json")
    except KeyError as exc:
        raise SystemExit(f"错误：{archive_path} 缺少 manifest.json") from exc
    if manifest_member is None:
        raise SystemExit(f"错误：{archive_path} 无法读取 manifest.json")
    manifest = json.loads(manifest_member.read())
    if not manifest:
        raise SystemExit(f"错误：{archive_path} 中 manifest.json 为空")

    found_tags: set[str] = set()
    for entry in manifest:
        cfg_member = tf.extractfile(entry["Config"])
        if cfg_member is None:
            raise SystemExit(f"错误：{archive_path} 缺少镜像配置文件 {entry['Config']}")
        cfg = json.loads(cfg_member.read())
        actual = f"{cfg.get('os')}/{cfg.get('architecture')}"
        if actual != expected_platform:
            raise SystemExit(
                f"错误：{archive_path} 中镜像 {entry.get('RepoTags')} 架构为 {actual}，预期 {expected_platform}"
            )
        for tag in entry.get("RepoTags") or []:
            found_tags.add(norm_ref(tag))

    if expected_refs:
        missing = [ref for ref in expected_refs if norm_ref(ref) not in found_tags]
        if missing:
            raise SystemExit(
                f"错误：{archive_path} 缺少预期镜像标签 {missing}（实际包含：{sorted(found_tags)}）"
            )
PY
}

rm -rf "$STAGE"
mkdir -p "$STAGE/images"

if [[ "$VERIFY" == true ]]; then
  ./scripts/verify.sh
else
  echo "明确跳过测试；仅在已单独完成验证时使用。"
fi

# 1. 复制 Windows 全量安装包脚本与文档（不包含增量更新脚本 apply-patch.*）
for fname in install.ps1 install.bat manage.bat README.md; do
  cp "$WIN_SRC/$fname" "$STAGE/"
done

# 2. 复制并标准化 Caddy 模板、compose.yml（移除 Linux SELinux :ro,z）与 .env.example
python3 - "$ROOT/infra/production" "$STAGE" "$VERSION" <<'PY'
from pathlib import Path
import re
import sys

prod_dir = Path(sys.argv[1])
stage_dir = Path(sys.argv[2])
version = sys.argv[3]

for caddy_name in ("Caddyfile.core", "Caddyfile.files", "Caddyfile.sameorigin"):
    cf_text = (prod_dir / caddy_name).read_text(encoding="utf-8-sig").replace("\r\n", "\n")
    (stage_dir / caddy_name).write_bytes(cf_text.encode("utf-8"))

compose_text = (prod_dir / "compose.yml").read_text(encoding="utf-8-sig").replace("\r\n", "\n")
# 仅移除仅适用于 Linux SELinux 的卷挂载标签 :ro,z / :ro,Z / :rw,z / :rw,Z
compose_norm = re.sub(r":(ro|rw),[zZ](\b|$)", r":\1\2", compose_text, flags=re.MULTILINE)
if ":ro,z" in compose_norm or ":ro,Z" in compose_norm or ":rw,z" in compose_norm or ":rw,Z" in compose_norm:
    raise SystemExit("错误：Windows compose.yml 仍残留 SELinux 挂载选项")
(stage_dir / "compose.yml").write_bytes(compose_norm.encode("utf-8"))

env_text = (prod_dir / ".env.example").read_text(encoding="utf-8-sig").replace("\r\n", "\n")
env_lines = []
replaced = False
for line in env_text.splitlines():
    if line.startswith("RELEASE_VERSION="):
        env_lines.append(f"RELEASE_VERSION={version}")
        replaced = True
    else:
        env_lines.append(line)
if not replaced:
    raise SystemExit("错误：.env.example 中未找到 RELEASE_VERSION= 配置项")
(stage_dir / ".env.example").write_bytes(("\n".join(env_lines) + "\n").encode("utf-8"))
PY

# 3. 打包前静态与编码检查（先快速失败，再执行耗时镜像构建）
python3 - "$STAGE" "$VERSION" <<'PY'
from pathlib import Path
import sys

stage = Path(sys.argv[1])
version = sys.argv[2]

for required in ("install.ps1", "install.bat", "manage.bat", "README.md", "compose.yml", ".env.example", "Caddyfile.core", "Caddyfile.files", "Caddyfile.sameorigin"):
    if not (stage / required).is_file():
        raise SystemExit(f"错误：暂存目录缺少必需文件：{required}")

ps1_bytes = (stage / "install.ps1").read_bytes()
if not ps1_bytes.startswith(b"\xef\xbb\xbf"):
    raise SystemExit("错误：install.ps1 必须以 UTF-8 BOM (ef bb bf) 开头以兼容 Windows PowerShell 5.1")
try:
    ps1_bytes.decode("utf-8-sig")
except UnicodeDecodeError as exc:
    raise SystemExit(f"错误：install.ps1 不是有效的 UTF-8 编码：{exc}") from exc

bat_files = sorted(stage.glob("*.bat"))
if not bat_files:
    raise SystemExit("错误：暂存目录未找到 .bat 启动脚本")
for bat in bat_files:
    raw = bat.read_bytes()
    if b"\n" not in raw:
        raise SystemExit(f"错误：{bat.name} 内容为空或缺少换行")
    if b"\n" in raw.replace(b"\r\n", b""):
        raise SystemExit(f"错误：{bat.name} 必须使用 CRLF (\\r\\n) 行尾")

compose_bytes = (stage / "compose.yml").read_bytes()
if compose_bytes.startswith(b"\xef\xbb\xbf"):
    raise SystemExit("错误：compose.yml 不得包含 UTF-8 BOM")
compose_text = compose_bytes.decode("utf-8")
if ":ro,z" in compose_text or ":ro,Z" in compose_text:
    raise SystemExit("错误：compose.yml 不得包含 Linux SELinux 挂载选项 :ro,z")

env_text = (stage / ".env.example").read_text(encoding="utf-8")
if f"RELEASE_VERSION={version}" not in env_text.splitlines():
    raise SystemExit(f"错误：.env.example 未正确写入 RELEASE_VERSION={version}")

for lf_file in ("compose.yml", ".env.example", "Caddyfile.core", "Caddyfile.files", "Caddyfile.sameorigin"):
    raw = (stage / lf_file).read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        raise SystemExit(f"错误：{lf_file} 不得包含 UTF-8 BOM")
    if b"\r\n" in raw:
        raise SystemExit(f"错误：{lf_file} 必须使用 LF 行尾以供 Linux 容器读取")

for forbidden in (".env", "initial-admin.txt"):
    if (stage / forbidden).exists():
        raise SystemExit(f"错误：暂存目录包含敏感运行时文件：{forbidden}")
PY

RELEASE_VERSION="$VERSION" docker compose --env-file infra/production/tests/compose-test.properties -f "$STAGE/compose.yml" config --quiet

# 4. 准备离线镜像：复用已校验镜像包，或重新构建/拉取并导出
if [[ -n "$REUSE_IMAGES_FROM" ]]; then
  echo "校验并复用已有镜像包：$REUSE_IMAGES_FROM"
  REUSE_TMP="$(mktemp -d "${TMPDIR:-/tmp}/whale-crm-win-reuse.XXXXXX")"

  python3 - "$REUSE_IMAGES_FROM" "$REUSE_TMP" "${IMAGES[@]}" <<'PY'
from pathlib import Path
import shutil
import sys
import tarfile
import zipfile

source = Path(sys.argv[1])
dest = Path(sys.argv[2])
expected_images = sys.argv[3:]
expected_archives = {
    "application.tar.gz",
    "mysql.tar.gz",
    "redis.tar.gz",
    "kafka.tar.gz",
    "minio.tar.gz",
    "clamav.tar.gz",
    "caddy.tar.gz",
}

if not source.exists():
    raise SystemExit(f"错误：--reuse-images-from 路径不存在：{source}")

images_out = dest / "images"
images_out.mkdir(parents=True, exist_ok=True)


def safe_copy_from_dir(img_dir: Path) -> None:
    img_txt = img_dir / "images.txt"
    if not img_txt.is_file():
        raise SystemExit(f"错误：目录中缺少 images.txt：{img_dir}")
    shutil.copy2(img_txt, images_out / "images.txt")
    for arch in sorted(expected_archives):
        src_file = img_dir / arch
        if not src_file.is_file():
            raise SystemExit(f"错误：目录中缺少镜像包：{src_file}")
        shutil.copy2(src_file, images_out / arch)


if source.is_dir():
    if (source / "images" / "images.txt").is_file():
        safe_copy_from_dir(source / "images")
    elif (source / "images.txt").is_file():
        safe_copy_from_dir(source)
    else:
        raise SystemExit(f"错误：目录中未找到 images/images.txt 或 images.txt：{source}")
elif source.is_file() and (source.name.endswith(".tar.gz") or source.name.endswith(".tgz")):
    needed = expected_archives | {"images.txt"}
    extracted: set[str] = set()
    with tarfile.open(source, "r:gz") as tf:
        for member in tf.getmembers():
            if not member.isfile():
                continue
            parts = Path(member.name).parts
            if any(p in ("..", "") or p.startswith("/") for p in parts):
                raise SystemExit(f"错误：归档包含非法路径：{member.name}")
            if len(parts) >= 2 and parts[-2] == "images" and parts[-1] in needed:
                fname = parts[-1]
                if fname in extracted:
                    raise SystemExit(f"错误：归档中存在重复的 images/{fname}")
                fobj = tf.extractfile(member)
                if fobj is None:
                    raise SystemExit(f"错误：无法提取归档成员：{member.name}")
                with open(images_out / fname, "wb") as out:
                    shutil.copyfileobj(fobj, out, length=1024 * 1024)
                extracted.add(fname)
    missing = sorted(needed - extracted)
    if missing:
        raise SystemExit(f"错误：tar.gz 中缺少预期镜像文件：{missing}")
elif source.is_file() and source.name.endswith(".zip"):
    needed = expected_archives | {"images.txt"}
    extracted = set()
    with zipfile.ZipFile(source, "r") as zf:
        for info in zf.infolist():
            if info.is_dir():
                continue
            parts = Path(info.filename).parts
            if any(p in ("..", "") or p.startswith("/") for p in parts):
                raise SystemExit(f"错误：ZIP 包含非法路径：{info.filename}")
            if len(parts) >= 2 and parts[-2] == "images" and parts[-1] in needed:
                fname = parts[-1]
                if fname in extracted:
                    raise SystemExit(f"错误：ZIP 中存在重复的 images/{fname}")
                with zf.open(info, "r") as src_f, open(images_out / fname, "wb") as out:
                    shutil.copyfileobj(src_f, out, length=1024 * 1024)
                extracted.add(fname)
    missing = sorted(needed - extracted)
    if missing:
        raise SystemExit(f"错误：ZIP 中缺少预期镜像文件：{missing}")
else:
    raise SystemExit(f"错误：--reuse-images-from 仅支持目录、.tar.gz/.tgz 或 .zip：{source}")

actual_images = [
    ln.strip()
    for ln in (images_out / "images.txt").read_text(encoding="utf-8-sig").splitlines()
    if ln.strip()
]
if actual_images != expected_images:
    raise SystemExit(
        "错误：复用源 images.txt 与当前版本/镜像清单不匹配！\n"
        f"预期：{expected_images}\n"
        f"实际：{actual_images}"
    )
PY

  printf '%s\n' "${IMAGES[@]}" > "$STAGE/images/images.txt"
  for group in "${ARCHIVE_GROUPS[@]}"; do
    group_name="${group%%|*}"
    read -r -a group_images <<< "${group#*|}"
    src_archive="$REUSE_TMP/images/${group_name}.tar.gz"
    dst_archive="$STAGE/images/${group_name}.tar.gz"
    echo "校验复用镜像包：${group_name}.tar.gz (${PLATFORM})"
    verify_image_archive "$src_archive" "$PLATFORM" "${group_images[@]}"
    mv "$src_archive" "$dst_archive"
  done
else
  ./mvnw -B -pl apps/crm-api -am -DskipTests package
  npm --prefix apps/crm-web ci
  npm --prefix apps/crm-web run build

  for image in "${IMAGES[@]:0:4}"; do
    case "$image" in
      */crm-api:*) docker build --platform "$PLATFORM" --build-arg JAR_FILE=apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar -t "$image" -f apps/crm-api/Dockerfile "$ROOT" ;;
      */web:*) docker build --platform "$PLATFORM" -t "$image" -f apps/crm-web/Dockerfile "$ROOT" ;;
      */ai-worker:*) docker build --platform "$PLATFORM" -t "$image" -f apps/ai-worker/Dockerfile "$ROOT" ;;
      */clamav-proxy:*) docker build --platform "$PLATFORM" -t "$image" -f infra/production/clamav-proxy/Dockerfile infra/production/clamav-proxy ;;
    esac
  done

  for image in "${IMAGES[@]:4}"; do
    local_arch="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image" 2>/dev/null || true)"
    if [[ "${CRM_PULL_IMAGES:-auto}" != "always" && "$local_arch" == "$PLATFORM" ]]; then
      echo "复用本地同架构镜像：$image ($local_arch)"
    else
      docker pull --platform "$PLATFORM" "$image"
    fi
  done

  printf '%s\n' "${IMAGES[@]}" > "$STAGE/images/images.txt"
  SAVE_PLATFORM_ARGS=()
  if docker save --help 2>&1 | grep -q -- '--platform'; then
    SAVE_PLATFORM_ARGS=(--platform "$PLATFORM")
  fi
  for group in "${ARCHIVE_GROUPS[@]}"; do
    group_name="${group%%|*}"
    read -r -a group_images <<< "${group#*|}"
    echo "打包镜像：${group_name} (${PLATFORM})"
    archive_path="$STAGE/images/${group_name}.tar.gz"
    docker save "${SAVE_PLATFORM_ARGS[@]}" "${group_images[@]}" | gzip -1 > "$archive_path"
    verify_image_archive "$archive_path" "$PLATFORM" "${group_images[@]}"
  done
fi

# 5. 写入 manifest.txt（显式标记 TARGET_OS=windows）
cat > "$STAGE/manifest.txt" <<EOF
RELEASE_VERSION=$VERSION
TARGET_PLATFORM=$PLATFORM
TARGET_OS=windows
BUILD_TIME_UTC=$(date -u +%Y-%m-%dT%H:%M:%SZ)
INSTALL_MODE=offline-images
EOF

# 6. 使用 Python zipfile 生成 Windows 友好 ZIP（已压缩的 images/*.tar.gz 使用 ZIP_STORED，文本使用 ZIP_DEFLATED）
mkdir -p "$OUTPUT"
python3 - "$STAGE" "$NAME" "$ZIP_PATH" "$SHA_PATH" <<'PY'
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
        raise SystemExit(f"错误：生成的 ZIP 校验失败，损坏条目：{bad}")

os.replace(tmp_zip, zip_path)

digest = hashlib.sha256()
with open(zip_path, "rb") as f:
    for chunk in iter(lambda: f.read(4 * 1024 * 1024), b""):
        digest.update(chunk)

sha_line = f"{digest.hexdigest()}  {zip_path.name}\n"
sha_path.write_bytes(sha_line.encode("ascii"))
print(f"SHA256: {sha_line.strip()}")
PY

rm -rf "$STAGE"

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT" && sha256sum -c "${NAME}.zip.sha256")
elif command -v shasum >/dev/null 2>&1; then
  (cd "$OUTPUT" && shasum -a 256 -c "${NAME}.zip.sha256")
fi

ls -lh "$ZIP_PATH" "$SHA_PATH"
