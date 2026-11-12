<template>
  <div class="editor-pane">
    <header class="toolbar">
      <div class="left">
        <slot name="leading" />
        <span class="doc-title">{{ docName }}</span>
        <span class="save-status">{{ saveStatusText }}</span>
        <el-button circle icon="Clock" size="small" @click="openHistoryDrawer"></el-button>
      </div>

      <CollaboratePanel
          :documentId="String(documentId)"
          :userId="currentUserId"
          @retry="connect" />

      <div class="header-right">
        <el-button type="primary" size="small" round icon="Check" @click="handleManualSave" :loading="isSaving">Save</el-button>
        <el-button size="small" round icon="Download" @click="handleDownload">Download</el-button>
        <el-avatar :size="30" class="user-avatar">{{ currentUserName.charAt(0).toUpperCase() }}</el-avatar>
      </div>
    </header>

    <div class="workspace" v-loading="docLoading">
      <aside class="left-sidebar" v-if="textStats">
        <div class="sidebar-section">
          <div class="section-title"><el-icon><DataLine /></el-icon> Text stats</div>
          <div class="stats-grid">
            <div class="stat-item"><span class="num">{{ textStats.totalCharacters || 0 }}</span><span class="desc">Characters</span></div>
            <div class="stat-item"><span class="num">{{ textStats.lines || 0 }}</span><span class="desc">Paragraphs</span></div>
            <div class="stat-item"><span class="num">{{ textStats.chineseCharacters || 0 }}</span><span class="desc">Chinese chars</span></div>
            <div class="stat-item"><span class="num">{{ textStats.punctuations || 0 }}</span><span class="desc">Punctuation</span></div>
          </div>
        </div>
      </aside>

      <main class="editor-main">
        <DocEditor
            v-if="ready"
            :key="editorKey"
            v-model="docContent"
            :provider="collabProvider"
            :user="collabUser"
            @update:modelValue="handleInput" />
      </main>

      <aside class="ai-sidebar">
        <div class="ai-header">
          <span class="ai-header-title">
            <span class="ai-header-mark">◈</span> Docs Assistant
          </span>
          <el-select v-model="currentModel" size="small" placeholder="Model" style="width: 130px;">
            <el-option v-for="m in modelOptions" :key="m.code" :label="m.name" :value="m.code" />
          </el-select>
        </div>

        <div class="chat-area" ref="chatArea" v-loading="historyLoading">
          <div v-if="!messages.length" class="chat-bubble ai">
            Hi, I’m your writing assistant. Ask me anything, or tell me what to do with this document.
          </div>

          <template v-for="(msg, i) in messages" :key="i">
            <div :class="['chat-bubble', msg.role]">
              <AgentSteps :steps="msg.steps" />
              <div v-if="msg.text" class="preserve-format">{{ msg.text }}</div>

              <ApprovalPrompt
                  v-if="msg.approvals?.length"
                  :approvals="msg.approvals"
                  @approve="approve(msg)"
                  @deny="deny(msg)" />
              <div v-else-if="msg.approvalOutcome" class="approval-outcome" :class="msg.approvalOutcome">
                {{ msg.approvalOutcome === 'approved' ? 'You approved this action' : 'You rejected this action — it did not run' }}
              </div>

              <div v-if="msg.actionResult" class="file-card" @click="openDocument(msg.actionResult)">
                <el-icon size="24" color="var(--brand)"><Document /></el-icon>
                <div>
                  <div class="file-name">{{ msg.actionResult.title }}</div>
                  <div class="file-desc">Open</div>
                </div>
              </div>
            </div>
          </template>

          <div v-if="isThinking" class="chat-bubble ai thinking">
            <AgentSteps :steps="steps" />
            <div class="thinking-line"><el-icon class="is-loading"><Loading /></el-icon> Working…</div>
          </div>
        </div>

        <div class="input-area">
          <div class="composer" :class="{ 'is-focused': isInputFocused }">
            <textarea
                v-model="userMsg"
                class="composer-input"
                rows="3"
                placeholder="Give an instruction — polish, summarize, proofread…"
                @keydown.enter.exact.prevent="handleSend"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
            ></textarea>

            <div class="composer-bar">
              <DocPicker :model-value="attachedDoc" readonly />

              <span class="bar-spacer"></span>

              <button
                  class="bar-send"
                  :class="{ active: userMsg.trim().length > 0 }"
                  type="button"
                  :disabled="isThinking"
                  @click="handleSend">
                <el-icon v-if="isThinking" class="is-loading"><Loading /></el-icon>
                <el-icon v-else><Top /></el-icon>
              </button>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <el-drawer v-model="showHistory" title="Version history" size="350px" direction="rtl">
      <el-timeline v-if="versionList.length > 0">
        <el-timeline-item v-for="ver in versionList" :key="ver.id" :timestamp="new Date(ver.createTime).toLocaleString()" placement="top">
          <el-card shadow="hover" class="version-card">
            <p>Version V{{ ver.versionNumber }}</p>
            <el-button size="small" type="primary" plain style="margin-top:10px" @click="handleRestore(ver.versionNumber)">Restore</el-button>
          </el-card>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-else description="No versions yet" />
    </el-drawer>

  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '../../api/user'
