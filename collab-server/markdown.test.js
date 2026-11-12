const assert = require('assert')
const { toMarkdown, markdownToNodes } = require('./markdown')

const roundTrip = (md) => markdownToNodes(md).map(toMarkdown).join('\n\n')

const cases = [
  '# 2026 年第二季度总结',
  '## 区域表现',
  '### 三级标题',
  '本季度整体营收较上季度增长。',
  '华东区表现最好，**增长 12%**，主要来自新签约的三家客户。',
  '带*斜体*和~~删除线~~和`代码`。',
  '看[这个链接](https://example.com)。',
  '- 华北区持平\n- 华南区下滑 3%',
  '1. 第一项\n2. 第二项',
  '> 引用一行',
  '---',
  '带<u>下划线</u>的一段。',
  '![季度图表.png](http://minio/bucket/季度图表.png)',
  '# 标题\n\n正文一段\n\n- 列表项\n\n![图.png](http://minio/图.png)'
]

for (const md of cases) {
  assert.strictEqual(roundTrip(md), md, `往返失败:\n  期望 ${JSON.stringify(md)}\n  实际 ${JSON.stringify(roundTrip(md))}`)
}

// 没有 alt 时拿文件名兜底，再解析回来 alt 就落定，第二轮起稳定
assert.strictEqual(
  toMarkdown({ type: 'image', attrs: { src: 'http://minio/bucket/季度图表.png' } }),
  '![季度图表.png](http://minio/bucket/季度图表.png)'
)

// 图片必须是独立块节点，不能被当成段落里的一个链接
const imageNodes = markdownToNodes('![图](http://minio/a.png)')
assert.strictEqual(imageNodes.length, 1)
assert.strictEqual(imageNodes[0].type, 'image')
assert.strictEqual(imageNodes[0].attrs.src, 'http://minio/a.png')

// 下划线走 HTML 标签，mark 要认出来
const underlined = markdownToNodes('带<u>下划线</u>的。')[0]
assert.deepStrictEqual(underlined.content[1].marks, [{ type: 'underline' }])
assert.strictEqual(underlined.content[1].text, '下划线')

// 混合标记的段落结构正确
const nodes = markdownToNodes('前面**粗**中间*斜*后面')
assert.strictEqual(nodes.length, 1)
assert.deepStrictEqual(nodes[0].content.map(n => n.text), ['前面', '粗', '中间', '斜', '后面'])
assert.deepStrictEqual(nodes[0].content[1].marks, [{ type: 'bold' }])
assert.deepStrictEqual(nodes[0].content[3].marks, [{ type: 'italic' }])

// 标记不成对时按字面文本保留，不吞字符
assert.strictEqual(roundTrip('前面**粗中间'), '前面**粗中间')

// 归一化漂移才是往返守卫要拦的：applyEdit 比对不上就拒绝这次替换
assert.notStrictEqual(roundTrip('  首尾有空白  '), '  首尾有空白  ')

console.log(`markdown codec: ${cases.length + 10} 项断言通过`)
