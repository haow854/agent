# schemas

前后端共享的接口定义。

| 文件 | 内容 | 来源 |
| --- | --- | --- |
| `openapi.json` | 后端全部 HTTP 接口（OpenAPI 3.0） | 由 `api/` 的 springdoc 生成 |

**后端代码是唯一的事实来源**：不要手改 `openapi.json`，改了接口就重新导出。前端对接、写 spec、让 AI 理解接口时都以这里为准，不用去翻 Java 代码。

## 重新导出

后端在本地 8080 端口运行时，在仓库根目录执行：

```bash
curl -s http://localhost:8080/api-docs | python -c "import json,sys; s=json.loads(sys.stdin.buffer.read().decode('utf-8')); s.pop('servers',None); f=open('schemas/openapi.json','w',encoding='utf-8',newline='\n'); json.dump(s,f,ensure_ascii=False,indent=2); f.write('\n')"
```

去掉 `servers` 字段是因为它记录的是导出时的本机地址，和环境有关。

可视化浏览：后端运行时打开 <http://localhost:8080/swagger-ui.html>。

## 什么时候必须导出

- 新增、删除或修改了 Controller 的路径、参数、请求体、返回结构
- 提交前用 `git diff schemas/` 检查接口变化是否符合预期
