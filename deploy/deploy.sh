#!/usr/bin/env bash
# 构建部署产物，上传到服务器，在服务器上构建镜像并启动全部服务。
#
# 用法（在开发机上）：
#   DOCAGENT_HOST=ubuntu@<服务器IP> ./deploy/deploy.sh
#
# 可选环境变量：
#   DOCAGENT_SSH_KEY     SSH 私钥，默认 ~/.ssh/docagent_deploy
#   DOCAGENT_REMOTE_DIR  服务器上的部署目录，默认 /opt/docagent
#
# 服务器上的 <部署目录>/.env 需要事先准备好，本脚本不会上传或改动它。
# 服务器初始化见 docs/01-product/specs/F-17-deployment.md
set -euo pipefail

# 从仓库根目录执行，无论脚本从哪里被调用
cd "$(dirname "$0")/.."

: "${DOCAGENT_HOST:?请设置 DOCAGENT_HOST，例如 ubuntu@1.2.3.4}"
SSH_KEY="${DOCAGENT_SSH_KEY:-$HOME/.ssh/docagent_deploy}"
REMOTE_DIR="${DOCAGENT_REMOTE_DIR:-/opt/docagent}"
COMPOSE="docker compose --env-file .env -f deploy/docker-compose.yml"

remote() {
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "$DOCAGENT_HOST" "$@"
}

./deploy/build.sh

echo "==> 上传到 $DOCAGENT_HOST:$REMOTE_DIR"
# 清掉上一次上传的文件（.env 和数据卷不受影响），再按仓库的目录结构解压
remote "test -f '$REMOTE_DIR/.env' || { echo '缺少 $REMOTE_DIR/.env'; exit 1; }
        cd '$REMOTE_DIR' && rm -rf api web collab-server deploy"
tar czf - \
  deploy/docker-compose.yml deploy/demo-users.sql deploy/certbot.sh \
  api/Dockerfile api/.dockerignore api/mysql-schema.sql api/target/docAgent-1.0.0.jar \
  web/Dockerfile web/.dockerignore web/nginx web/dist \
  collab-server/Dockerfile collab-server/.dockerignore \
  collab-server/package.json collab-server/package-lock.json collab-server/*.js \
  | remote "tar xzf - -C '$REMOTE_DIR'"

echo "==> 构建镜像并启动"
remote "cd '$REMOTE_DIR' && $COMPOSE up -d --build --remove-orphans && $COMPOSE ps"