import { docApi } from '../../api/document'
import { useAgentChat } from '../../composables/useAgentChat'
import { useAiModels } from '../../composables/useAiModels'
import { downloadBlob } from '../../utils/download'
import { openDocument } from '../../utils/openDocument'
import CollaboratePanel from '../../collaboration/CollaboratePanel.vue'
import { useCollaboration } from '../../collaboration/useCollaboration'
import DocEditor from '../../editor/DocEditor.vue'
import DocPicker from '../components/DocPicker.vue'
import AgentSteps from '../components/AgentSteps.vue'
import ApprovalPrompt from '../components/ApprovalPrompt.vue'
import { getEditorText } from '../../editor/editorInstance'

const props = defineProps({
  documentId: { type: [String, Number], required: true }
})

const {
  provider: collabProvider,
  collabUser,
  startCollaboration,
  stopCollaboration
} = useCollaboration()

const { messages, steps, isThinking, historyLoading, startNewSession, ask, approve, deny } = useAgentChat()
const { modelOptions, currentModel } = useAiModels()

const isSaving = ref(false)
const saveStatusText = ref('')
const docName = ref('Untitled')
const currentUserName = ref('User')
const currentUserId = ref(localStorage.getItem('userId') || '1')
const docContent = ref('')
const docLoading = ref(false)
const ready = ref(false)
const userMsg = ref('')
const isInputFocused = ref(false)
const showHistory = ref(false)
const versionList = ref([])
const textStats = ref(null)

const docId = computed(() => String(props.documentId))
const editorKey = computed(() => `${docId.value}:${collabProvider.value ? 'collab' : 'solo'}`)

// 挂载目标由这一篇锁定，换不了：这里开着哪篇，AI 就只能动哪篇
const attachedDoc = computed(() => ({ id: docId.value, title: docName.value }))

function connect() {
  startCollaboration({
    documentId: docId.value,
    userId: currentUserId.value,
    userName: currentUserName.value
  })
}

async function load() {
  ready.value = false
  stopCollaboration()
  startNewSession()
  saveStatusText.value = ''
  textStats.value = null
  docLoading.value = true

  try {
    const [docRes] = await Promise.all([
      docApi.getDocDetail(docId.value),
      userApi.getUserInfo(currentUserId.value)
          .then(r => { currentUserName.value = r.data.username || 'User' })
          .catch(() => {})
    ])
    const data = docRes.data || docRes
    docName.value = data.title || 'Untitled'
    docContent.value = data.content || ''
    saveStatusText.value = 'Saved'

    docApi.getDocStats(docId.value)
        .then(res => { textStats.value = res.data })
        .catch(e => console.warn('Text stats request failed', e))

    connect()
    ready.value = true
  } catch (e) {
    console.error('Could not open the document', e)
    docName.value = 'Could not open the document'
    ElMessage.error('Could not open the document')
  } finally {
    docLoading.value = false
  }
}

