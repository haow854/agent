# F-17：云服务器部署

- **状态**：开发中
- **日期**：2026-11-12
- **关联**：T-005、T-006、ADR-0003、ADR-0006

## 要解决什么问题

项目需要一个公网可访问的演示环境：别人打开一个地址，就能登录、编辑文档、让 Agent 处理文档。环境运行在一台 2 核 4G 的云服务器上（腾讯云轻量应用服务器，上海地域），全部服务在这一台机器上。

## 做完长什么样

- [x] 浏览器访问 `https://<服务器IP>` 打开前端，地址栏显示安全连接，用演示账号登录；访问 `http://<服务器IP>` 自动跳到 https
- [x] 两个标签页打开同一份文档，编辑实时同步
- [x] 让 Agent 修改文档，进度实时推送，修改出现在编辑器里
- [x] 上传文件
- [ ] Agent 能抓取网页内容：线上验证了联网搜索；读网页只在本地容器里验证过
- [x] 公网只能访问 80、443 端口（SSH 的 22 除外），数据库、缓存、队列、对象存储都不对外
- [ ] 证书自动续期，不用人工处理：首次签发和自动切到 HTTPS 已验证，续期要等几天后看
- [ ] 服务器重启后全部服务自动恢复
- [x] 一条命令完成重新部署：`DOCAGENT_HOST=ubuntu@<IP> ./deploy/deploy.sh`

## 不做什么

- 域名与 ICP 备案：先用 IP 访问，HTTPS 用 IP 证书（ADR-0006）
- HSTS：证书只有 6 天有效，续期一旦出问题，开了 HSTS 的浏览器连「继续访问」都点不了
- 多机部署与高可用
- 监控栈（Prometheus + Grafana）：4G 内存放不下，`/actuator/prometheus` 也需要先解决访问鉴权（T-014）
- 推送代码后自动构建和部署（T-013）
- 数据库表结构迁移工具

## 设计

### 拓扑

```
浏览器 ──:443──▶ web（nginx，前端静态文件）
        ──:80───▶ web：证书验证文件，其余 301 到 https
                 ├─ /api/、/ws/ ──▶ api:8080
                 └─ /collab     ──▶ collab-server:1234
certbot ──▶ Let's Encrypt：签发和续期 IP 证书，验证文件经共享卷交给 web
内部网络：mysql、redis、rabbitmq、minio（不发布端口）
```

所有服务定义在 `deploy/docker-compose.yml`，compose 项目名 `docagent`。

### 服务

| 服务 | 镜像 | 要点 |
| --- | --- | --- |
| web | `nginx:1.27-alpine` + `web/dist` | 唯一发布端口的服务：80 和 443（`HTTP_PORT`、`HTTPS_PORT` 可改）；有证书时走 HTTPS，没有时退回 HTTP |
| certbot | `certbot/certbot:v5.9.0` | 签发和续期 Let's Encrypt 的 IP 证书；不设 `PUBLIC_IP` 时什么都不做 |
| api | `eclipse-temurin:17-jre-noble` + 后端 jar | 堆上限 512 MB |
| collab-server | `node:22-alpine` | 每次连接都向后端校验文档权限，校验不通过（包括后端不可达）一律拒绝 |
| mysql | `mysql:8.0` | 关闭 performance_schema，最大连接数 100；数据卷为空时执行 `api/mysql-schema.sql` 和 `deploy/demo-users.sql` |
| redis | `redis:7-alpine` | 密码 + AOF 持久化 |
| rabbitmq | `rabbitmq:4.3-management` | 固定 hostname，容器重建后沿用原数据目录 |
| minio | `pgsty/minio` | MinIO 官方镜像已不在 Docker Hub 发布，使用社区维护的构建；存储桶由后端启动时创建 |

内存预估：空闲约 1.5 GB，高峰约 2.3 GB；本地演练跑过几次 Agent 任务后实测约 1.2 GB（api 约 770 MB）。全部容器 `restart: unless-stopped`，容器日志单文件 10 MB、保留 3 个。

### nginx 路由

| 路径 | 去向 | 说明 |
| --- | --- | --- |
| `/.well-known/acme-challenge/` | 共享卷 `acme-webroot` | 只在 80 端口；Let's Encrypt 的验证文件 |
| `/api/` | api | 读超时 300 秒；上传上限 100 MB |
| `/ws/` | api | STOMP over WebSocket；CONNECT 帧带访问令牌，进度只推给任务所属的用户 |
| `= /collab` | collab-server 的 `/` | Hocuspocus WebSocket；只代理这一个路径，协同服务的 `/ai/*` 只供后端在内网调用 |
| `/assets/` | 静态文件 | 文件名带 hash，长期缓存 |
| 其他 | `index.html` | 前端 history 路由回落 |

`/api-docs`、`/swagger-ui.html`、`/actuator` 不在代理范围内，公网访问不到。上游地址写成变量，由 Docker 内置 DNS（`127.0.0.11`）在请求时解析，后端容器重建后不用重启 nginx。

### HTTPS

证书是 Let's Encrypt 给 IP 地址签的证书（ADR-0006），只能用 `shortlived` 档，有效期约 6 天。

**签发与续期**：certbot 作为 compose 里的一个服务常驻，循环执行：

