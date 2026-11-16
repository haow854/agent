<template>
  <div class="doc-editor">
    <div class="editor-toolbar" v-if="editor">
      <el-tooltip content="Bold" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('bold') }" @click="editor.chain().focus().toggleBold().run()"><b>B</b></button>
      </el-tooltip>
      <el-tooltip content="Italic" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('italic') }" @click="editor.chain().focus().toggleItalic().run()"><i>I</i></button>
      </el-tooltip>
      <el-tooltip content="Underline" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('underline') }" @click="editor.chain().focus().toggleUnderline().run()"><u>U</u></button>
      </el-tooltip>
      <el-tooltip content="Strikethrough" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('strike') }" @click="editor.chain().focus().toggleStrike().run()"><s>S</s></button>
      </el-tooltip>

      <span class="tb-divider"></span>

      <button v-for="level in [1, 2, 3]" :key="level" class="tb-btn"
              :class="{ active: isActive('heading', { level }) }"
              @click="editor.chain().focus().toggleHeading({ level }).run()">H{{ level }}</button>
      <button class="tb-btn" :class="{ active: isActive('paragraph') }"
              @click="editor.chain().focus().setParagraph().run()">Body</button>

      <span class="tb-divider"></span>

      <el-tooltip content="Bulleted list" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('bulletList') }" @click="editor.chain().focus().toggleBulletList().run()">
          <el-icon><List /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="Numbered list" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('orderedList') }" @click="editor.chain().focus().toggleOrderedList().run()">
          <el-icon><Finished /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="Quote" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive('blockquote') }" @click="editor.chain().focus().toggleBlockquote().run()">
          <el-icon><ChatLineSquare /></el-icon>
        </button>
      </el-tooltip>

      <span class="tb-divider"></span>

      <el-tooltip v-for="align in alignOptions" :key="align.value" :content="align.label" placement="bottom">
        <button class="tb-btn" :class="{ active: isActive({ textAlign: align.value }) }"
                @click="editor.chain().focus().setTextAlign(align.value).run()">
          <el-icon><component :is="align.icon" /></el-icon>
        </button>
      </el-tooltip>

      <span class="tb-divider"></span>

      <el-tooltip content="Insert image" placement="bottom">
        <button class="tb-btn" :disabled="uploadingImage" @click="triggerImagePicker">
          <el-icon v-if="!uploadingImage"><Picture /></el-icon>
          <el-icon v-else class="is-loading"><Loading /></el-icon>
        </button>
      </el-tooltip>

      <span class="tb-divider"></span>

      <el-tooltip content="Undo" placement="bottom">
        <button class="tb-btn" :disabled="!canUndo" @click="editor.chain().focus().undo().run()">
          <el-icon><RefreshLeft /></el-icon>
        </button>
      </el-tooltip>
      <el-tooltip content="Redo" placement="bottom">
        <button class="tb-btn" :disabled="!canRedo" @click="editor.chain().focus().redo().run()">
          <el-icon><RefreshRight /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <div class="paper-scroll">
      <editor-content :editor="editor" />
    </div>

    <input ref="imageInputRef" type="file" accept="image/*" multiple class="hidden-file-input" @change="handleImagePicked" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import TextAlign from '@tiptap/extension-text-align'
import { TableKit } from '@tiptap/extension-table'
import { TaskList, TaskItem } from '@tiptap/extension-list'
import Collaboration from '@tiptap/extension-collaboration'
import CollaborationCaret from '@tiptap/extension-collaboration-caret'
import { ElMessage } from 'element-plus'
import { fileApi } from '../api/file'
import { setActiveEditor, clearActiveEditor } from './editorInstance'

const props = defineProps({
  modelValue: { type: String, default: '' },
  editable: { type: Boolean, default: true },
  /** Hocuspocus provider；传入即进入协同模式（父组件通过 :key 在模式切换时重建本组件） */
  provider: { type: Object, default: null },
  /** 协同模式下的本人光标信息 { name, color } */
  user: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'ready'])

const imageInputRef = ref(null)
const uploadingImage = ref(false)

const alignOptions = [
  { value: 'left', label: 'Align left', icon: 'Fold' },
  { value: 'center', label: 'Center', icon: 'Operation' },
  { value: 'right', label: 'Align right', icon: 'Expand' }
]

// useEditor 返回的是 shallowRef，编辑器内部事务不会触发组件重渲染，
// 工具栏的高亮/可用状态得靠这个计数器建立依赖。
const txVersion = ref(0)

const isCollab = !!props.provider

