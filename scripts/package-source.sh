#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
VERSION="${CRM_SOURCE_VERSION:-2.0.0}"
[[ "$VERSION" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "源码包版本格式不正确" >&2; exit 2; }
NAME="whale-crm-source-${VERSION}"
OUTPUT="$ROOT/release"
STAGE="$OUTPUT/source-build/$NAME"
ARCHIVE="$OUTPUT/${NAME}.tar.gz"

rm -rf "$STAGE"
mkdir -p "$STAGE"

tar -cf - \
  --exclude='.git' \
  --exclude='*/.git' \
  --exclude='node_modules' \
  --exclude='*/node_modules' \
  --exclude='target' \
  --exclude='*/target' \
  --exclude='dist' \
  --exclude='*/dist' \
  --exclude='.venv' \
  --exclude='*/.venv' \
  --exclude='__pycache__' \
  --exclude='*/__pycache__' \
  --exclude='.pytest_cache' \
  --exclude='*/.pytest_cache' \
  --exclude='.env' \
  --exclude='*/.env' \
  --exclude='.env.*' \
  --exclude='*/.env.*' \
  --exclude='*.log' \
  --exclude='*.pyc' \
  --exclude='*.jar' \
  --exclude='*.class' \
  --exclude='*.pem' \
  --exclude='*.key' \
  --exclude='*id_rsa*' \
  --exclude='.DS_Store' \
  --exclude='*/.DS_Store' \
  --exclude='._*' \
  --exclude='*/._*' \
  --exclude='__MACOSX' \
  --exclude='*/__MACOSX' \
  --exclude='infra/production/runtime' \
  --exclude='infra/production/backups' \
  --exclude='infra/production/initial-admin.txt' \
  --exclude='docs/08_原型与来源' \
  apps contracts infra scripts config docs .github .mvn \
  .dockerignore .gitignore AGENTS.md CHANGELOG.md README.md pom.xml mvnw mvnw.cmd gitleaks.toml \
  | tar -xf - -C "$STAGE"

mkdir -p "$STAGE/infra/compose" "$STAGE/infra/production"
cp infra/compose/.env.example "$STAGE/infra/compose/.env.example"
cp infra/production/.env.example "$STAGE/infra/production/.env.example"

cat > "$STAGE/SOURCE_PACKAGE.md" <<'EOF'
# 鲸选美学 CRM 精简源码包

这是用于继续研发的源码快照，不是服务器安装包。应用源码、测试源码、数据库迁移、接口契约、部署脚本和开发文档均保留；原型来源目录未纳入。

未包含依赖、编译输出、前端构建产物、容器镜像、运行数据、日志、真实 `.env` 配置、凭据文件或生产密钥。公开 `.env.example` 仅作配置模板，不能用于生产。

## 常用校验

```bash
./mvnw -pl apps/crm-api -am verify
npm --prefix apps/crm-web ci
npm --prefix apps/crm-web run typecheck
npm --prefix apps/crm-web run lint
npm --prefix apps/crm-web test
python3 -m venv apps/ai-worker/.venv
apps/ai-worker/.venv/bin/pip install -r apps/ai-worker/requirements.txt
apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests
```

服务器离线安装包由 `./scripts/build-installer.sh` 另行构建，构建默认先执行全栈验证。
EOF

tar -czf "$ARCHIVE" -C "$OUTPUT/source-build" "$NAME"
python3 - "$ARCHIVE" <<'PY'
import pathlib
import sys
import tarfile

archive = pathlib.Path(sys.argv[1])
required = {
    'pom.xml', 'mvnw', 'apps/crm-api/pom.xml', 'apps/crm-web/package-lock.json',
    'apps/ai-worker/requirements.txt', 'apps/crm-api/src/main/resources/application-prod.yml',
    'infra/production/install.sh', 'infra/production/.env.example',
    'scripts/build-installer.sh', 'scripts/package-source.sh', 'gitleaks.toml', 'docs/AGENTS.md',
}
forbidden_directories = {'.git', 'node_modules', 'target', 'dist', '.venv', '__pycache__', '.pytest_cache', 'tmp', 'release', 'runtime', 'backups'}
count = 0
with tarfile.open(archive, 'r:gz') as package:
    names = set()
    for member in package:
        if not member.isfile():
            continue
        count += 1
        path = pathlib.PurePosixPath(member.name)
        relative = pathlib.PurePosixPath(*path.parts[1:])
        names.add(str(relative))
        if any(part in forbidden_directories for part in relative.parts):
            raise SystemExit(f'归档包含排除目录：{relative}')
        if relative.name != '.env.example' and (relative.name.startswith('.env') or relative.name == 'initial-admin.txt'):
            raise SystemExit(f'归档包含敏感配置：{relative}')
        if relative.suffix in {'.jar', '.class', '.pyc', '.pem', '.key', '.log'} or 'id_rsa' in relative.name:
            raise SystemExit(f'归档包含构建产物或密钥：{relative}')
    missing = sorted(required - names)
    if missing:
        raise SystemExit(f'归档缺少必要源码：{missing}')
print(f'源码归档检查通过：{count} 个文件，未包含依赖、构建产物或真实配置密钥。')
PY

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$OUTPUT" && sha256sum "${NAME}.tar.gz" > "${NAME}.tar.gz.sha256")
else
  (cd "$OUTPUT" && shasum -a 256 "${NAME}.tar.gz" > "${NAME}.tar.gz.sha256")
fi
ls -lh "$ARCHIVE" "$ARCHIVE.sha256"
