# 功能地图

> 下一个编号：F-18。规划中的功能写好 spec 后，在「设计 / 备注」列链接 `specs/` 下的文件。

状态：✅ 已完成 · 🚧 进行中 · 📋 已规划 · 💡 想法 · 🟡 部分完成 · ❌ 已放弃

| ID | 模块 | 功能 | 状态 | 设计 / 备注 |
| --- | --- | --- | --- | --- |
| F-01 | AI 文档处理 | 通过对话创建、列表、读取、替换、删除文档 | ✅ | `api/.../agentservice/tool/`；删除即永久删除，正文、协同状态、版本、原始文件、授权、评论、批注一起删，只有所有者能删 |
| F-02 | AI 文档处理 | 文档版本列表与恢复 | ✅ | [spec](specs/F-02-version-history.md)：版本正文存 MinIO，恢复经协同服务 `/ai/overwrite` 整篇替换 |
| F-03 | 文档编辑 | 富文本编辑器：Markdown 内容的显示与编辑（TipTap），多人实时协同（Yjs + Hocuspocus） | ✅ | `web/src/editor/`、`collab-server/`；[spec](specs/F-03-markdown.md)、ADR-0004 |
| F-04 | 对话 | 会话与消息持久化、多轮对话 | ✅ | |
| F-05 | 对话 | 上下文清理与自动压缩 | ✅ | `AutoCompactor`、`ContextManager` |
| F-06 | 对话 | 长期记忆（remember / forget） | ✅ | |
| F-07 | Agent 执行 | ReAct 循环、同批工具并发执行 | ✅ | `ReactRunner`、`ToolBatchExecutor` |
| F-08 | Agent 执行 | RabbitMQ 异步任务、WebSocket 进度推送 | 🟡 | 任务串行执行（T-016） |
| F-09 | Agent 执行 | 任务审批、取消、续接 | 🟡 | `ApprovalPrompt.vue`；取消无前端入口（T-017） |
| F-10 | Agent 执行 | 子 Agent | ✅ | `SubAgentTool` |
| F-11 | 外部信息 | MCP 网页搜索与读取 | ✅ | 都走 Tavily 的远程 MCP（`tavily_search`、`tavily_extract`），网页不从本机下载（ADR-0005） |
| F-12 | 文档协作 | 评论与批注 | 🟡 | 仅后端接口，前端未接入（T-009） |
| F-13 | 基础设施 | 文件上传下载（MinIO） | ✅ | |
| F-14 | 基础设施 | 注册登录、JWT 鉴权 | ✅ | 令牌区分访问和刷新，进度推送在 STOMP 的 CONNECT 帧上鉴权；线上后端拒绝注册，演示账号在 MySQL 初始化时写入 |
| F-15 | 运维 | AIOps 指标、故障检测与告警 | 🟡 | `/aiops` 页面指标显示错误（T-019） |
| F-16 | 质量 | 黑盒评测 | ✅ | `eval/` |
| F-17 | 基础设施 | 云服务器部署，可公网访问 | 🚧 | [spec](specs/F-17-deployment.md)、T-005、T-006 |