# 2026-11-15 · 首次部署到腾讯云

- **目标**：服务器初始化并首次部署，公网验收（T-005、T-033）
- **结果**：
  - 服务器：Ubuntu 22.04，2 核 3.6 GB；只能用密钥登录。从腾讯云 docker-ce 源装 Docker 29.9 和 compose 插件，镜像加速指向 `mirror.ccs.tencentyun.com`，ubuntu 加进 docker 组，建 `/opt/docagent`
  - `.env` 在服务器上生成：7 个密钥用 `openssl rand -hex 32`，生成时不输出；百炼和 Tavily 的 key 由用户自己填
  - 部署前在服务器上确认能连上 Let's Encrypt、Tavily、百炼和腾讯云的各个软件源
  - `deploy.sh` 一次完成：8 个容器全部启动。certbot 首次运行就签下 IP 证书（Let's Encrypt YE2，SAN 为服务器 IP，有效约 6.7 天），web 启动 245 秒后自动切到 HTTPS
  - 公网验收：
    - 不登录：证书可信（不跳过校验）、h2、http 301 到 https；1234、3306、5672、6379、8080、9000、9001、15672 从外面都连不上；`/api-docs`、`/swagger-ui.html`、`/actuator/*`、`/ai/read` 不对外；注册被后端拒绝；未登录调接口被拒；进度推送和协同经 wss 可达
    - 登录（用户在自己的 Chrome 里登录 demo1，经 Claude in Chrome 测）：两个标签页实时协同，能看到对方光标；文档助手里 Agent 联网搜索后改写文档，进度步骤实时显示，改动实时出现在另一个标签页并回存；上传 1 MB 文件成功。测试文档和文件已删除
  - 服务器空闲时内存占用约 1 GB
- **问题**：
  - 后端 jar 146 MB，上传约 65 KB/s，首次部署光上传就花了约 40 分钟（记为 T-034）
  - 内置浏览器面板和用户的 Chrome 登录状态不共享，用户在 Chrome 登录后改用 Claude in Chrome 测
- **遗留**：
  - 约 3 天后看 `docker compose logs certbot`，确认证书自动续期（T-033）
  - 服务器重启后自动恢复、线上让 Agent 读一次网页，还没验（T-005）
  - 部署时代码没有提交，线上版本暂时对应不到提交
