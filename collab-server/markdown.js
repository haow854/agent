/**
 * ProseMirror JSON ↔ markdown。模型只看 markdown，HTML 和 PM JSON 都不出这一层。
 *
 * 往 TipTap 加扩展时，这三处必须一起改，漏一处就是静默丢内容：
 *   1. web/src/editor/DocEditor.vue —— 编辑器的扩展列表
 *   2. index.js 的 extensions      —— 序列化用，与上面同构
 *   3. 本文件的 toMarkdown / markdownToNodes
 * 本文件覆盖不到的节点会退化成纯文本，反向也造不出来。
 */

const fileName = (src) => String(src || '').split(/[/\\]/).pop() || 'image'

function inlineToMarkdown(content) {
  return (content || []).map(n => {
    if (n.type === 'hardBreak') return '\n'
    if (n.type !== 'text') return ''
    let t = n.text || ''
    for (const m of n.marks || []) {
      if (m.type === 'bold') t = `**${t}**`
      else if (m.type === 'italic') t = `*${t}*`
      else if (m.type === 'strike') t = `~~${t}~~`
      else if (m.type === 'code') t = '`' + t + '`'
      // markdown 没有下划线，借 HTML 标签；不写出来的话工具栏加的下划线
      // 模型读不到，而它改到那一段时会连着一起丢掉
      else if (m.type === 'underline') t = `<u>${t}</u>`
      else if (m.type === 'link') t = `[${t}](${m.attrs?.href || ''})`
    }
    return t
  }).join('')
}

const itemToMarkdown = (li) =>
  (li.content || []).map(p => inlineToMarkdown(p.content)).join(' ')

function toMarkdown(node) {
  switch (node.type) {
    case 'heading':
      return '#'.repeat(node.attrs?.level || 1) + ' ' + inlineToMarkdown(node.content)
    case 'codeBlock':
      return '```\n' + inlineToMarkdown(node.content) + '\n```'
    case 'blockquote':
      return (node.content || []).map(c => '> ' + inlineToMarkdown(c.content)).join('\n')
    case 'bulletList':
      return (node.content || []).map(li => '- ' + itemToMarkdown(li)).join('\n')
    case 'orderedList':
      return (node.content || []).map((li, i) => `${i + 1}. ` + itemToMarkdown(li)).join('\n')
    case 'horizontalRule':
      return '---'
    case 'image':
      return `![${node.attrs?.alt || fileName(node.attrs?.src)}](${node.attrs?.src || ''})`
    default:
      return inlineToMarkdown(node.content)
  }
}

const INLINE = /\*\*([^*]+)\*\*|\*([^*]+)\*|~~([^~]+)~~|`([^`]+)`|\[([^\]]+)\]\(([^)]*)\)|<u>([^<]+)<\/u>/g

function markdownToInline(text) {
  const out = []
  let at = 0
  for (const m of text.matchAll(INLINE)) {
    if (m.index > at) out.push({ type: 'text', text: text.slice(at, m.index) })
    const [full, bold, italic, strike, code, linkText, href, underline] = m
    if (bold !== undefined) out.push({ type: 'text', text: bold, marks: [{ type: 'bold' }] })
    else if (italic !== undefined) out.push({ type: 'text', text: italic, marks: [{ type: 'italic' }] })
    else if (strike !== undefined) out.push({ type: 'text', text: strike, marks: [{ type: 'strike' }] })
    else if (code !== undefined) out.push({ type: 'text', text: code, marks: [{ type: 'code' }] })
    else if (underline !== undefined) out.push({ type: 'text', text: underline, marks: [{ type: 'underline' }] })
    else out.push({ type: 'text', text: linkText, marks: [{ type: 'link', attrs: { href } }] })
    at = m.index + full.length
  }
  if (at < text.length) out.push({ type: 'text', text: text.slice(at) })
  return out
}

const listItem = (text) => ({
  type: 'listItem',
  content: [{ type: 'paragraph', content: markdownToInline(text) }]
})

/** 一段 markdown（可能是多个块，空行分隔）→ PM JSON 节点数组。 */
function markdownToNodes(md) {
  return md.split(/\n{2,}/).map(chunk => chunk.trim()).filter(Boolean).map(chunk => {
    const lines = chunk.split('\n')
    const heading = /^(#{1,3})\s+(.*)$/.exec(chunk)
    if (heading && lines.length === 1) {
      return {
        type: 'heading',
        attrs: { level: heading[1].length },
        content: markdownToInline(heading[2])
      }
    }
    if (chunk === '---') return { type: 'horizontalRule' }
    // 必须在段落兜底之前拦下：Image 配的是 inline: false，图片永远是独立块，
    // 而落到 markdownToInline 的话 INLINE 会把 [alt](src) 认成链接、前面的 ! 变成正文
    const image = /^!\[([^\]]*)\]\(([^)]*)\)$/.exec(chunk)
    if (image) {
      return { type: 'image', attrs: { src: image[2], alt: image[1] || null } }
    }
    if (lines.every(l => /^-\s+/.test(l))) {
      return { type: 'bulletList', content: lines.map(l => listItem(l.replace(/^-\s+/, ''))) }
    }
    if (lines.every(l => /^\d+\.\s+/.test(l))) {
      return { type: 'orderedList', content: lines.map(l => listItem(l.replace(/^\d+\.\s+/, ''))) }
    }
    if (lines.every(l => /^>\s?/.test(l))) {
      return {
        type: 'blockquote',
        content: lines.map(l => ({ type: 'paragraph', content: markdownToInline(l.replace(/^>\s?/, '')) }))
      }
    }
    return { type: 'paragraph', content: markdownToInline(chunk) }
  })
}

module.exports = { toMarkdown, markdownToNodes }
