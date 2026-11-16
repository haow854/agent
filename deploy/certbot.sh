#!/bin/sh
# certbot 容器的主循环：给 PUBLIC_IP 签 Let's Encrypt 的 IP 证书，之后定期续期。
# 不设 PUBLIC_IP（本地运行完整环境）时什么都不做。设计见 docs/01-product/specs/F-17-deployment.md
trap 'exit 0' TERM

while :; do
  pause=43200
  if [ -n "${PUBLIC_IP:-}" ]; then
    if [ -f /etc/letsencrypt/live/docagent/fullchain.pem ]; then
      # 什么时候真的续期由 certbot 按 ARI 决定，没到时间就什么都不做
      certbot renew --quiet
    elif ! certbot certonly --non-interactive --agree-tos --register-unsafely-without-email \
        --webroot -w /var/www/acme --preferred-profile shortlived \
        --ip-address "$PUBLIC_IP" --cert-name docagent; then
      pause=3600
    fi
  fi
  sleep "$pause" &
  wait $!
done
