# AGENTS.md

这是 AI 编程助手（Claude Code、Codex、Cursor 等）在本仓库工作的指南，人类开发者同样适用。

**docAgent**：AI Agent 与人在同一份实时协同文档里协作的文档应用。产品细节见 [docs/01-product/](docs/01-product/README.md)。

## 开工前必读

1. [docs/02-todos/backlog.md](docs/02-todos/backlog.md)：现在在做什么、下一步是什么
2. 最近一篇 [docs/03-journal/](docs/03-journal/README.md)：上次做到哪、留下了什么
3. 与本次任务相关的 spec（`docs/01-product/specs/`）、ADR（`docs/04-adr/`）和复盘（`docs/05-postmortems/`）
4. 拿不准产品方向时读 [docs/01-product/README.md](docs/01-product/README.md)，尤其是「设计原则」和「非目标」

## docs/ 知识库归属

`docs/` 是本项目的本地 LLM Wiki，由人和 AI 共同维护。编号前缀就是阅读顺序，每个目录只管一类事，目录内的 README 是索引和规则：

| 目录 | 归属（管什么） | 什么时候写 |
| --- | --- | --- |
| [docs/README.md](docs/README.md) | 知识库入口：会话流程、编号与命名约定、何时写 spec / ADR / 复盘 | 流程本身变化时 |
| [docs/01-product/](docs/01-product/README.md) | 产品定位、目标用户、设计原则、非目标；`features.md` 功能地图（`F-xx`）；`specs/` 单个功能设计（`F-xx-<slug>.md`） | 功能新增、砍掉或状态变化；较大功能动手前写 spec |
| [docs/02-todos/](docs/02-todos/README.md) | `backlog.md` 待办看板（`T-xxx`）；`done.md` 已完成归档 | 每次会话开始和结束 |
| [docs/03-journal/](docs/03-journal/README.md) | 开发日志，每次会话一篇（`YYYY-MM-DD-<slug>.md`） | 每次会话结束 |
| [docs/04-adr/](docs/04-adr/README.md) | 架构决策记录（`ADR-xxxx`），记录每个功能演进中的取舍，含索引 | 做出有取舍的决定时 |
| [docs/05-postmortems/](docs/05-postmortems/README.md) | 复盘：故障、难缠的 bug、AI 改坏东西导致的返工 | 出问题之后 |

硬规则：

- 跨模块、改接口或表结构、一次会话做不完的功能，**先写 spec，确认后再写代码**
- 已采纳的 ADR、写完的日志和复盘不改正文；要推翻 ADR 就新写一个并标注「取代 ADR-xxxx」
- 编号只增不复用，下一个可用编号写在 `features.md`、`backlog.md` 和 `04-adr/README.md` 顶部
- 没验证过的事不标「已完成」
- 复盘的行动项如果是约定，要写回本文件的「代码惯例」

## 仓库结构

| 目录 | 内容 | 技术栈 |
| --- | --- | --- |
| `web/` | 前端：对话、文档管理、编辑器 | Vue 3、Vite 4、Element Plus、TipTap |
| `api/` | 后端单体：账号、文档、文件、Agent 执行、AIOps | Java 17、Spring Boot 3.2、MyBatis-Plus、Spring AI |
| `collab-server/` | 实时协同服务，Agent 通过它读写 Y.Doc | Node.js、Hocuspocus、Yjs |
| `schemas/` | 前后端共享的 OpenAPI 定义，由后端生成 | springdoc |
| `eval/` | 黑盒评测：文档操作、多轮对话、工具调用 | Python |
| `deploy/` | compose、Prometheus 配置、部署脚本 | Docker Swarm |
| `docs/` | Vibe Coding 知识库 | Markdown |

后端包结构（`api/src/main/java/com/javaee/`）：`agentservice`（Agent 编排、模型、工具、会话、记忆）、`documentservice`、`fileservice`、`user`、`aiops`、`common`（公共模型与工具类）、`config`（安全与消息队列）。

## 本地开发

依赖服务：MySQL 8、Redis 7、RabbitMQ 3.8、MinIO。
配置：把 `.env.example` 复制为**仓库根目录**的 `.env` 并填写。后端从 `.env` 或 `../.env` 加载，所以从仓库根目录或 `api/` 启动都能读到。

| 模块 | 启动 | 测试 / 检查 |
| --- | --- | --- |
| api | `cd api && ./mvnw spring-boot:run`（端口 8080） | `cd api && ./mvnw test` |
| web | `cd web && npm install && npm run dev`（端口 5173，`/api` `/ws` 代理到 8080，`/collab` 代理到 1234） | `cd web && npm run build` |
| collab-server | `cd collab-server && npm install && npm start`（端口 1234） | `cd collab-server && npm test` |
| eval | 后端运行在 8080 时：`cd eval && python -m harness.runner` | `cd eval && python -m harness.runner --dry-run` |
| schemas | 后端运行时导出，见 [schemas/README.md](schemas/README.md) | |

部署：从仓库根目录执行 `./deploy/deploy.sh`，需要先设置 `DOCAGENT_MANAGER_HOST`。

## 代码惯例

后端：

- 按业务域分包，公共代码放 `common`
- Controller 返回值用 `common/model/Result` 包装（`FileMetadataController` 例外，返回 `ResponseEntity`）
- 新增 Agent 工具：在 `agentservice/tool/` 新建类，实现 `AgentTool` 并加 `@Component`，`AgentToolRegistry` 会自动发现；同时补对应的 `*ToolTest`
- 会真实调用模型的测试必须加 `@EnabledIfEnvironmentVariable(named = "RUN_LIVE_MODEL_TESTS", matches = "true")`，默认不跑，因为会产生费用
- 表结构改动写进 `api/mysql-schema.sql`

前端：

- 后端请求统一走 `web/src/api/*.js`，底层是 `web/src/utils/request.js`（baseURL 为 `/api`）
- 页面放 `views/`，可复用的状态逻辑放 `composables/`

跨模块（最容易漏）：

- **TipTap 扩展三处同步**：`web/src/editor/DocEditor.vue`、`collab-server/index.js` 的 extensions、`collab-server/markdown.js`。漏一处会静默丢内容
- **改后端接口后**：重新导出 `schemas/openapi.json`，并同步修改 `web/src/api/`
- **`COLLAB_AI_SECRET`** 在 api 和 collab-server 两侧必须一致，否则 Agent 读写文档一律 403

配置与安全：

- `.env` 里暂时没有值的键要**注释掉，不要留空**：空串会覆盖 `application.yml` 里的默认值
- 不提交 `.env` 和任何密钥；新增配置项时同步写进 `.env.example`

## 已知问题

- `./mvnw test` 目前有 7 个用例失败：`MonolithSecurityConfigTest`（5 个）和 `DocumentEndpointAuthenticationTest`（2 个）期望 403，实际返回 401。重组目录前就存在，见 T-012。判断改动是否引入回归时，以这 7 个之外的结果为准。

## 完成一项任务前

- 跑受影响模块的测试或构建（见「本地开发」表）
- 更新 `docs/02-todos/`；功能状态变化时更新 `docs/01-product/features.md`；做了取舍就写 ADR
- 会话结束时在 `docs/03-journal/` 新建一篇日志，并加入其 README 索引
- 出了故障或返工，在 `docs/05-postmortems/` 写复盘
