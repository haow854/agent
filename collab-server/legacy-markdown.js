/**
 * 读出段落里存着的 Markdown 原文。
 *
 * 有些文档的协同状态里，表格、代码块、四级以下标题这类内容是以 Markdown 原文的形式
 * 存在普通段落（或列表项、引用）里的。这里把这些块还原成原文，交给
 * index.js 的 upgradeLegacyContent 重新解析。
 *
 * 只处理能原样还原的块：段落、标题、列表项只有一个段落的列表、引用，且只含文字
 * 和常见行内标记。其余返回 null，保持原样，避免把编辑器里做出的嵌套结构压平。
 */
const INLINE_MARKS = new Set(['bold', 'italic', 'strike', 'code', 'underline', 'link'])

const isPlainInline = (content) =>
  (content || []).every(n => n.type === 'text' && (n.marks || []).every(m => INLINE_MARKS.has(m.type)))

function inlineToMarkdown(content) {
  return (content || []).map(n => {
    let t = n.text || ''
    for (const m of n.marks || []) {
      if (m.type === 'bold') t = `**${t}**`
      else if (m.type === 'italic') t = `*${t}*`
      else if (m.type === 'strike') t = `~~${t}~~`
      else if (m.type === 'code') t = '`' + t + '`'
      else if (m.type === 'underline') t = `<u>${t}</u>`
      else if (m.type === 'link') t = `[${t}](${m.attrs?.href || ''})`
    }
    return t
  }).join('')
}

const singleParagraph = (block) =>
  block.content?.length === 1 && block.content[0].type === 'paragraph' && isPlainInline(block.content[0].content)

/** 一个顶层节点 → 当初的 Markdown 原文；不能原样还原时返回 null。 */
function legacyToMarkdown(node) {
  switch (node.type) {
    case 'paragraph':
      return isPlainInline(node.content) ? inlineToMarkdown(node.content) : null
    case 'heading':
      return isPlainInline(node.content)
        ? '#'.repeat(node.attrs?.level || 1) + ' ' + inlineToMarkdown(node.content)
        : null
    case 'bulletList':
    case 'orderedList': {
      const items = node.content || []
      if (!items.every(singleParagraph)) return null
      return items.map((li, i) =>
        (node.type === 'bulletList' ? '- ' : `${i + 1}. `) + inlineToMarkdown(li.content[0].content)
      ).join('\n')
    }
    case 'blockquote': {
      const paragraphs = node.content || []
      if (!paragraphs.every(p => p.type === 'paragraph' && isPlainInline(p.content))) return null
      return paragraphs.map(p => '> ' + inlineToMarkdown(p.content)).join('\n')
    }
    default:
      return null
  }
}

module.exports = { legacyToMarkdown }
