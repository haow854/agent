# 待办看板

> 下一个编号：T-013

## 🚧 进行中

- [ ] T-005 基础部署：购买云服务器（推荐腾讯云，至少 2 核 4G），用 `deploy/` 下的脚本部署，公网可访问（F-17）
- [ ] T-006 前端容器化：为 `web/` 写 Dockerfile + nginx，反向代理 `/api`、`/ws`、`/collab`，加入 `deploy/docker-compose-services.yml`（T-005 的前置）

## ⏭️ 下一步

<!-- 已经想清楚、随时可以开工的事，按优先级排序 -->

- [ ] T-008 补齐 01-product/README.md 的目标用户、设计原则、非目标（需要本人确认产品方向）
- [ ] T-012 修复 7 个安全相关测试：`MonolithSecurityConfigTest`、`DocumentEndpointAuthenticationTest` 期望 403 实际 401。先确认哪个是对的：未登录返回 401 更符合语义，可能该改测试而不是改代码
- [ ] T-001 WebSocket（STOMP）握手鉴权，进度推送改为按用户/任务定向，不再全局广播（F-14，见 `MonolithSecurityConfig.java` 中的 TODO）
- [ ] T-002 Swagger 按 profile 开关，生产环境默认关闭（见 `MonolithSecurityConfig.java` 中的 TODO）

## 🗂️ 待排期

<!-- 想做但还没排上，或还需要先写 spec 的事 -->

- [ ] T-009 前端接入评论与批注（F-12，跨前后端，需先写 spec）
- [ ] T-010 补录既有架构决策：ReAct 执行模型、RabbitMQ 异步任务、Agent 通过协同服务编辑文档、存储分层（MySQL / Redis / MinIO）
- [ ] T-011 发布版本：在 GitHub 上打 tag（如 `v1.0.0`），附带部署说明或打包产物

## 📥 收件箱

<!-- 随手记的想法、发现的问题，未经整理 -->

- 前端主包 1.2 MB、编辑器包 540 KB（`npm run build` 有体积警告），可考虑拆包
- `deploy/` 的 compose 使用 Docker Swarm（`docker stack deploy`），单机部署时是否改用 `docker compose` 更简单？
