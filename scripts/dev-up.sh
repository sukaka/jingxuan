#!/usr/bin/env bash
# 启动本地开发中间件（MySQL 8.4 / Redis 7.4 / Kafka KRaft / MinIO）。仅限本地开发。
set -euo pipefail
cd "$(dirname "$0")/../infra/compose"

if [ ! -f .env ]; then
  cp .env.example .env
  echo "已生成 infra/compose/.env（开发默认值，勿提交真实密钥）"
fi

docker compose -f docker-compose.dev.yml --env-file .env up -d
docker compose -f docker-compose.dev.yml --env-file .env ps
echo
echo "MySQL: localhost:3307  Redis: localhost:6380  Kafka: localhost:29092  MinIO: http://localhost:9001"
