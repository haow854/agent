# 2026-11-11 · 工程规范化：monorepo + docs 知识库

- **目标**：完成工程升级与 Vibe Coding 初始化（T-003、T-004、T-007）
- **结果**：
  - 目录重组为 `web/`、`api/`、`collab-server/`、`schemas/`、`eval/`、`deploy/`，修正了 compose、部署脚本、`.env` 加载路径和 `.gitignore`
  - `.env` 移到仓库根目录，后端从 `.env` 或 `../.env` 读取
  - 建立编号目录式的 `docs/` 知识库：01-product、02-todos、03-journal、04-adr、05-postmortems；整理出现有功能地图（F-01 ~ F-17）
  - 新增 `AGENTS.md`（通用 AI 指南，写明 docs 各目录归属）和 `CLAUDE.md`（引用 AGENTS.md）
  - 从本地运行的后端导出 `schemas/openapi.json`（68 个接口）
  - collab-server 测试、前端构建、评测自检通过；后端 190 个测试中 183 个通过
- **决策**：[ADR-0001](../04-adr/0001-docs-driven-vibecoding.md)、[ADR-0002](../04-adr/0002-monorepo-layout.md)
- **问题**：后端 7 个安全测试失败（期望 403，实际 401）。在重组前的原始提交上复现了同样的失败，确认与本次改动无关，记为 T-012
- **遗留**：部署（T-005、T-006）等待购买服务器；产品方向部分需本人补充（T-008）
