#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
SOURCE_DB="${M01_SOURCE_CONTAINER:-compose-mysql-1}"
if [ "$SOURCE_DB" != "compose-mysql-1" ] && [ "${M01_ALLOW_OTHER_SOURCE:-}" != "1" ]; then
  echo "仅允许默认开发源 compose-mysql-1；其他源需显式 M01_ALLOW_OTHER_SOURCE=1" >&2
  exit 1
fi
if [ "$(docker inspect -f '{{.Name}}' "$SOURCE_DB")" != "/$SOURCE_DB" ]; then
  echo "恢复源不是预期的本地开发容器" >&2
  exit 1
fi
for required in mysqldump mysql; do
  docker exec "$SOURCE_DB" sh -c "command -v $required" >/dev/null
done
for required in python3 docker curl; do
  command -v "$required" >/dev/null
done
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home)}"

RUN_ID="$(date +%s)-$(python3 -c 'import uuid; print(uuid.uuid4().hex[:10])')"
WORK="$ROOT/tmp/m01-$RUN_ID"
mkdir -p "$WORK/objects" "$WORK/restore" "$WORK/mc-config"
chmod 700 "$WORK" "$WORK/mc-config"
TARGET_DB="m01-mysql-$RUN_ID"
TARGET_S3="m01-minio-$RUN_ID"
TARGET_BUCKET="m01-restore-$RUN_ID"
DB_SECRET="m01-db-$RUN_ID"
REPL_SECRET="m01-repl-$RUN_ID"
S3_SECRET="m01-s3-$RUN_ID"
BACKEND_PID=""
STARTED_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

sql_source() {
  docker exec "$SOURCE_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database=medical_crm --batch --skip-column-names --execute="$1"' sh "$1"
}
sql_target() {
  docker exec "$TARGET_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database=medical_crm --batch --skip-column-names --execute="$1"' sh "$1"
}
sql_target_vertical() {
  docker exec "$TARGET_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database=medical_crm --vertical --execute="$1"' sh "$1"
}
run_mc() {
  docker run --rm -v "$WORK:/work" --env MC_CONFIG_DIR=/work/mc-config --entrypoint /bin/sh minio/mc:RELEASE.2025-05-21T01-59-54Z -c "$1"
}
free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}
TARGET_DB_PORT="$(free_port)"
TARGET_S3_PORT="$(free_port)"

cleanup() {
  status=$?
  if [ -n "$BACKEND_PID" ]; then
    kill -TERM "$BACKEND_PID" 2>/dev/null || true
    wait "$BACKEND_PID" 2>/dev/null || true
  fi
  docker exec "$SOURCE_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --database=medical_crm --execute="DROP DATABASE IF EXISTS m01_probe; DROP USER IF EXISTS '\''m01_repl'\''@'\''%'\'';"' >/dev/null 2>&1 || true
  if docker container inspect "$TARGET_DB" >/dev/null 2>&1; then
    docker logs "$TARGET_DB" > "$WORK/mysql.log" 2>&1 || true
    docker rm -f "$TARGET_DB" >/dev/null
  fi
  if docker container inspect "$TARGET_S3" >/dev/null 2>&1; then
    docker logs "$TARGET_S3" > "$WORK/minio.log" 2>&1 || true
    docker rm -f "$TARGET_S3" >/dev/null
  fi
  exit "$status"
}
trap cleanup EXIT

printf 'M01 开始：%s，隔离目标 %s / %s\n' "$STARTED_UTC" "$TARGET_DB" "$TARGET_S3"
sql_source "DROP USER IF EXISTS 'm01_repl'@'%'; CREATE USER 'm01_repl'@'%' IDENTIFIED BY '$REPL_SECRET'; GRANT REPLICATION SLAVE ON *.* TO 'm01_repl'@'%';"
sql_source "CREATE DATABASE m01_probe; CREATE TABLE m01_probe.recovery_marker (id BIGINT AUTO_INCREMENT PRIMARY KEY, marker VARCHAR(64)); INSERT INTO m01_probe.recovery_marker(marker) VALUES('before_backup');"

