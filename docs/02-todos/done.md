# 已完成

<!-- 格式：- [x] T-xxx 描述（YYYY-MM-DD，相关链接）。最新的在最上面。 -->

- [x] T-020 删掉走不到的文件解析：导入只接受 docx、txt、md、html，图片 OCR、PDF、`.doc`、XML 的解析分支一并删除；去掉 tess4j、poi-scratchpad 和没人用的 flexmark-all，pdfbox 只留给评测脚手架；api 镜像不再装 Tesseract。jar 从 146 MB 降到 107 MB（2026-11-15，F-13、F-17）

- [x] T-032 上线前安全修复（含 T-001），已在本地实测：进度推送只认访问令牌、只推给任务所属的用户；查别人只返回 id 和用户名；令牌区分访问和刷新；网页读取改用 Tavily 的 `tavily_extract`；只有所有者能删文档；去掉跨域配置；线上由后端拒绝注册，演示账号在 MySQL 初始化时写入（2026-11-14，[ADR-0005](../04-adr/0005-web-fetch-via-tavily.md)、[spec F-17](../01-product/specs/F-17-deployment.md)）
- [x] T-021 网页读取不再启动 MCP 子进程（改用 Tavily 的 `tavily_extract`），子进程启动失败后反复重试、刷错误日志的问题随之消失（2026-11-14，[ADR-0005](../04-adr/0005-web-fetch-via-tavily.md)）
- [x] T-006 部署配置在本地用 Docker 完整演练：7 个服务一起运行，按 spec F-17 的验收标准逐项验证（2026-11-14，[spec F-17](../01-product/specs/F-17-deployment.md)）
- [x] T-018 前端在访问令牌到期前自动刷新，请求遇到 401 先刷新再重试，刷新令牌也失效才退出登录；协同连接同步新令牌，回存不再因令牌过期失败（2026-11-14，F-14）
- [x] T-031 去掉每个用户每小时 20 个 Agent 任务的上限（2026-11-14）
- [x] T-030 前端显示 Agent 的提问（ask_user）：问题卡片带候选项，回答交回原任务继续执行；等待回答时输入框里的话当作回答，刷新后问题仍在（2026-11-14，F-09）
- [x] T-029 Agent 系统提示词按平台功能和使用场景重写：平台介绍、何时提问、编辑与删除规则、不可信内容、记忆规则，工作区加当前日期（2026-11-14，F-07）
- [x] T-028 版本正文存到 MinIO，数据库只存版本元数据；本地已有的 44 个版本已迁移（2026-11-14，[spec F-02](../01-product/specs/F-02-version-history.md)）
- [x] T-026 删除文档改为永久删除，并清理以前软删除留下的文档、文件和存储对象（2026-11-14）
- [x] T-015 版本恢复经协同服务替换正文，打开着的编辑器即时看到，回存不再覆盖恢复结果（2026-11-14，F-02）

- [x] T-025 已有文档在加载时按新的解析规则处理（2026-11-13，[spec F-03 Markdown](../01-product/specs/F-03-markdown.md)）
- [x] T-024 docx 和 html 导入：保留标题级别、转换表格、允许嵌套标记（2026-11-13，spec F-03 Markdown）
- [x] T-023 collab-server 用 `@tiptap/markdown` 实现编解码（2026-11-13，spec F-03 Markdown、[ADR-0004](../04-adr/0004-markdown-escaping.md)）
- [x] T-022 TipTap 升到 3.33，加入表格、任务列表、六级标题，编辑器改为连续页面（2026-11-13，spec F-03 Markdown）

- [x] T-007 导出 OpenAPI 定义到 `schemas/openapi.json`（2026-11-11）
- [x] T-004 建立 docs/ 知识库、AGENTS.md、CLAUDE.md（2026-11-11，[ADR-0001](../04-adr/0001-docs-driven-vibecoding.md)）
- [x] T-003 仓库升级为 monorepo：`web/`、`api/`、`collab-server/`、`schemas/`、`eval/`、`deploy/`（2026-11-11，[ADR-0002](../04-adr/0002-monorepo-layout.md)）
