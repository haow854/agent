const assert = require('assert')
const { TiptapTransformer } = require('@hocuspocus/transformer')
const Y = require('yjs')
const { extensions, FRAGMENT, readMarkdown, applyEdit, overwrite, ydocFromMarkdown, upgradeLegacyContent } = require('./index')
const { markdownToNodes } = require('./markdown')

const SOURCE = {
  type: 'doc',
  content: [
    { type: 'heading', attrs: { level: 1, textAlign: 'center' }, content: [{ type: 'text', text: '2026 年第二季度总结' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '本季度整体营收较上季度增长。' }] },
    { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '区域表现' }] },
    {
      type: 'paragraph',
      content: [
        { type: 'text', text: '华东区表现最好，' },
        { type: 'text', text: '增长 12%', marks: [{ type: 'bold' }] },
        { type: 'text', text: '，主要来自新签约的三家客户。' }
      ]
    },
    {
      type: 'bulletList',
      content: [
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '华北区持平' }] }] },
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '华南区下滑 3%' }] }] }
      ]
    },
    { type: 'image', attrs: { src: 'http://minio/docs/季度图表.png' } },
    { type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '下季度计划' }] }
  ]
}

const fresh = () => TiptapTransformer.toYdoc(structuredClone(SOURCE), FRAGMENT, extensions)
const md = (doc) => readMarkdown(doc, 1, 100).markdown

// 读：模型看到的就是 markdown，图片出文件名
{
  const view = readMarkdown(fresh(), 1, 100)
  assert.strictEqual(view.markdown, [
    '# 2026 年第二季度总结',
    '本季度整体营收较上季度增长。',
    '## 区域表现',
    '华东区表现最好，**增长 12%**，主要来自新签约的三家客户。',
    '- 华北区持平\n- 华南区下滑 3%',
    '![季度图表.png](http://minio/docs/季度图表.png)',
    '## 下季度计划'
  ].join('\n\n'))
  assert.strictEqual(view.total, 7)
  assert.strictEqual(view.truncated, false)
}

// 翻页
{
  const view = readMarkdown(fresh(), 3, 2)
  assert.strictEqual(view.markdown, '## 区域表现\n\n华东区表现最好，**增长 12%**，主要来自新签约的三家客户。')
  assert.deepStrictEqual([view.from, view.to, view.truncated], [3, 4, true])
}

// 改文字
{
  const doc = fresh()
  assert.deepStrictEqual(applyEdit(doc, '下滑 3%', '小幅回调 3%'), { ok: true, blocks: 1 })
  assert.ok(md(doc).includes('- 华南区小幅回调 3%'))
  assert.ok(md(doc).includes('- 华北区持平'))
}

// 改标题级别：结构就是文本
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '## 区域表现', '### 区域表现').ok)
  assert.ok(md(doc).includes('### 区域表现'))
}

// 段落变列表项
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '本季度整体营收较上季度增长。', '- 本季度整体营收较上季度增长。').ok)
  assert.ok(md(doc).includes('- 本季度整体营收较上季度增长。'))
}

// 插一段：一个节点变两个
{
  const doc = fresh()
  assert.deepStrictEqual(
    applyEdit(doc, '## 下季度计划', '## 风险提示\n\n汇率波动。\n\n## 下季度计划'),
    { ok: true, blocks: 3 }
  )
  assert.strictEqual(readMarkdown(doc, 1, 100).total, 9)
  assert.ok(md(doc).endsWith('## 风险提示\n\n汇率波动。\n\n## 下季度计划'))
}

// 改行内标记
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '**增长 12%**', '增长 15%').ok)
  assert.ok(md(doc).includes('华东区表现最好，增长 15%，主要'))
}

// 删整块（delete 走 replacement=''）
{
  const doc = fresh()
  assert.deepStrictEqual(applyEdit(doc, '## 下季度计划', ''), { ok: true, removed: true })
  assert.strictEqual(readMarkdown(doc, 1, 100).total, 6)
}

// textAlign 这类模型看不见的属性，改完还在
{
  const doc = fresh()
  applyEdit(doc, '# 2026 年第二季度总结', '# 2026 年上半年总结')
  const heading = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[0]
  assert.strictEqual(heading.attrs.textAlign, 'center')
  assert.strictEqual(heading.attrs.level, 1)
}

// 未命中 / 不唯一 / 跨段 / 图片
{
  assert.match(applyEdit(fresh(), '不存在的文本', 'x').error, /找不到/)
  assert.match(applyEdit(fresh(), '季度', 'x').error, /请带上更多上下文/)
  assert.match(applyEdit(fresh(), '## 区域表现\n\n华东区', 'x').error, /跨越了多个段落/)
  assert.match(applyEdit(fresh(), '![季度图表.png](http://minio/docs/季度图表.png)', 'x').error,
    /图片节点/)
}