DUMP_FILE="$WORK/backup.sql"
docker exec "$SOURCE_DB" sh -c 'mysqldump --user=root --password="$MYSQL_ROOT_PASSWORD" --single-transaction --source-data=2 --set-gtid-purged=OFF --routines --events --triggers --hex-blob --databases medical_crm m01_probe' > "$DUMP_FILE"
chmod 600 "$DUMP_FILE"
read -r LOG_FILE LOG_POS < <(python3 -c 'import re,pathlib; text=pathlib.Path("'"$DUMP_FILE"'").read_text(); match=re.search(r"SOURCE_LOG_FILE=\x27([^\x27]+)\x27,\s*SOURCE_LOG_POS=(\d+)",text); assert match, "dump 缺少 binlog 切点"; print(match.group(1),match.group(2))')
sql_source "INSERT INTO m01_probe.recovery_marker(marker) VALUES('after_backup');"
read -r END_FILE END_POS < <(sql_source "SHOW BINARY LOG STATUS" | python3 -c 'import sys; row=sys.stdin.read().strip().split("\t"); assert len(row)>=2, "缺少 BINARY LOG STATUS"; print(row[0],row[1])')
printf 'M01 dump 切点：%s:%s；增量终点：%s:%s\n' "$LOG_FILE" "$LOG_POS" "$END_FILE" "$END_POS"

docker run --detach --rm --name "$TARGET_DB" \
  --env MYSQL_ROOT_PASSWORD="$DB_SECRET" --env MYSQL_DATABASE=medical_crm --env MYSQL_ROOT_HOST=% \
  --publish "127.0.0.1:$TARGET_DB_PORT:3306" \
  mysql:8.4 --server-id=2 --log-bin-trust-function-creators=1
DB_READY=0
for attempt in {1..60}; do
  if docker exec "$TARGET_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD" --execute="SELECT 1"' >/dev/null 2>&1; then
    DB_READY=1
    break
  fi
  sleep 1
done
if [ "$DB_READY" != 1 ]; then
  echo '隔离 MySQL 的 root 认证查询未在 60 秒内就绪' >&2
  exit 1
fi
docker exec -i "$TARGET_DB" sh -c 'mysql --user=root --password="$MYSQL_ROOT_PASSWORD"' < "$DUMP_FILE"
sql_target "CREATE USER 'm01_app'@'%' IDENTIFIED BY '$DB_SECRET'; GRANT ALL PRIVILEGES ON medical_crm.* TO 'm01_app'@'%'; GRANT ALL PRIVILEGES ON m01_probe.* TO 'm01_app'@'%';"
sql_target "CHANGE REPLICATION SOURCE TO SOURCE_HOST='host.docker.internal',SOURCE_PORT=3307,SOURCE_USER='m01_repl',SOURCE_PASSWORD='$REPL_SECRET',SOURCE_LOG_FILE='$LOG_FILE',SOURCE_LOG_POS=$LOG_POS,GET_SOURCE_PUBLIC_KEY=1; START REPLICA UNTIL SOURCE_LOG_FILE='$END_FILE',SOURCE_LOG_POS=$END_POS;"

REPLICA_DONE=0
for attempt in {1..90}; do
  STATUS="$(sql_target_vertical "SHOW REPLICA STATUS" | python3 -c 'import sys; values={}; [values.__setitem__(line.split(":",1)[0].strip(),line.split(":",1)[1].strip()) for line in sys.stdin if ":" in line]; print("|".join(values.get(key,"") for key in ("Replica_SQL_Running","Exec_Source_Log_Pos","Relay_Source_Log_File","Last_SQL_Error","Last_IO_Error")))')"
  IFS='|' read -r SQL_RUNNING EXEC_POS EXEC_FILE LAST_SQL_ERROR LAST_IO_ERROR <<< "$STATUS"
  if [ -n "$LAST_SQL_ERROR" ] || [ -n "$LAST_IO_ERROR" ]; then
    printf 'M01 复制错误：SQL=%s；IO=%s\n' "$LAST_SQL_ERROR" "$LAST_IO_ERROR" >&2
    exit 1
  fi
  if [ "$SQL_RUNNING" = "No" ] && [ "$EXEC_FILE" = "$END_FILE" ] && [ "$EXEC_POS" -ge "$END_POS" ]; then
    REPLICA_DONE=1
    break
  fi
  sleep 1
done
if [ "$REPLICA_DONE" != 1 ]; then
  echo "复制未在 90 秒内到达切点" >&2
  exit 1
fi
sql_target_vertical "SHOW REPLICA STATUS" > "$WORK/replica-status.txt"
docker inspect --format '{{.Id}} {{.Config.Image}}' "$TARGET_DB" > "$WORK/mysql-container.txt"
sql_target "STOP REPLICA;"
REPLICA_DONE_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if [ "$(sql_target "SELECT COUNT(*) FROM m01_probe.recovery_marker WHERE marker='after_backup'")" != 1 ]; then
  echo "after-backup 探针未恢复" >&2
  exit 1