// 内容扩展必须与 collab-server/extensions.js 一致：协同服务用同一套扩展在协同状态和 Markdown 之间转换，
// 这边多出的节点那边认不出，会在 Agent 读写时丢掉
const buildExtensions = () => {
  const list = [
    // 协同模式下必须关掉本地撤销栈，Collaboration 扩展提供协同感知的撤销
    StarterKit.configure(isCollab ? { undoRedo: false } : {}),
    Image.configure({ inline: false, allowBase64: false }),
    TextAlign.configure({ types: ['heading', 'paragraph'] }),
    TableKit,
    TaskList,
    TaskItem.configure({ nested: true })
  ]
  if (isCollab) {
    list.push(Collaboration.configure({ document: props.provider.document }))
    if (props.user) {
      list.push(CollaborationCaret.configure({ provider: props.provider, user: props.user }))
    }
  }
  return list
}

const editor = useEditor({
  // 协同模式下内容由服务端灌入（onLoadDocument），前端再 setContent 会造成重复
  content: isCollab ? undefined : (props.modelValue || ''),
  editable: props.editable,
  extensions: buildExtensions(),
  // 正文排版样式挂在 .paper 上
  editorProps: {
    attributes: { class: 'paper' },
    handlePaste: (view, event) => handleImageFiles(Array.from(event.clipboardData?.files || [])),
    handleDrop: (view, event) => handleImageFiles(Array.from(event.dataTransfer?.files || []))
  },
  onCreate: ({ editor: instance }) => {
    setActiveEditor(instance)
    emit('ready', instance)
  },
  onTransaction: () => {
    txVersion.value++
  },
  onUpdate: ({ editor: instance }) => {
    emit('update:modelValue', instance.getHTML())
  }
})

/** 工具栏高亮判断：读一次 txVersion 建立响应式依赖 */
const isActive = (...args) => {
  txVersion.value
  return editor.value?.isActive(...args) ?? false
}

const canUndo = computed(() => {
  txVersion.value
  return editor.value?.can().undo() ?? false
})

const canRedo = computed(() => {
  txVersion.value
  return editor.value?.can().redo() ?? false
})

// 外部（加载文档、版本回溯）改内容时同步进编辑器；协同模式下内容一律走 yjs，不接受外部覆盖
watch(() => props.modelValue, (value) => {
  if (isCollab) return
  const instance = editor.value
  if (!instance || instance.isDestroyed) return
  if ((value || '') === instance.getHTML()) return
  instance.commands.setContent(value || '', { emitUpdate: false })
})

watch(() => props.editable, (value) => {
  editor.value?.setEditable(value)
})

const triggerImagePicker = () => imageInputRef.value?.click()

const handleImagePicked = (event) => {
  const files = Array.from(event.target.files)
  event.target.value = ''
  uploadImages(files)
}

/** 粘贴/拖拽进来的图片同样走上传，返回 true 表示已处理，阻止默认的 base64 内嵌 */
const handleImageFiles = (files) => {
  const images = files.filter(file => file.type.startsWith('image/'))
  if (images.length === 0) return false
  uploadImages(images)
  return true
}

/**
 * 图片本体传 MinIO，文档里只留下引用地址。
 * 不用 base64 内嵌：那样几 MB 的图片会被塞进 document.content 并参与每次同步。
 * 多张图按顺序逐张上传、插入。
 */
const uploadImages = async (files) => {
  uploadingImage.value = true
  try {
    for (const file of files) {
      const res = await fileApi.upload(file)
      editor.value.chain().focus().setImage({
        src: `/api/files/preview/${res.data.fileId}`,
        alt: file.name
      }).run()
    }
    ElMessage.success(files.length > 1 ? `${files.length} images inserted` : 'Image inserted')
  } catch (e) {
    console.error('Image upload failed', e)
    ElMessage.error('Image upload failed')
  } finally {
    uploadingImage.value = false
  }
}

onBeforeUnmount(() => {
  const instance = editor.value
  clearActiveEditor(instance)
  instance?.destroy()
})
</script>

<style scoped>
.doc-editor { display: flex; flex-direction: column; min-height: 100%; }

.editor-toolbar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 2px;
  padding: 8px 20px;
  background: #fff;
  border-bottom: 1px solid var(--border);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.04);
}

.tb-btn {
  min-width: 32px;
  height: 30px;
  padding: 0 8px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  font-size: 14px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
}
.tb-btn:hover:not(:disabled) { background: var(--bg-sunken); }
.tb-btn.active { background: var(--ink-soft); color: var(--text-primary); }
.tb-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.tb-divider { width: 1px; height: 18px; background: var(--border); margin: 0 6px; }

