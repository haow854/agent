# 04 · 决策记录（ADR）

> 下一个编号：0007

每个有取舍的决定一个文件，用 [_template.md](_template.md) 新建，文件名 `NNNN-短标题.md`，编号递增。

决策一旦「已采纳」就不再修改正文；想推翻它，就写一个新的 ADR，并把旧的状态改成「已被 ADR-xxxx 取代」。

## 索引

| 编号 | 标题 | 状态 | 日期 |
| --- | --- | --- | --- |
| [ADR-0001](0001-docs-driven-vibecoding.md) | 用 docs/ 管理 Vibe Coding 的产品设计与决策 | 已采纳 | 2026-11-11 |
| [ADR-0002](0002-monorepo-layout.md) | 仓库按 monorepo 组织，前后端与接口定义分目录 | 已采纳 | 2026-11-11 |
| [ADR-0003](0003-single-host-compose.md) | 单机 Docker Compose 部署，构建产物在开发机生成 | 已采纳 | 2026-11-12 |
| [ADR-0004](0004-markdown-escaping.md) | Markdown 写法使用 TipTap 的默认转义 | 已采纳 | 2026-11-13 |
| [ADR-0005](0005-web-fetch-via-tavily.md) | 网页读取由 Tavily 的 tavily_extract 下载网页 | 已采纳 | 2026-11-14 |
| [ADR-0006](0006-https-ip-certificate.md) | HTTPS 先用 Let's Encrypt 的 IP 证书 | 已采纳 | 2026-11-14 |

<!-- 新增 ADR 后在上表追加一行 -->