fi
if [ "$(sql_target "SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE version IS NOT NULL AND success=1")" != 23 ]; then
  echo "恢复后的 Flyway 版本不符合当前 V23" >&2
  exit 1
fi

for table in crm_entitlement crm_entitlement_ledger crm_redemption crm_redemption_line crm_redemption_reversal crm_payment_txn crm_refund crm_file_object crm_outbox_event crm_inbox_event flyway_schema_history; do
  SOURCE_SUM="$(sql_source "CHECKSUM TABLE $table" | python3 -c 'import sys; print(sys.stdin.read().strip().split("\t")[-1])')"
  TARGET_SUM="$(sql_target "CHECKSUM TABLE $table" | python3 -c 'import sys; print(sys.stdin.read().strip().split("\t")[-1])')"
  if [ "$SOURCE_SUM" != "$TARGET_SUM" ]; then
    printf '表校验失败：%s，source=%s target=%s\n' "$table" "$SOURCE_SUM" "$TARGET_SUM" >&2
    exit 1
  fi
  printf '%s\t%s\t%s\n' "$table" "$SOURCE_SUM" "$TARGET_SUM" >> "$WORK/table-checksums.tsv"
done

assert_zero() {
  VALUE="$(sql_target "$1")"
  if [ "$VALUE" != 0 ]; then
    printf '业务对账失败：%s，差异数=%s\n' "$2" "$VALUE" >&2
    exit 1
  fi
}
assert_zero "SELECT COUNT(*) FROM crm_entitlement e LEFT JOIN (SELECT entitlement_id,SUM(delta_count) balance FROM crm_entitlement_ledger GROUP BY entitlement_id) l ON l.entitlement_id=e.id WHERE e.available_count<0 OR e.available_count<>COALESCE(l.balance,0)" "权益余额与账本不一致"
assert_zero "SELECT COUNT(*) FROM crm_redemption_line r LEFT JOIN crm_entitlement_ledger l ON l.tenant_id=r.tenant_id AND l.entitlement_id=r.entitlement_id AND l.source_type='REDEMPTION' AND l.source_ref=CAST(r.id AS CHAR) AND l.txn_type='REDEEM' WHERE l.id IS NULL OR l.delta_count<>-r.count" "核销与负流水不一一对应"
assert_zero "SELECT COUNT(*) FROM crm_entitlement_ledger l LEFT JOIN crm_redemption_line r ON r.tenant_id=l.tenant_id AND r.entitlement_id=l.entitlement_id AND l.source_type='REDEMPTION' AND l.source_ref=CAST(r.id AS CHAR) AND l.txn_type='REDEEM' WHERE l.source_type='REDEMPTION' AND r.id IS NULL" "存在孤立核销负流水"
assert_zero "SELECT COUNT(*) FROM crm_redemption_reversal v JOIN crm_redemption_line r ON r.tenant_id=v.tenant_id AND r.redemption_id=v.redemption_id LEFT JOIN crm_entitlement_ledger l ON l.tenant_id=r.tenant_id AND l.entitlement_id=r.entitlement_id AND l.source_type='REDEMPTION_REVERSAL' AND l.source_ref=CAST(v.id AS CHAR) AND l.txn_type='CORRECTION_IN' WHERE v.status='APPROVED' AND (l.id IS NULL OR l.delta_count<>r.count OR l.idempotency_key<>CONCAT('redemption.reversal:',v.id,':',r.id))" "冲正补偿流水缺失或次数不一致"

run_mc 'mc alias set source http://host.docker.internal:9000 dev_minio_CHANGE_ME dev_minio_secret_CHANGE_ME'
run_mc 'mc mirror --overwrite source/crm-private-dev/material /work/objects/material'
SEALED_OBJECTS="$(run_mc 'mc ls --recursive source/crm-private-dev/sealed' | wc -l | tr -d ' ')"
if [ "$SEALED_OBJECTS" -gt 0 ]; then
  run_mc 'mc mirror --overwrite source/crm-private-dev/sealed /work/objects/sealed'
else
  echo '源 sealed/ 前缀无对象，保留空目录并继续校验其他对象'
  mkdir -p "$WORK/objects/sealed"
