/**
 * 文档内容用到的 TipTap 扩展。协同状态的结构和 Markdown 的读写规则都从这份列表来。
 *
 * 必须与 web/src/editor/DocEditor.vue 的扩展列表一致：编辑器里多出来的节点，
 * 这边既转不成 Markdown，也建不出协同状态，Agent 读写时会丢掉。
 */
const StarterKit = require('@tiptap/starter-kit').default
const Image = require('@tiptap/extension-image').default
const TextAlign = require('@tiptap/extension-text-align').default
const { TableKit } = require('@tiptap/extension-table')
const { TaskList, TaskItem } = require('@tiptap/extension-list')

const extensions = [
  // 撤销栈属于各个编辑器自己，服务端不需要
  StarterKit.configure({ undoRedo: false }),
  Image.configure({ inline: false, allowBase64: false }),
  TextAlign.configure({ types: ['heading', 'paragraph'] }),
  TableKit,
  TaskList,
  TaskItem.configure({ nested: true })
]

module.exports = { extensions }