// 图片整块删除是允许的 —— 报错信息一直这么写，但守卫原来挡在删除前面
{
  const doc = fresh()
  const result = applyEdit(doc, '![季度图表.png](http://minio/docs/季度图表.png)', '')
  assert.strictEqual(result.removed, true)
  assert.ok(!readMarkdown(doc, 1, 100).markdown.includes('季度图表'))
}

// 并发：别人改了这一处 → 拒绝；改了别处 → 照常落地
{
  const doc = fresh()
  applyEdit(doc, '华北区持平', '华北区增长 1%')
  assert.match(applyEdit(doc, '华北区持平', 'x').error, /找不到/)
  assert.ok(applyEdit(doc, '下滑 3%', '小幅回调 3%').ok)
}

// ---- 表格、代码块、任务列表里的替换 ----

const RICH = [
  '# 采购清单',
  '| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |\n| 香蕉 | 5 |',
  '```js\nconst total = 8\n\nconsole.log(total)\n```',
  '- [ ] 买菜\n- [ ] 写周报'
].join('\n\n')

const richDoc = () => {
  const doc = new Y.Doc()
  Y.applyUpdate(doc, ydocFromMarkdown(RICH))
  return doc
}
const blockTypes = (doc) => (TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []).map(n => n.type)

// 一张表格、一个代码块、一个列表各算一块
{
  const view = readMarkdown(richDoc(), 1, 100)
  assert.strictEqual(view.total, 4)
  assert.deepStrictEqual(blockTypes(richDoc()), ['heading', 'table', 'codeBlock', 'taskList'])
}

// 改表格单元格，表格还是表格
{
  const doc = richDoc()
  assert.ok(applyEdit(doc, '| 苹果  | 3   |', '| 苹果  | 6   |').ok)
  assert.deepStrictEqual(blockTypes(doc), ['heading', 'table', 'codeBlock', 'taskList'])
  const table = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[1]
  assert.strictEqual(table.content[1].content[1].content[0].content[0].text, '6')
}

// 改代码块里的一行，空行和语言标记都保留
{
  const doc = richDoc()
  assert.ok(applyEdit(doc, 'const total = 8', 'const total = 9').ok)
  const code = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[2]
  assert.strictEqual(code.attrs.language, 'js')
  assert.strictEqual(code.content[0].text, 'const total = 9\n\nconsole.log(total)')
}

// 勾选任务
{
  const doc = richDoc()
  assert.ok(applyEdit(doc, '- [ ] 买菜', '- [x] 买菜').ok)
  const list = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[3]
  assert.deepStrictEqual(list.content.map(i => i.attrs.checked), [true, false])
}

// 段落换成表格：一个块变成表格块
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '本季度整体营收较上季度增长。',
    '| 季度 | 营收 |\n| --- | --- |\n| Q2 | 120 |').ok)
  assert.strictEqual(TiptapTransformer.fromYdoc(doc, FRAGMENT).content[1].type, 'table')
}

// 带转义的正文：照读到的原文替换，存下来的是原来的字符
{
  const doc = new Y.Doc()
  Y.applyUpdate(doc, ydocFromMarkdown('引用 \\[Lewis et al. 2020\\]，配置项 max\\_tasks\\_per\\_hour 默认 20'))
  assert.ok(md(doc).includes('\\[Lewis et al. 2020\\]，配置项 max\\_tasks\\_per\\_hour'))
  assert.ok(applyEdit(doc, '\\[Lewis et al. 2020\\]', '\\[arXiv:2005.11401\\]').ok)
  assert.strictEqual(TiptapTransformer.fromYdoc(doc, FRAGMENT).content[0].content[0].text,
    '引用 [arXiv:2005.11401]，配置项 max_tasks_per_hour 默认 20')
}

// 替换文本里的 $& 原样写入
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '华北区持平', '华北区 $& 持平').ok)
  const list = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[4]
  assert.strictEqual(list.content[0].content[0].content[0].text, '华北区 $& 持平')
}

// 不成对的标记按字面文字写入，不报错也不吞字
{
  const doc = fresh()
  assert.ok(applyEdit(doc, '本季度整体营收较上季度增长。', '本季度**营收增长').ok)
  const text = TiptapTransformer.fromYdoc(doc, FRAGMENT).content[1].content.map(n => n.text).join('')
  assert.strictEqual(text, '本季度**营收增长')
}

// ---- 段落里存着 Markdown 原文的文档 ----

