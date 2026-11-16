# ADR-0005：网页读取由 Tavily 的 tavily_extract 下载网页

- **状态**：已采纳
- **日期**：2026-11-14
- **关联**：F-11、F-17、T-032

## 背景

Agent 的 `web_fetch` 读取模型指定的网页。模型会受提示注入影响（网页、文档里埋的指令），所以要读的地址不可信。

后端和 MySQL、Redis、MinIO、协同服务在同一个 Docker 网络里，所在的云服务器还有元数据接口（腾讯云是 `169.254.0.23`）。网页只要是从本机下载的，任何一跳指向内网就是 SSRF：

- 只校验起始地址拦不住跳转：公网网页返回 `302`，`Location` 指向内网
- 校验时和下载时各解析一次域名，拦不住 DNS 重绑定：第一次返回公网 IP，第二次返回内网 IP

所以要么在真正连接的那一刻检查目标 IP，要么不从本机下载。

## 备选方案

### 本地 `mcp-server-fetch` 加 squid 出口代理

`mcp-server-fetch` 以 stdio 子进程运行，启动参数 `--proxy-url` 指向 compose 里的 squid，squid 用 `dst` ACL 拒绝内网网段。squid 自己解析域名并按解析结果连接，每一跳都经过它。

- 优点：抓取仍走 MCP；跳转和 DNS 重绑定都在代理一层拦住
- 缺点：
  - 多一个容器
  - 只在 compose 部署里生效，本地开发没有这层保护
  - 镜像里要装 Python 运行 `mcp-server-fetch`

### 后端用 OkHttp 下载，每一跳连接后检查 IP

network interceptor 在每一跳 TCP 连接建立之后、HTTP 请求发出之前执行，检查 `route().socketAddress()`，内网地址直接中止。HTML 用 jsoup 和 flexmark 转成 markdown。

- 优点：不依赖外部服务，免费；本地和线上一样受保护
- 缺点：
  - 不走 MCP
  - 自己维护下载、编码识别、正文提取和 HTML 转换
  - 国内服务器访问部分境外网站会失败

### Tavily 的 `tavily_extract`

联网搜索已经接了 Tavily 的远程 MCP（Streamable HTTP），同一个服务提供 `tavily_extract`：传入 URL，返回正文 markdown。

- 优点：
  - 请求从 Tavily 的服务器发出，碰不到本机所在的内网
  - 和 `web_search` 共用一个 MCP 连接和配置，不用另外部署
  - 境外网站从 Tavily 那边基本都能读
- 缺点：
  - 消耗 Tavily 额度
  - 依赖 Tavily 可用，国内服务器要能连上 `mcp.tavily.com`
  - 屏蔽境外 IP 或反爬严格的国内网站可能读不到

## 决定

`web_fetch` 调 `tavily_extract`，网页不从本机下载。搜索已经依赖 Tavily，读取跟着用同一个服务，SSRF 问题直接消失，不用在本机维护一套防护。

## 影响

- 后端镜像里不再有 Python，也不再启动 MCP 子进程
- 搜索服务换成 Tavily 以外的 MCP 后，`web_fetch` 不可用
- Tavily 一次返回整页，`start_index`/`max_length` 分页在后端切
- 读不到的网页 Tavily 不在 MCP 结果里标记出错，原因写在返回的 JSON（`failed_results` 或 `error`）里，后端按内容判断
