# 待办看板

> 下一个编号：T-035

## 🚧 进行中

- [ ] T-033 HTTPS：Let's Encrypt 的 IP 证书，compose 里常驻 certbot 签发和续期，nginx 按证书是否存在切换 HTTP/HTTPS（[spec F-17](../01-product/specs/F-17-deployment.md)、[ADR-0006](../04-adr/0006-https-ip-certificate.md)）。配置已写完，本地用自签证书验证过切换和续期后的重新加载，整套服务在 HTTPS 下演练通过；服务器上首次部署后证书签下来，web 245 秒后自动切到 HTTPS。还差：看到一次自动续期（约 3 天后，`docker compose logs certbot`）
- [ ] T-005 服务器初始化与首次部署：腾讯云轻量应用服务器 2 核 4G（上海），按 [spec F-17](../01-product/specs/F-17-deployment.md) 初始化并部署。已部署，`https://<服务器IP>` 可访问，不需要登录的公网检查全部通过（证书可信、h2、http 跳 https、内部端口不可达、接口文档和监控端点不对外、注册被拒、进度推送和协同经 wss 可达）；登录后两个标签页实时协同、Agent 联网搜索并改文档（进度实时显示，改动实时出现在另一个标签页并回存）、上传文件都通过。还差：服务器重启后自动恢复、线上让 Agent 读一次网页
## ⏭️ 下一步

<!-- 已经想清楚、随时可以开工的事，按优先级排序 -->

- [ ] T-034 部署只上传有变化的部分：后端 jar 有 146 MB（依赖都打在里面），从开发机上传约 65 KB/s，首次部署光上传就要 40 分钟。改成 Spring Boot 分层 jar，依赖层不变时不重复上传（F-17、ADR-0003）

- [ ] T-016 Agent 任务串行执行：`ai.model.request.queue` 实际只有 1 个消费者，一个长任务会让所有用户的任务排队（F-08）
- [ ] T-017 取消任务：前端没有取消入口；取消后对话一直显示 Working；取消只在主循环下一轮生效，已派出的子 Agent 会继续跑完（F-09、F-10）
- [ ] T-019 AIOps 页面：后端指标按时间窗口分组返回，概览卡片按顶层键读取，全部显示 0；指标表直接显示原始 JSON；普通用户打开页面会弹「仅管理员可访问」（F-15）
- [ ] T-008 补齐 01-product/README.md 的目标用户、设计原则、非目标（需要本人确认产品方向）
- [ ] T-012 修复 7 个安全相关测试：`MonolithSecurityConfigTest`、`DocumentEndpointAuthenticationTest` 期望 403 实际 401。先确认哪个是对的：未登录返回 401 更符合语义，可能该改测试而不是改代码
- [ ] T-002 Swagger 按 profile 开关，生产环境默认关闭（见 `MonolithSecurityConfig.java` 中的 TODO）

## 🗂️ 待排期

<!-- 想做但还没排上，或还需要先写 spec 的事 -->

- [ ] T-009 前端接入评论与批注（F-12，跨前后端，需先写 spec）
- [ ] T-010 补录既有架构决策：ReAct 执行模型、RabbitMQ 异步任务、Agent 通过协同服务编辑文档、存储分层（MySQL / Redis / MinIO）
- [ ] T-011 发布版本：在 GitHub 上打 tag（如 `v1.0.0`），附带部署说明或打包产物
- [ ] T-013 CI 构建镜像：GitHub Actions 构建镜像并推送到镜像仓库，服务器只拉镜像部署，线上版本对应到提交（ADR-0003）
- [ ] T-014 监控：Prometheus + Grafana 采集后端指标。需要先解决 `/actuator/prometheus` 的访问鉴权，且 4G 机器内存不够
- [ ] T-027 `FileServiceImpl.delete` 找不到元数据时按扩展名猜文件名去删，删不到也返回成功；去掉这个兜底，如实报错（F-13，[复盘](../05-postmortems/2026-11-14-purge-missed-original-files.md)）

## 📥 收件箱

<!-- 随手记的想法、发现的问题，未经整理 -->

- 文档里插入的图片显示不出来：图片地址 `/api/files/preview/<id>` 要求登录，浏览器加载 `<img>` 不带令牌，返回 401
- 文档侧栏和挂载文档的聊天提示仅操作当前文档，但前端未传 `workspace`，请求实际默认 `all`；核对预期后同步作用域参数与界面文案
- 前端主包 1.2 MB、编辑器包 540 KB（`npm run build` 有体积警告），可考虑拆包
- 编辑器左侧字数统计在输入时不更新
- 部分早期文档的正文丢了换行，Markdown 符号连成一行显示：
  - 团建活动方案、年终总结写作指南：版本 1 存有完整原文，可以用版本恢复
  - bert、llama2、chinchilla、test.docx：版本 1 就只有一行，数据里找不回结构
- 文档里插入的图片不随文档删除（同一张图片可能被复制进了别的文档），没有文档引用的图片会一直留在存储里
- 请求参数缺失或 JSON 格式错误时返回 500，应为 400
- 评测 conv-08（deepseek-v4.1-flash-1017）：Agent 删了两篇文档，其中一篇不该删；评测 create-04 检查时任务还在 running，没生成文档
- 评测 edit-06 没配置审批：Agent 选用需要审批的 `delete_text` 时任务停在 action_required，直接判失败；选用 `replace_text` 时通过

