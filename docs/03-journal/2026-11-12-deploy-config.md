# 2026-11-12 · 部署设计与部署配置

- **目标**：确定部署方案并写好部署配置（T-006），为上服务器（T-005）做准备
- **结果**：
  - 服务器：腾讯云轻量应用服务器 2 核 4G、5M 带宽（上海），系统 Ubuntu 22.04
  - [spec F-17](../01-product/specs/F-17-deployment.md)：单机 7 个服务，只有 nginx 发布端口；路由、配置来源、构建与发布流程、服务器初始化步骤
  - 部署配置：`deploy/docker-compose.yml`、`deploy/build.sh`、`deploy/deploy.sh`，api / collab-server / web 各自的 Dockerfile 和 `.dockerignore`，`web/nginx.conf.template`；`.env.example` 加入部署相关的键
  - 已验证：compose 配置校验通过，缺少必填密码时直接报错；web 镜像 `nginx -t` 通过；collab-server 镜像包含 `markdown.js` 且不含测试文件；api 镜像内 Tesseract 5.3.4 带 `chi_sim`，`mcp_server_fetch` 可导入
  - 未验证：整套服务在本地一起跑起来，以及 spec 里的各项验收标准
- **决策**：[ADR-0003](../04-adr/0003-single-host-compose.md)
- **问题**：MinIO 官方镜像已不在 Docker Hub 发布（`minio/minio` 返回 404，Quay 需要登录），使用社区维护的 `pgsty/minio`。api 镜像约 940 MB，服务器上首次构建要拉取不少内容
- **遗留**：
  - T-006：用 Docker Desktop 跑通完整环境，按 spec F-17 验收标准逐项验证；重点确认 tess4j 能加载容器里的 libtesseract
  - T-005：服务器初始化与首次部署，等服务器公网 IP
