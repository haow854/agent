/**
 * 证明这次改动的意义：存二进制能让断线期间的编辑合并回来，存 markdown 不能。
 * 两条路径除了持久化格式完全一样，所以差异只可能来自它。
 */
const assert = require('assert')
const Y = require('yjs')
const { TiptapTransformer } = require('@hocuspocus/transformer')
const { toMarkdown, markdownToNodes } = require('./markdown')
const { extensions, FRAGMENT, readMarkdown } = require('./index')

const SOURCE = {
  type: 'doc',
  content: [
    { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '季度总结' }] },
    { type: 'paragraph', content: [{ type: 'text', text: '营收增长。' }] }
  ]
}

// 服务端此刻的文档，以及一个客户端在断网前拿到的同一份副本
const server = TiptapTransformer.toYdoc(structuredClone(SOURCE), FRAGMENT, extensions)
const offline = new Y.Doc()
Y.applyUpdate(offline, Y.encodeStateAsUpdate(server))

// 断网期间这个客户端继续编辑，攒下一条更新
const before = Y.encodeStateVector(offline)
offline.getXmlFragment(FRAGMENT).get(1).get(0).insert(4, '很快')
const lateUpdate = Y.encodeStateAsUpdate(offline, before)

const restoreFromYdoc = () => {
  const doc = new Y.Doc()
  Y.applyUpdate(doc, Y.encodeStateAsUpdate(server))   // 存的是二进制
  return doc
}

const restoreFromMarkdown = () => {
  const nodes = TiptapTransformer.fromYdoc(server, FRAGMENT).content || []
  const markdown = nodes.map(toMarkdown).join('\n\n')
  return TiptapTransformer.toYdoc(
      { type: 'doc', content: markdownToNodes(markdown) }, FRAGMENT, extensions)
}

// 所有人断开、文档被逐出内存，随后被重建 —— 然后那条迟到的更新到了
{
  const doc = restoreFromYdoc()
  Y.applyUpdate(doc, lateUpdate)
  assert.strictEqual(readMarkdown(doc, 1, 10).markdown, '# 季度总结\n\n营收增长很快。',
    '从二进制恢复后，断线期间的编辑应当合并进来')
}

{
  const doc = restoreFromMarkdown()
  Y.applyUpdate(doc, lateUpdate)
  assert.strictEqual(readMarkdown(doc, 1, 10).markdown, '# 季度总结\n\n营收增长。',
    '从 markdown 重建后字符身份全新，迟到的更新进了待办队列，永远整合不进来')
}

// 内容本身两条路都恢复得出来 —— 丢的只是身份，所以这个 bug 平时看不见
assert.strictEqual(readMarkdown(restoreFromYdoc(), 1, 10).markdown,
                   readMarkdown(restoreFromMarkdown(), 1, 10).markdown)

console.log('持久化: 二进制保住了 CRDT 身份，markdown 丢失（3 项断言通过）')