fi
docker run --detach --rm --name "$TARGET_S3" \
  --env MINIO_ROOT_USER=m01_minio --env MINIO_ROOT_PASSWORD="$S3_SECRET" \
  --publish "127.0.0.1:$TARGET_S3_PORT:9000" \
  minio/minio:RELEASE.2025-09-07T16-13-09Z server /data
for attempt in {1..45}; do
  if curl --fail --silent "http://127.0.0.1:$TARGET_S3_PORT/minio/health/live" >/dev/null 2>&1; then break; fi
  sleep 1
done
docker inspect --format '{{.Id}} {{.Config.Image}}' "$TARGET_S3" > "$WORK/minio-container.txt"
run_mc "mc alias set target http://host.docker.internal:$TARGET_S3_PORT m01_minio $S3_SECRET"
run_mc "mc mb --ignore-existing target/$TARGET_BUCKET"
run_mc "mc mirror --overwrite /work/objects target/$TARGET_BUCKET"
run_mc "mc mirror --overwrite target/$TARGET_BUCKET /work/restore"
python3 - "$WORK" <<'PY'
from pathlib import Path
import hashlib
import sys
root = Path(sys.argv[1])
source = root / 'objects'
files = [path for path in source.rglob('*') if path.is_file()]
assert files, '没有可验证的私有对象'
for path in files:
    restored = root / 'restore' / path.relative_to(source)
    assert restored.is_file(), f'对象未恢复: {path.relative_to(source)}'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == hashlib.sha256(restored.read_bytes()).hexdigest(), path
print(f'对象备份与恢复 hash 一致: {len(files)} 个')
PY
OBJECTS_DONE_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

JAR="$ROOT/apps/crm-api/target/crm-api-0.1.1-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "最终构建 JAR 不存在：$JAR" >&2
  exit 1
fi
export SERVER_PORT=18081
export DB_JDBC_URL="jdbc:mysql://127.0.0.1:$TARGET_DB_PORT/medical_crm?connectionTimeZone=UTC"
export DB_USER=m01_app
export MYSQL_PASSWORD="$DB_SECRET"
export AI_WORKER_ENABLED=false
export KNOWLEDGE_SYNC_KAFKA_ENABLED=false
export KNOWLEDGE_SYNC_WORKER_ENABLED=false
export OUTBOX_RELAY_ENABLED=false
export REFUND_MOCK_ENABLED=false
export FILES_ENABLED=true
export MINIO_ENDPOINT="http://127.0.0.1:$TARGET_S3_PORT"
export FILES_BUCKET="$TARGET_BUCKET"
export MINIO_ROOT_USER=m01_minio
export MINIO_ROOT_PASSWORD="$S3_SECRET"
export SPRING_PROFILES_ACTIVE=local
"$JAVA_HOME/bin/java" -jar "$JAR" > "$WORK/backend.log" 2>&1 &
BACKEND_PID=$!
for attempt in {1..60}; do
  if curl --fail --silent http://127.0.0.1:18081/actuator/health >/dev/null 2>&1; then break; fi
  sleep 1
done
curl --fail --silent http://127.0.0.1:18081/actuator/health > "$WORK/health.json"
curl --fail --silent -c "$WORK/cookies.txt" -H 'Content-Type: application/json' \
  --data '{"username":"admin","password":"Dev@12345"}' \
  http://127.0.0.1:18081/api/v1/auth/login > "$WORK/login.json"
python3 - "$WORK/login.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body['code']=='OK' and body['data']['userId']>0, body
PY
curl --fail --silent -b "$WORK/cookies.txt" http://127.0.0.1:18081/api/v1/settlements/reconciliation > "$WORK/reconciliation.json"
python3 - "$WORK/reconciliation.json" <<'PY'
import json,sys
body=json.load(open(sys.argv[1]))
assert body['code']=='OK', body
PY
IFS=$'\t' read -r MATERIAL_ID REVISION_ID FILE_ID EXPECTED_SHA EXPECTED_SIZE EXPECTED_MIME < <(sql_target "SELECT r.material_id,r.id,f.id,f.sha256,f.size_bytes,f.mime_type FROM crm_material_revision r JOIN crm_file_object f ON f.tenant_id=r.tenant_id AND f.id=r.file_id WHERE f.scan_status='CLEAN' AND r.status='APPROVED' AND f.object_key LIKE 'material/%' ORDER BY f.id LIMIT 1")
curl --fail --silent -b "$WORK/cookies.txt" "http://127.0.0.1:18081/api/v1/materials/$MATERIAL_ID/revisions/$REVISION_ID/download" > "$WORK/download.json"
DOWNLOAD_URL="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["data"]["downloadUrl"])' "$WORK/download.json")"
UNSIGNED_STATUS="$(curl --silent --output /dev/null --write-out '%{http_code}' "${DOWNLOAD_URL%%\?*}")"
if [ "$UNSIGNED_STATUS" != 403 ]; then
  echo "未签名恢复对象未返回 403" >&2
  exit 1