watch(docId, load)
onMounted(load)
onUnmounted(stopCollaboration)

const handleSend = async () => {
  const task = userMsg.value.trim()
  if (!task || isThinking.value) return
  userMsg.value = ''
  scrollToBottom()

  await ask({ task, model: currentModel.value, documentId: docId.value })
  scrollToBottom()
}

const scrollToBottom = () => {
  nextTick(() => {
    const c = document.querySelector('.chat-area')
    if (c) c.scrollTop = c.scrollHeight
  })
}

watch(messages, scrollToBottom, { deep: true })

const handleInput = () => {
  saveStatusText.value = collabProvider.value ? 'Live sync on' : 'Unsaved changes'
}

const handleManualSave = async () => {
  isSaving.value = true
  saveStatusText.value = 'Saving…'
  try {
    await docApi.updateDoc(docId.value, { title: docName.value, category: 'default' })
    saveStatusText.value = 'Saved'
    ElMessage.success('Saved')
  } catch (e) {
    saveStatusText.value = 'Save failed'
    ElMessage.error('Sync failed')
  } finally {
    isSaving.value = false
  }
}

const openHistoryDrawer = async () => {
  const res = await docApi.getVersions(docId.value)
  versionList.value = res.data
  showHistory.value = true
}

const handleRestore = async (verNum) => {
  ElMessageBox.confirm(`Restore this document to V${verNum}? Unsaved changes will be lost.`, 'Restore version').then(async () => {
    try {
      await docApi.restoreVersion(docId.value, verNum)
      ElMessage.success('Version restored')
      showHistory.value = false
      await load()
    } catch (e) {
      ElMessage.error('Restore failed')
    }
  }).catch(() => {})
}

const handleDownload = async () => {
  isSaving.value = true
  saveStatusText.value = 'Exporting…'
  try {
    // 包装成 Word 认得的格式，并强制保留空白符
    const wordHtml = `
      <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
      <head><meta charset='utf-8'></head>
      <body style="font-family: sans-serif;">
        <pre style="white-space: pre-wrap; word-break: break-all;">${getEditorText()}</pre>
      </body>
      </html>`
    downloadBlob(new Blob([wordHtml], { type: 'application/msword' }), `${docName.value}.doc`)
    ElMessage.success('Exported')
    saveStatusText.value = 'Saved'
  } catch (e) {
    ElMessage.error('Download failed')
  } finally {
    isSaving.value = false
  }
}

</script>

<style scoped>
.preserve-format { white-space: pre-wrap !important; word-break: break-word; text-align: left; }