/* 连续页面：正文在居中的一栏里往下延伸，不分页 */
.paper-scroll { padding: 40px 24px 120px; }

.paper-scroll > div { max-width: 760px; margin: 0 auto; }

.hidden-file-input { display: none; }

/* ===== 正文排版 ===== */
:deep(.paper) {
  min-height: 60vh;
  outline: none;
  line-height: 1.8;
  font-size: 16px;
  color: var(--text-primary);
  word-break: break-word;
}
:deep(.paper > * + *) { margin-top: 0.75em; }
:deep(.paper h1) { font-size: 28px; font-weight: 500; line-height: 1.4; margin-top: 1.2em; }
:deep(.paper h2) { font-size: 22px; font-weight: 500; line-height: 1.4; margin-top: 1.1em; }
:deep(.paper h3) { font-size: 18px; font-weight: 500; line-height: 1.5; margin-top: 1em; }
:deep(.paper h4) { font-size: 16px; font-weight: 600; line-height: 1.5; margin-top: 1em; }
:deep(.paper h5) { font-size: 15px; font-weight: 600; line-height: 1.5; margin-top: 0.9em; }
:deep(.paper h6) { font-size: 14px; font-weight: 600; line-height: 1.5; margin-top: 0.9em; color: var(--text-secondary); }
:deep(.paper ul), :deep(.paper ol) { padding-left: 1.6em; }
:deep(.paper li) { margin: 0.2em 0; }
:deep(.paper li > p) { margin: 0; }

/* 任务列表：复选框在左，正文在右，完成项变灰划线 */
:deep(.paper ul[data-type="taskList"]) { list-style: none; padding-left: 0.2em; }
:deep(.paper ul[data-type="taskList"] li) { display: flex; align-items: flex-start; gap: 0.5em; }
:deep(.paper ul[data-type="taskList"] li > label) { flex: 0 0 auto; margin-top: 0.35em; user-select: none; }
:deep(.paper ul[data-type="taskList"] li > div) { flex: 1 1 auto; min-width: 0; }
:deep(.paper ul[data-type="taskList"] li[data-checked="true"] > div) {
  color: var(--text-secondary);
  text-decoration: line-through;
}

/* 表格：固定布局，内容多时在容器内横向滚动 */
:deep(.paper .tableWrapper) { overflow-x: auto; }
:deep(.paper table) { border-collapse: collapse; width: 100%; table-layout: fixed; }
:deep(.paper th), :deep(.paper td) {
  border: 1px solid var(--border);
  padding: 6px 10px;
  vertical-align: top;
  min-width: 3em;
}
:deep(.paper th) { background: var(--bg-sunken); font-weight: 600; text-align: left; }
:deep(.paper th > p), :deep(.paper td > p) { margin: 0; }
:deep(.paper .selectedCell) { background: var(--ink-soft); }
:deep(.paper blockquote) {
  border-left: 3px solid var(--border);
  padding-left: 14px;
  color: var(--text-secondary);
}
:deep(.paper img) {
  max-width: 100%;
  height: auto;
  border-radius: 4px;
  display: block;
}
:deep(.paper img.ProseMirror-selectednode) { outline: 2px solid var(--brand); }
:deep(.paper pre) {
  background: var(--bg-sunken);
  border-radius: 6px;
  padding: 12px 14px;
  font-size: 14px;
  line-height: 1.6;
  overflow-x: auto;
}
:deep(.paper code) {
  background: var(--bg-sunken);
  border-radius: 3px;
  padding: 1px 4px;
  font-size: 14px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
:deep(.paper pre code) { background: none; padding: 0; border-radius: 0; }
:deep(.paper a) { color: var(--brand); text-decoration: underline; }
:deep(.paper hr) { border: none; border-top: 1px solid var(--border); margin: 1.5em 0; }

/* ===== 远程协作光标（CollaborationCaret 只插 DOM 不带样式，必须自己写） ===== */
:deep(.collaboration-carets__caret) {
  border-left: 1px solid;      /* 颜色由扩展按用户色内联注入 */
  border-right: 1px solid;
  margin-left: -1px;
  margin-right: -1px;
  pointer-events: none;
  position: relative;
  word-break: normal;
}
:deep(.collaboration-carets__label) {
  position: absolute;          /* 关键：不脱离文档流就会铺满整行 */
  top: -1.4em;
  left: -1px;
  padding: 1px 6px;
  border-radius: 4px 4px 4px 0;
  color: #fff;
  font-size: 12px;
  font-style: normal;
  font-weight: 500;
  line-height: normal;
  white-space: nowrap;
  user-select: none;
}
</style>
