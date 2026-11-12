#!/usr/bin/env bash
set -euo pipefail

# 从仓库根目录执行，无论脚本从哪里被调用。
cd "$(dirname "$0")/.."

if [[ -z "${DOCAGENT_MANAGER_HOST:-}" ]]; then
  echo "DOCAGENT_MANAGER_HOST is required"
  exit 1
fi

./deploy/build-docker.sh

if [[ "$(docker info --format '{{.Swarm.LocalNodeState}}')" != "active" ]]; then
  docker swarm init --advertise-addr "${DOCAGENT_MANAGER_HOST}"
fi

if ! docker network inspect docagent-network >/dev/null 2>&1; then
  docker network create --driver overlay --attachable docagent-network
fi

docker stack deploy -c deploy/docker-compose-infra.yml docagent-infra
docker stack deploy -c deploy/docker-compose-services.yml docagent-services

docker service ls