.editor-pane {
  flex: 1; height: 100%; display: flex; flex-direction: column;
  background: var(--bg-sunken); min-width: 0;
  container-type: inline-size;
}
.toolbar { height: 56px; background: #fff; border-bottom: 1px solid var(--border); display: flex; align-items: center; padding: 0 24px; justify-content: space-between; gap: 24px; flex-shrink: 0; }
.toolbar .left { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.doc-title { font-size: 15px; font-weight: 500; color: var(--text-primary); }
.save-status { font-size: 12px; color: var(--text-muted); }
.header-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.user-avatar { background-color: var(--brand); font-weight: 500; color: #fff; }
.workspace { flex: 1; display: flex; overflow: hidden; position: relative; min-height: 0; }
.editor-main { flex: 1; overflow-y: auto; background: var(--bg-sunken); position: relative; min-width: 0; }

.left-sidebar {
  width: 220px; background: var(--bg-sunken); border-right: 1px solid var(--border);
  display: flex; flex-direction: column; flex-shrink: 0; padding: 20px; overflow-y: auto;
}
.sidebar-section { margin-bottom: 30px; }
.section-title { font-size: 14px; font-weight: 500; color: var(--text-primary); margin-bottom: 16px; display: flex; align-items: center; gap: 6px; }

.stats-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.stat-item {
  background: #fff; border: 1px solid var(--border); border-radius: 8px; padding: 12px;
  display: flex; flex-direction: column; align-items: center; box-shadow: 0 2px 4px rgba(0,0,0,0.02);
}
.stat-item .num { font-size: 18px; font-weight: 500; color: var(--text-primary); font-family: monospace; }
.stat-item .desc { font-size: 11px; color: var(--text-muted); margin-top: 4px; }

.ai-sidebar { width: 360px; background: #fff; border-left: 1px solid var(--border); display: flex; flex-direction: column; height: 100%; flex-shrink: 0; }
.ai-header {
  padding: 12px 20px; font-weight: 500; border-bottom: 1px solid var(--border); color: var(--text-primary);
  display: flex; align-items: center; justify-content: space-between; flex-shrink: 0;
}
.ai-header-title { display: flex; align-items: center; gap: 6px; }
.ai-header-mark { font-size: 17px; line-height: 1; color: var(--brand); }

.chat-area { flex-grow: 1; padding: 20px; overflow-y: auto; background: var(--bg-sunken); display: flex; flex-direction: column; }
.chat-bubble {
  max-width: 90%; padding: 14px 18px; border-radius: 12px; margin-bottom: 20px;
  font-size: 14px; line-height: 1.6; box-shadow: 0 2px 8px rgba(0,0,0,0.04);
  display: flex; flex-direction: column; gap: 10px;
}
.chat-bubble.ai { background: #fff; border: 1px solid var(--border); align-self: flex-start; border-bottom-left-radius: 2px; }
.chat-bubble.user { background: var(--bg-sunken); color: var(--text-primary); align-self: flex-end; margin-left: auto; border-bottom-right-radius: 2px; }
.chat-bubble.thinking { color: var(--text-muted); }
.chat-bubble.thinking .thinking-line { display: flex; align-items: center; gap: 6px; }

.approval-outcome { font-size: 12px; line-height: 1.6; color: var(--text-muted); }
.approval-outcome.denied { color: var(--danger); }

.file-card { display: flex; align-items: center; gap: 10px; background: var(--bg-sunken); padding: 10px; border-radius: 6px; cursor: pointer; }
.file-card:hover { background: var(--bg-hover); }
.file-name { font-weight: 500; font-size: 13px; color: var(--text-primary); }
.file-desc { font-size: 12px; color: var(--text-muted); margin-top: 2px; }

.input-area { flex-shrink: 0; padding: 16px; border-top: 1px solid var(--border); background: var(--bg-raised); }

.composer {
  display: flex; flex-direction: column;
  border: 1px solid var(--border); border-radius: 12px;
  background: var(--bg-raised); transition: 0.18s ease;
}
.composer:hover { border-color: var(--border-strong); }
.composer.is-focused { border-color: var(--brand); box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.1); }

.composer-input {
  border: none; outline: none; resize: none; background: transparent;
  padding: 12px 14px 4px; font: inherit; font-size: 14px; line-height: 1.6;
  color: var(--text-primary);
}
.composer-input::placeholder { color: var(--text-muted); }

.composer-bar { display: flex; align-items: center; gap: 6px; padding: 6px 8px 8px 10px; }
.bar-spacer { flex: 1 1 auto; }

.bar-send {
  display: inline-flex; align-items: center; justify-content: center;
  width: 28px; height: 28px; color: var(--text-muted); background: var(--bg-sunken);
  border: none; border-radius: 8px; cursor: pointer; transition: 0.15s ease;
}
.bar-send.active { background: var(--brand); color: #fff; }
.bar-send:disabled { cursor: default; }

/* 780 纸 + 360 助理 + 220 统计 = 1360，装不下就先Collapse统计栏 */
@container (max-width: 1400px) {
  .left-sidebar { display: none; }
}
</style>