- 还没有证书：`certbot certonly --webroot -w /var/www/acme --ip-address $PUBLIC_IP --preferred-profile shortlived --cert-name docagent --agree-tos --register-unsafely-without-email --non-interactive`，失败 1 小时后重试
- 已有证书：每 12 小时 `certbot renew`。certbot 按 Let's Encrypt 的 ARI（续期信息）决定什么时候真的续，6 天的证书大约剩一半有效期时续一次，不会撞到频率限制
- `--agree-tos` 表示同意 Let's Encrypt 的订户协议，部署即视为同意；不留邮箱，Let's Encrypt 已经不发到期提醒邮件
- 证书固定放在 `/etc/letsencrypt/live/docagent/`（`--cert-name`），路径和 IP 无关

**验证**：HTTP-01，webroot 方式。web 在 80 端口提供 `/.well-known/acme-challenge/`，目录是和 certbot 共享的卷 `acme-webroot`。

**nginx 按证书是否存在选配置**：web 容器启动时，以及之后每 5 分钟检查一次：

- 有证书：80 端口只提供验证文件，其余 301 到 https；443 端口开 `ssl` 和 `http2`，提供整个应用
- 没有证书：80 端口直接提供整个应用，和不开 HTTPS 时一样

证书文件有变化（续期后 `live/docagent/fullchain.pem` 指向新的文件），或者从没有到有，就换配置并 `nginx -s reload`。这样首次部署不用分两步，证书签下来后几分钟内自动切到 HTTPS；本地运行完整环境时没有证书，照旧是 `http://localhost`。

两种配置共用同一份 location 定义（下表「nginx 路由」），只是外面的 server 块不同。证书卷 `letsencrypt` 以只读方式挂进 web。

**前端和后端不用改**：进度推送和协同的 WebSocket 都按 `location.protocol` 选 `ws`/`wss`；接口用相对路径。

### 配置

- 配置统一来自仓库根目录的 `.env`，键名见 `.env.example`，本地开发与服务器共用一套键名
- api 容器通过 `env_file` 读取整份 `.env`；compose 只写容器内外不同的值（服务地址、JVM 参数、容器内路径），以及各服务必须一致的账号密码
- 必填项（数据库、Redis、RabbitMQ、MinIO 密码，`JWT_SECRET`、`COLLAB_AI_SECRET`）缺失时 compose 直接报错，不带默认值启动
- 表结构脚本固定使用 `docagent` 库
- 线上不开放注册：compose 给 api 设 `REGISTRATION_ENABLED=false`，后端直接拒绝注册接口，从容器网络里调也一样；本地开发默认开放，评测脚本要注册账号
- 在国内服务器上构建镜像时，用 `NPM_REGISTRY` 指定国内 npm 源；Docker 镜像通过腾讯云镜像加速拉取
- `PUBLIC_IP`：服务器公网 IP，certbot 用它申请证书；本地不设

### 构建与发布

- `deploy/build.sh`：在开发机上编译后端 jar 和前端 `dist/`
- 各 Dockerfile 只拷贝构建产物，镜像构建不编译代码
- `deploy/deploy.sh`：运行 `build.sh` → 打包 compose 文件、演示账号 SQL、Dockerfile 和构建产物，经 SSH 上传到服务器 `/opt/docagent`（目录结构与仓库一致，不含 `.env`）→ 在服务器上 `docker compose up -d --build`
- 本地运行完整环境：`./deploy/build.sh && docker compose --env-file .env -f deploy/docker-compose.yml up -d --build`

### 服务器初始化（一次性）

1. 控制台导入 SSH 公钥并绑定实例；防火墙只开放 22、80、443
2. 安装 Docker（腾讯云软件源），配置镜像加速；把部署用户加进 `docker` 组，`deploy.sh` 经 SSH 执行 `docker compose` 不带 sudo
3. 创建 `/opt/docagent` 并交给部署用户，在服务器上生成 `.env`：各类密码与密钥用随机值，`DASHSCOPE_API_KEY` 从开发机拷贝，`PUBLIC_IP` 填服务器公网 IP
4. 演示账号不用手动创建：MySQL 首次初始化时由 `deploy/demo-users.sql` 写入 demo1～demo5，文件里只有 BCrypt 哈希，明文密码在开发机的 `deploy/demo-accounts.txt`（不提交）。之后要增减账号，在 mysql 容器里手动执行 SQL
5. 百炼控制台设置费用预警

### 风险与待定问题

- [ ] 联网搜索和网页读取依赖 Tavily，国内服务器要能连上 `mcp.tavily.com`；网页由 Tavily 下载，屏蔽境外 IP 的国内网站可能读不到（ADR-0005）
- [ ] OpenAI 直连在国内服务器不可用，线上只用百炼
- [ ] 表结构只在 MySQL 数据卷为空时初始化，之后的表结构改动需要手动在线上执行
- [ ] 证书只有 6 天：certbot 容器停了或续期一直失败，几天后证书过期，浏览器会拦截。部署后查一次 `docker compose logs certbot`，确认续期正常
- [ ] 本地拿不到真证书（Let's Encrypt 要从公网访问 80 端口）。本地演练用自签证书放进证书卷，验证 nginx 能切到 HTTPS；真证书的签发和续期只能在服务器上验证

## 实施拆分

1. T-006 部署配置：compose、各服务 Dockerfile、nginx、构建与部署脚本、`.env.example`；用 Docker Desktop 在本地跑通，按验收标准逐项验证
2. T-005 服务器初始化与首次部署，公网验证

## 验证方式

本地和服务器上各按「做完长什么样」逐项手动验证；用 `docker stats` 记录各容器实际内存。
