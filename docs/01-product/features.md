# 功能地图

> 下一个编号：F-18。规划中的功能写好 spec 后，在「设计 / 备注」列链接 `specs/` 下的文件。

状态：✅ 已完成 · 🚧 进行中 · 📋 已规划 · 💡 想法 · 🟡 部分完成 · ❌ 已放弃

| ID | 模块 | 功能 | 状态 | 设计 / 备注 |
| --- | --- | --- | --- | --- |
| F-01 | AI 文档处理 | 通过对话创建、列表、读取、替换、删除文档 | ✅ | `api/.../agentservice/tool/` |
| F-02 | AI 文档处理 | 文档版本列表与恢复 | ✅ | |
| F-03 | 实时协同 | TipTap + Yjs + Hocuspocus 多人编辑 | ✅ | `collab-server/` |
| F-04 | 对话 | 会话与消息持久化、多轮对话 | ✅ | |
| F-05 | 对话 | 上下文清理与自动压缩 | ✅ | `AutoCompactor`、`ContextManager` |
| F-06 | 对话 | 长期记忆（remember / forget） | ✅ | |
| F-07 | Agent 执行 | ReAct 循环、同批工具并发执行 | ✅ | `ReactRunner`、`ToolBatchExecutor` |
| F-08 | Agent 执行 | RabbitMQ 异步任务、WebSocket 进度推送 | ✅ | |
| F-09 | Agent 执行 | 任务审批、取消、续接 | ✅ | `ApprovalPrompt.vue` |
| F-10 | Agent 执行 | 子 Agent | ✅ | `SubAgentTool` |
| F-11 | 外部信息 | MCP 网页搜索与抓取 | ✅ | |
| F-12 | 文档协作 | 评论与批注 | 🟡 | 仅后端接口，前端未接入（T-009） |
| F-13 | 基础设施 | 文件上传下载（MinIO） | ✅ | |
| F-14 | 基础设施 | 注册登录、JWT 鉴权 | 🟡 | WebSocket 握手未鉴权（T-001） |
| F-15 | 运维 | AIOps 指标、故障检测与告警 | ✅ | `/aiops` 页面 |
| F-16 | 质量 | 黑盒评测 | ✅ | `eval/` |
| F-17 | 基础设施 | 云服务器部署，可公网访问 | 🚧 | T-005、T-006 |
