#!/usr/bin/env bash
set -euo pipefail

# 从仓库根目录执行，无论脚本从哪里被调用。
cd "$(dirname "$0")/.."

(cd api && ./mvnw clean package -DskipTests)
docker build -t docagent/docagent:1.0.0 api
docker build -t docagent/collab-server:1.0.0 collab-server

docker images 'docagent/*'
