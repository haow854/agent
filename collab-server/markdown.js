/**
 * 编辑器 JSON ↔ Markdown。模型只看 Markdown，编辑器 JSON 和 HTML 都不出这一层。
 *
 * 读写规则来自 extensions.js 里各扩展自带的 Markdown 定义，所以支持哪些元素
 * 只取决于扩展列表：新增一种元素，在 extensions.js 和 web/src/editor/DocEditor.vue
 * 里同时加上对应扩展即可。写法按 @tiptap/markdown 的默认规则，见 ADR-0004。
 */
const { MarkdownManager } = require('@tiptap/markdown')
const { extensions } = require('./extensions')

const manager = new MarkdownManager({ extensions })

const fileName = (src) => String(src || '').split(/[/\\]/).pop() || 'image'

// 没有 alt 的图片用文件名代替，模型才知道这张图是什么
const withImageAlt = (node) =>
  node.type === 'image' && !node.attrs?.alt
    ? { ...node, attrs: { ...node.attrs, alt: fileName(node.attrs?.src) } }
    : node

/** 一个顶层节点 → Markdown。 */
function toMarkdown(node) {
  return manager.serialize({ type: 'doc', content: [withImageAlt(node)] }).trim()
}

/** 一段 Markdown（可以包含多个块）→ 顶层节点数组。 */
function markdownToNodes(md) {
  return manager.parse(md).content || []
}

module.exports = { toMarkdown, markdownToNodes }