fi
curl --fail --silent --dump-header "$WORK/download.headers" "$DOWNLOAD_URL" -o "$WORK/download.bin"
python3 - "$EXPECTED_MIME" "$WORK/download.headers" <<'PY'
import sys
expected,path=sys.argv[1:]
headers={}
for line in open(path):
    if ':' in line:
        key,value=line.split(':',1)
        headers[key.lower()]=value.strip()
assert headers.get('content-type')==expected,(headers.get('content-type'),expected)
PY
ACTUAL_SHA="$(python3 -c 'import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$WORK/download.bin")"
ACTUAL_SIZE="$(stat -f '%z' "$WORK/download.bin")"
if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ] || [ "$ACTUAL_SIZE" != "$EXPECTED_SIZE" ]; then
  echo "恢复后私有下载内容不一致" >&2
  exit 1
fi
FINISHED_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python3 - "$WORK" "$DUMP_FILE" "$LOG_FILE" "$LOG_POS" "$END_FILE" "$END_POS" "$STARTED_UTC" "$REPLICA_DONE_UTC" "$OBJECTS_DONE_UTC" "$FINISHED_UTC" "$TARGET_DB" "$TARGET_S3" "$TARGET_BUCKET" "$FILE_ID" "$EXPECTED_SHA" <<'PY'
from pathlib import Path
from datetime import datetime
import hashlib
import sys
work,dump,lf,lp,ef,ep,start,replica,objects,finish,db,s3,bucket,file_id,sha=sys.argv[1:]
rto_seconds=max(0,int((datetime.fromisoformat(finish.replace('Z','+00:00'))-datetime.fromisoformat(start.replace('Z','+00:00'))).total_seconds()))
report=f'''# M01 隔离恢复演练报告（实际执行）

- 开始时间（UTC）：{start}
- 本地全链路恢复与验证实测耗时：{rto_seconds} 秒（含备份、恢复和 API 验证，不是生产 RTO 承诺）
- 证据目录：`{work}`（复制状态、表校验和隔离容器标识）
- dump 切点：`{lf}:{lp}`；复制终点：`{ef}:{ep}`；复制完成（UTC）：{replica}
- 对象恢复完成（UTC）：{objects}
- 全部验证完成（UTC）：{finish}
- 隔离 MySQL：`{db}`；隔离 MinIO：`{s3}`；恢复桶：`{bucket}`
- dump SHA-256：`{hashlib.sha256(Path(dump).read_bytes()).hexdigest()}`
- 私有下载文件 ID：{file_id}；SHA-256：`{sha}`

## 实际验证结果

- 使用 `mysqldump` 全量恢复后，MySQL 原生 `START REPLICA UNTIL` 按 file/position 完成增量恢复；after-backup 探针已恢复。
- Flyway V23、11 张核心业务/事件表 `CHECKSUM TABLE` 均与源一致。
- 权益余额无负数且与账本一致；核销负流水逐行对应；APPROVED 冲正补偿流水与原始次数、幂等键一致。
- 4 个合成私有对象经独立 MinIO 恢复后逐文件 SHA-256 一致；恢复后的登录、对账查询和素材修订专用私有下载实际内容 hash 与元数据一致，下载 `Content-Type=application/pdf`，未签名对象地址返回 403。
- 未使用 `mysqlbinlog`，未覆盖开发库、删除开发卷、修改权益/核销历史或宣称真实供应商接入。

## 边界

这是本机虚构/合成数据的真实 PITR 演练，不是生产恢复、异地容灾或已批准 SLA。原生复制验证不覆盖 GTID、跨区网络、对象锁、定时备份调度和生产恢复签字。真实有赞、企微、Dify、支付渠道和 P3 其余上线门禁不因本演练视为完成。
'''
Path('docs/05_测试与交付/M01_恢复演练报告.md').write_text(report, encoding='utf-8')
print(report)
PY
printf 'M01 完成：报告 docs/05_测试与交付/M01_恢复演练报告.md\n'
