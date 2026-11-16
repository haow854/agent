#!/bin/sh
# nginx 镜像启动时执行 /docker-entrypoint.d/ 下的脚本，然后才启动 nginx。
# 这里先按证书选一次配置，再在后台每 5 分钟检查一次证书有没有变化。
docagent-site
( while sleep 300; do docagent-site --reload; done ) &
