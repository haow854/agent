FROM nginx:1.27-alpine

# HTTP 和 HTTPS 两套 nginx 配置，启动时按证书是否存在选一套，之后每 5 分钟检查一次
COPY nginx/app.conf nginx/http.conf nginx/https.conf /etc/nginx/docagent/
COPY --chmod=755 nginx/docagent-site.sh /usr/local/bin/docagent-site
COPY --chmod=755 nginx/40-docagent-site.sh /docker-entrypoint.d/

# dist 由 deploy/build.sh 在开发机上构建
COPY dist/ /usr/share/nginx/html/
