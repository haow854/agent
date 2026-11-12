/**
 * 当前激活的 TipTap 编辑器实例注册表。
 *
 * 编辑器内容原来直接挂在 `.paper` 这个 contenteditable 上，页面各处都通过
 * `document.querySelector('.paper').innerHTML` 读写。换成 TipTap 之后不能再直接
 * 改 DOM（改了编辑器自身的文档状态不会更新），所以统一从这里拿实例、走编辑器 API。
 */

let activeEditor = null

export function setActiveEditor(editor) {
  activeEditor = editor
}

export function clearActiveEditor(editor) {
  if (!editor || activeEditor === editor) activeEditor = null
}

export function getActiveEditor() {
  return activeEditor
}

/** This document HTML，替代原来的 `.paper`.innerHTML */
export function getEditorHtml() {
  return activeEditor ? activeEditor.getHTML() : ''
}

/** This document纯文本，替代原来的 `.paper`.innerText */
export function getEditorText() {
  return activeEditor ? activeEditor.getText({ blockSeparator: '\n' }) : ''
}

/**
 * 整篇替换内容。
 * @param {string} html
 * @param {boolean} emitUpdate 是否触发 update 事件（远端同步回写时传 false，避免回环）
 */
export function setEditorHtml(html, emitUpdate = true) {
  if (!activeEditor) return false
  activeEditor.commands.setContent(html || '', { emitUpdate })
  return true
}

export function isEditorReady() {
  return !!activeEditor && !activeEditor.isDestroyed
}
