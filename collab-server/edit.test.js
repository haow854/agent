const assert = require('assert')
const { TiptapTransformer } = require('@hocuspocus/transformer')
const { extensions, FRAGMENT, readMarkdown, applyEdit } = require('./index')

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

console.log('applyEdit / readMarkdown: 全部断言通过')
