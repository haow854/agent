const assert = require('assert')
const { toMarkdown, markdownToNodes } = require('./markdown')

const roundTrip = (md) => markdownToNodes(md).map(toMarkdown).join('\n\n')
const types = (md) => markdownToNodes(md).map(n => n.type)
let checks = 0
const check = (fn) => { fn(); checks++ }

// 规范写法原样往返
const canonical = [
  '# 2026 年第二季度总结',
  '## 区域表现',
  '### 三级标题',
  '#### 四级标题',
  '###### 六级标题',
  '本季度整体营收较上季度增长。',
  '华东区表现最好，**增长 12%**，主要来自新签约的三家客户。',
  '带*斜体*和~~删除线~~和`代码`。',
  '***粗斜体***',
  '看[这个链接](https://example.com)。',
  '- 华北区持平\n- 华南区下滑 3%',
  '1. 第一项\n2. 第二项',
  '- 水果\n  - 苹果\n  - 香蕉',
  '- [ ] 买菜\n- [x] 写周报',
  '```js\nconst a = 1\n\nconsole.log(a)\n```',
  '> 引用一行',
  '> - 引用里的列表',
  '---',
  '带<u>下划线</u>的一段。',
  '![季度图表.png](http://minio/bucket/季度图表.png)',
  '# 标题\n\n正文一段\n\n- 列表项\n\n![图.png](http://minio/图.png)'
]
for (const md of canonical) {
  check(() => assert.strictEqual(roundTrip(md), md,
    `往返失败:\n  期望 ${JSON.stringify(md)}\n  实际 ${JSON.stringify(roundTrip(md))}`))
}

// 每种元素解析成对应的节点
check(() => assert.deepStrictEqual(types('# 标题\n正文第一行'), ['heading', 'paragraph']))
check(() => assert.deepStrictEqual(types('#### 四级标题'), ['heading']))
check(() => assert.deepStrictEqual(types('* 苹果\n* 香蕉'), ['bulletList']))
check(() => assert.deepStrictEqual(types('- [ ] 买菜'), ['taskList']))
check(() => assert.deepStrictEqual(types('```\na\n\nb\n```'), ['codeBlock']))
check(() => assert.deepStrictEqual(types('| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |'), ['table']))
check(() => assert.deepStrictEqual(types('- 一\n- 二\n\n说明文字'), ['bulletList', 'paragraph']))

// 代码块保留语言标记，块内空行不拆块
check(() => {
  const [code] = markdownToNodes('```python\nx = 1\n\ny = 2\n```')
  assert.strictEqual(code.attrs.language, 'python')
  assert.strictEqual(code.content[0].text, 'x = 1\n\ny = 2')
})

// 任务列表的勾选状态
check(() => {
  const [list] = markdownToNodes('- [ ] 买菜\n- [x] 写周报')
  assert.deepStrictEqual(list.content.map(i => i.attrs.checked), [false, true])
})

// 表格：表头和单元格
check(() => {
  const [table] = markdownToNodes('| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |')
  assert.deepStrictEqual(table.content.map(r => r.content.map(c => c.type)),
    [['tableHeader', 'tableHeader'], ['tableCell', 'tableCell']])
  assert.strictEqual(table.content[1].content[0].content[0].content[0].text, '苹果')
})

// 不同写法归一成规范写法，再往返一次保持不变
const variants = {
  '* 苹果\n* 香蕉': '- 苹果\n- 香蕉',
  '__加粗__ 和 _斜体_': '**加粗** 和 *斜体*',
  '| 名称 | 数量 |\n| --- | --- |\n| 苹果 | 3 |': '| 名称  | 数量  |\n| --- | --- |\n| 苹果  | 3   |'
}
for (const [input, expected] of Object.entries(variants)) {
  check(() => assert.strictEqual(roundTrip(input), expected))
  check(() => assert.strictEqual(roundTrip(expected), expected))
}

// 正文里的行内符号转义，& < > 写成 HTML 实体，再解析还是原来的文字
for (const [text, expected] of [
  ['价格 3*4=12，路径 a_b_c', '价格 3\\*4=12，路径 a\\_b\\_c'],
  ['见 [arXiv:1810.04805]', '见 \\[arXiv:1810.04805\\]'],
  ['R&D 部门，a < b', 'R&amp;D 部门，a &lt; b'],
  ['字面上的 *星号*', '字面上的 \\*星号\\*']
]) {
  check(() => {
    const md = toMarkdown({ type: 'paragraph', content: [{ type: 'text', text }] })
    assert.strictEqual(md, expected)
    assert.strictEqual(markdownToNodes(md)[0].content[0].text, text)
  })
}

// 表格单元格里的竖线转义，不会被拆成两格
check(() => {
  const [table] = markdownToNodes('| 名称 | 说明 |\n| --- | --- |\n| a\\|b | 2 |')
  const md = toMarkdown(table)
  assert.strictEqual(markdownToNodes(md)[0].content[1].content[0].content[0].content[0].text, 'a|b')
  assert.strictEqual(markdownToNodes(md)[0].content[1].content.length, 2)
})

// 标记不成对时按字面文字保留，不吞字符
check(() => {
  const [p] = markdownToNodes('前面**粗中间')
  assert.strictEqual(p.content.map(n => n.text).join(''), '前面**粗中间')
})

// 没有 alt 的图片用文件名代替
check(() => assert.strictEqual(
  toMarkdown({ type: 'image', attrs: { src: 'http://minio/bucket/季度图表.png' } }),
  '![季度图表.png](http://minio/bucket/季度图表.png)'
))

// 图片是独立的块节点
check(() => {
  const nodes = markdownToNodes('![图](http://minio/a.png)')
  assert.strictEqual(nodes.length, 1)
  assert.strictEqual(nodes[0].type, 'image')
  assert.strictEqual(nodes[0].attrs.src, 'http://minio/a.png')
})

// 下划线走 HTML 标签
check(() => {
  const [p] = markdownToNodes('带<u>下划线</u>的。')
  assert.deepStrictEqual(p.content[1].marks, [{ type: 'underline' }])
  assert.strictEqual(p.content[1].text, '下划线')
})

// 混合标记
check(() => {
  const [p] = markdownToNodes('前面**粗**中间*斜*后面')
  assert.deepStrictEqual(p.content.map(n => n.text), ['前面', '粗', '中间', '斜', '后面'])
  assert.deepStrictEqual(p.content[1].marks, [{ type: 'bold' }])
  assert.deepStrictEqual(p.content[3].marks, [{ type: 'italic' }])
})

// 空文本
check(() => assert.deepStrictEqual(markdownToNodes(''), []))

console.log(`markdown codec: ${checks} 项断言通过`)
