# ADR-0002：仓库按 monorepo 组织

- **状态**：已采纳
- **日期**：2026-11-11
- **关联**：T-003、T-007

## 背景

原结构：`docAgent/` 下混放后端源码、前端（`vue-test-app/`）、协同服务、compose 文件和部署脚本，评测在另一个顶层目录 `docagent-eval/`。

monorepo 是目前标准的工程管理方式，对 Vibe Coding 更友好：一次提交能覆盖一个完整功能，前后端可以共享接口等设计定义。

## 决定

| 目录 | 内容 | 原位置 |
| --- | --- | --- |
| `web/` | Vue 3 前端 | `docAgent/vue-test-app/` |
| `api/` | Spring Boot 后端（含 `mysql-schema.sql`、`Dockerfile`） | `docAgent/` |
| `collab-server/` | Node.js 协同服务 | `docAgent/collab-server/` |
| `schemas/` | 前后端共享的 OpenAPI 定义 | 新增 |
| `eval/` | 黑盒评测 | `docagent-eval/` |
| `deploy/` | compose、Prometheus 配置、部署脚本 | `docAgent/` |
| `docs/` | Vibe Coding 知识库 | 新增 |

约定：

- `.env` 放仓库根目录，后端从 `.env` 或 `../.env` 加载
- 表结构 `mysql-schema.sql` 留在 `api/`；`schemas/` 只放前后端共享的接口定义
- 接口定义由 springdoc 生成，导出为 `schemas/openapi.json`，去掉与环境相关的 `servers` 字段
- 根目录 `.gitattributes` 强制 `*.sh` 使用 LF

## 影响

- Maven 坐标、Java 包名不变
- IntelliJ 需要重新导入 `api/pom.xml`
- 部署脚本从仓库根目录执行：`./deploy/deploy.sh`
- 接口定义目前靠手动导出，改了接口容易忘记重新导出
