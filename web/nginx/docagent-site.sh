#!/bin/sh
# 按证书是否存在选 nginx 配置：有证书用 https.conf，没有用 http.conf。
# 证书从无到有、或续期换了内容时，换上对应的配置；带 --reload 时让运行中的 nginx 重新加载。
set -eu

CERT=/etc/letsencrypt/live/docagent/fullchain.pem
STATE=/tmp/docagent-site

if [ -f "$CERT" ]; then
  wanted="https $(md5sum < "$CERT" | cut -d' ' -f1)"
  conf=https.conf
else
  wanted=http
  conf=http.conf
fi

if [ "$(cat "$STATE" 2>/dev/null)" = "$wanted" ]; then
  exit 0
fi

cp "/etc/nginx/docagent/$conf" /etc/nginx/conf.d/default.conf
if [ "${1:-}" = "--reload" ]; then
  nginx -s reload
fi
# 重新加载成功才记下来，失败的话下一轮再试
echo "$wanted" > "$STATE"
echo "docagent-site: 使用 $conf"
