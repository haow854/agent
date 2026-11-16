#!/usr/bin/env bash
# 在开发机上生成部署产物：后端 jar（api/target/）和前端静态文件（web/dist/）。
# 镜像由 docker compose 用这些产物构建，见 deploy/docker-compose.yml。
set -euo pipefail

# 从仓库根目录执行，无论脚本从哪里被调用
cd "$(dirname "$0")/.."

echo "==> 构建后端"
(cd api && ./mvnw -q -DskipTests clean package)

echo "==> 构建前端"
(cd web && { [ -d node_modules ] || npm ci; } && npm run build)
