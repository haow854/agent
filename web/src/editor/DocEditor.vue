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

    <input ref="imageInputRef" type="file" accept="image/*" class="hidden-file-input" @change="handleImagePicked" />
  </div>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import TextAlign from '@tiptap/extension-text-align'
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

const buildExtensions = () => {
  const list = [
    // 协同模式下必须关掉本地撤销栈，Collaboration 扩展提供协同感知的撤销
    StarterKit.configure({ heading: { levels: [1, 2, 3] }, ...(isCollab ? { undoRedo: false } : {}) }),
    Image.configure({ inline: false, allowBase64: false }),
    TextAlign.configure({ types: ['heading', 'paragraph'] })
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
  // 保留原来的 .paper 类名，A4 纸样式依赖它
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

const handleImagePicked = async (event) => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (file) await uploadAndInsertImage(file)
}

/** 粘贴/拖拽进来的图片同样走上传，返回 true 表示已处理，阻止默认的 base64 内嵌 */
const handleImageFiles = (files) => {
  const images = files.filter(file => file.type.startsWith('image/'))
  if (images.length === 0) return false
  images.forEach(file => uploadAndInsertImage(file))
  return true
}

/**
 * 图片本体传 MinIO，文档里只留下Quote地址。
 * 不用 base64 内嵌：那样几 MB 的图片会被塞进 document.content 并参与每次同步。
 */
const uploadAndInsertImage = async (file) => {
  if (uploadingImage.value) return
  uploadingImage.value = true
  try {
    const res = await fileApi.upload(file)
    const fileId = res?.data?.fileId
    if (!fileId) throw new Error('Upload returned no fileId')

    editor.value?.chain().focus().setImage({
      src: `/api/files/preview/${fileId}`,
      alt: file.name
    }).run()
    ElMessage.success('Image inserted')
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

.paper-scroll { display: flex; padding: 40px 0 80px; overflow-x: auto; }

.paper-scroll > div { flex: 0 0 auto; margin: 0 auto; }

.hidden-file-input { display: none; }

/* ===== A4 纸张与正文排版（原 EditorView 的 .paper 样式迁移过来） ===== */
:deep(.paper) {
  width: 780px;
  min-height: 1100px;
  background: #fff;
  padding: 80px 100px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
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
:deep(.paper ul), :deep(.paper ol) { padding-left: 1.6em; }
:deep(.paper li) { margin: 0.2em 0; }
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
  overflow-x: auto;
}
:deep(.paper code) { background: var(--bg-sunken); border-radius: 3px; padding: 1px 4px; font-size: 14px; }
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