// 按段落存原文的协同状态：表格、代码块、四级标题都还是普通段落
const legacyDoc = () => TiptapTransformer.toYdoc({
  type: 'doc',
  content: [
    { type: 'paragraph', content: [{ type: 'text', text: '# 标题\n紧跟的正文' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '#### 四级标题' }] },
    // 中间有空行的代码块被拆成了两段
    { type: 'paragraph', content: [{ type: 'text', text: '```js\nconst a = 1' }] },
    { type: 'paragraph', content: [{ type: 'text', text: 'console.log(a)\n```' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '普通的一段，' }, { type: 'text', text: '加粗', marks: [{ type: 'bold' }] }] },
    {
      type: 'bulletList',
      content: [
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '[ ] 买菜' }] }] },
        { type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '[x] 写周报' }] }] }
      ]
    },
    // 编辑器里做出来的嵌套列表，原文还原不了，必须原样保留
    {
      type: 'bulletList',
      content: [{
        type: 'listItem',
        content: [
          { type: 'paragraph', content: [{ type: 'text', text: '水果' }] },
          { type: 'bulletList', content: [{ type: 'listItem', content: [{ type: 'paragraph', content: [{ type: 'text', text: '苹果' }] }] }] }
        ]
      }]
    }
  ]
}, FRAGMENT, extensions)

{
  const doc = legacyDoc()
  const result = upgradeLegacyContent(doc)
  assert.deepStrictEqual(result, { marked: true, upgraded: 1 })
  assert.deepStrictEqual(blockTypes(doc),
    ['heading', 'paragraph', 'table', 'heading', 'codeBlock', 'paragraph', 'taskList', 'bulletList'])
  const nodes = TiptapTransformer.fromYdoc(doc, FRAGMENT).content
  assert.strictEqual(nodes[3].attrs.level, 4)
  // 拆开的代码块拼回一个，空行保留
  assert.strictEqual(nodes[4].content[0].text, 'const a = 1\n\nconsole.log(a)')
  // 行内标记照旧
  assert.deepStrictEqual(nodes[5].content[1].marks.map(m => m.type), ['bold'])
  // 嵌套列表原样保留
  assert.strictEqual(nodes[7].content[0].content[1].type, 'bulletList')
  // 第二次加载什么都不做
  assert.deepStrictEqual(upgradeLegacyContent(doc), { marked: false, upgraded: 0 })
}

// 带着旧状态重连的客户端不会把旧段落带回来
{
  const server = legacyDoc()
  const client = new Y.Doc()
  Y.applyUpdate(client, Y.encodeStateAsUpdate(server))
  upgradeLegacyContent(server)
  Y.applyUpdate(server, Y.encodeStateAsUpdate(client))
  Y.applyUpdate(client, Y.encodeStateAsUpdate(server))
  assert.deepStrictEqual(blockTypes(server), blockTypes(client))
  assert.strictEqual(blockTypes(server).length, 8)
}

// 按现行规则建成的文档带着标记：段落里字面上的 *星号* 不会被当成斜体
{
  const doc = new Y.Doc()
  Y.applyUpdate(doc, ydocFromMarkdown('字面上的 \\*星号\\*'))
  assert.deepStrictEqual(upgradeLegacyContent(doc), { marked: false, upgraded: 0 })
  assert.strictEqual(TiptapTransformer.fromYdoc(doc, FRAGMENT).content[0].content[0].text, '字面上的 *星号*')
}

// ---- 整篇替换（版本恢复） ----

{
  const doc = fresh()
  const before = md(doc)
  const result = overwrite(doc, '# 旧版标题\n\n| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |')
  assert.deepStrictEqual(result, { ok: true, previous: before })
  assert.deepStrictEqual(blockTypes(doc), ['heading', 'table'])
  // 恢复出来的内容按现行规则建成，下次加载不再升级
  assert.deepStrictEqual(upgradeLegacyContent(doc), { marked: false, upgraded: 0 })
}

// 打开着的编辑器收到新内容；带着旧状态重连也不会把旧内容带回来
{
  const server = fresh()
  const client = new Y.Doc()
  Y.applyUpdate(client, Y.encodeStateAsUpdate(server))
  overwrite(server, '恢复后的正文')
  Y.applyUpdate(server, Y.encodeStateAsUpdate(client))
  Y.applyUpdate(client, Y.encodeStateAsUpdate(server))
  assert.strictEqual(md(server), '恢复后的正文')
  assert.strictEqual(md(client), '恢复后的正文')
}

// 恢复成空版本：留一个空段落
{
  const doc = fresh()
  overwrite(doc, '')
  assert.deepStrictEqual(blockTypes(doc), ['paragraph'])
}

console.log('applyEdit / readMarkdown / upgradeLegacyContent / overwrite: 全部断言通过')
