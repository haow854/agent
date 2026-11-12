<template>
  <div class="feishu-layout">
    <el-container class="full-height">
      <el-aside width="292px" class="feishu-aside">
        <div class="aside-top">
          <div class="brand">
            <div class="logo-box">◈</div>
            <span class="brand-text">Docs</span>
          </div>

          <div class="mode-switch" role="tablist" aria-label="Workspace mode">
            <button
                v-for="m in MODES"
                :key="m.value"
                class="mode-option"
                :class="{ active: dashboardMode === m.value }"
                type="button"
                role="tab"
                :aria-selected="dashboardMode === m.value"
                @click="dashboardMode = m.value"
            >
              <el-icon><component :is="m.icon" /></el-icon>
              <span>{{ m.label }}</span>
            </button>
          </div>

          <button
              v-if="dashboardMode === 'chat'"
              class="primary-btn"
              type="button"
              @click="startNewChat">
            <el-icon><EditPen /></el-icon>
            <span>New chat</span>
            <el-icon class="primary-btn-tail"><Plus /></el-icon>
          </button>

          <button
              v-else
              class="primary-btn"
              :class="{ active: !coworkDocId }"
              type="button"
              @click="coworkDocId = ''">
            <el-icon><Files /></el-icon>
            <span>Files</span>
            <span class="primary-btn-tail doc-count">{{ docList.length }}</span>
          </button>

          <input ref="fileInputRef" type="file" class="file-input" @change="onFileSelected" />

          <div class="doc-list-header">
            <span>Chats</span>
            <span class="doc-count">{{ sessionList.length }}</span>
          </div>
        </div>

        <div class="doc-list" v-loading="sessionsLoading">
          <div
              v-for="s in sessionList"
              :key="s.sessionId"
              class="session-item"
              role="button"
              tabindex="0"
              @click="openSession(s)"
              @keyup.enter="openSession(s)"
          >
            <div class="doc-icon"><el-icon><ChatLineSquare /></el-icon></div>
            <div class="doc-list-content">
              <el-tooltip :content="s.title" placement="right" :show-after="600">
                <div class="doc-name">{{ s.title }}</div>
              </el-tooltip>
              <div class="doc-meta">
                <span>{{ formatTime(s.updatedAt) }}</span>
              </div>
            </div>
            <el-dropdown
                trigger="click"
                placement="bottom-start"
                @click.stop
                @command="(cmd) => handleSessionCommand(cmd, s)"
            >
              <button class="doc-more-btn" type="button" aria-label="Chat actions" @click.stop>
                <el-icon><MoreFilled /></el-icon>
              </button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="delete" icon="Delete" style="color: var(--danger)">
                    Delete chat
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>

          <div v-if="!sessionsLoading && sessionList.length === 0" class="doc-empty">
            <el-icon><ChatLineSquare /></el-icon>
            <span>No chats yet</span>
            <button type="button" @click="startNewChat">Start your first chat</button>
          </div>
        </div>

        <div class="aside-bottom">
          <button v-if="isAdminUser" class="utility-item" type="button" @click="goToAIOps">
            <el-icon><Setting /></el-icon>
            <span>AI Ops</span>
          </button>
          <el-dropdown trigger="click" placement="top-start" @command="handleUserCommand">
            <div class="user-profile">
              <el-avatar :size="30" class="user-avatar">
                {{ currentUserName.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="username">{{ currentUserName }}</span>
              <el-icon class="more-icon"><MoreFilled /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" icon="SwitchButton" style="color: var(--danger)">Sign out</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-aside>

      <el-main
          class="feishu-main"
          :class="{ 'is-pane': dashboardMode === 'chat' || coworkDocId }">
        <ChatPane v-if="dashboardMode === 'chat'" :userName="currentUserName" />

        <EditorPane v-else-if="coworkDocId" :document-id="coworkDocId" />

        <div class="main-container" v-else v-loading="loading">
          <div class="doc-section">
            <div class="section-header">
              <h2>Documents <span class="doc-count">{{ docList.length }}</span></h2>
              <div class="header-actions">
                <el-input
                    v-model="search"
                    class="doc-search"
                    placeholder="Search documents…"
                    size="small"
                    prefix-icon="Search"
                    clearable
                    @keyup.enter="handleSearch"
                    @clear="fetchFiles"
                />
                <el-button
                    size="small" icon="Upload"
                    @click="triggerUpload">Upload</el-button>
                <el-button
                    v-if="docList.length"
                    type="danger" size="small" plain icon="Delete"
                    @click="clearDirtyData">Clear all</el-button>
              </div>
            </div>

            <div class="card-grid" v-if="filteredList.length">
              <div class="doc-card" v-for="doc in filteredList" :key="doc.id">
                <div class="card-cover" @click="goToEditor(doc.id)">
                  <el-icon :size="44"><Document /></el-icon>
                </div>
                <div class="card-info">
                  <div class="info-top">
                    <el-tooltip :content="doc.name" placement="top" :show-after="600">
                      <div class="doc-name" @click="goToEditor(doc.id)">{{ doc.name }}</div>
                    </el-tooltip>
                    <el-dropdown trigger="click" @command="(cmd) => handleCardCommand(cmd, doc)">
                      <el-icon class="more-btn"><MoreFilled /></el-icon>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item command="download" icon="Download">Download</el-dropdown-item>
                          <el-dropdown-item command="delete" icon="Delete" style="color: var(--danger)">Delete document</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <div class="doc-meta">
                    <span>{{ doc.time }}</span>
                    <el-tag size="small" :type="doc.analyzed ? 'success' : 'info'" round effect="light">
                      {{ doc.analyzed ? 'Ready' : 'Pending' }}
                    </el-tag>
                  </div>
                </div>
              </div>
            </div>

            <div v-else-if="!loading" class="doc-empty">
              <el-icon><Document /></el-icon>
              <span>{{ search ? 'No matching documents' : 'No documents yet' }}</span>
              <button v-if="!search" type="button" @click="triggerUpload">Upload your first document</button>
            </div>
          </div>
        </div>
      </el-main>
    </el-container>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, defineAsyncComponent } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { fileApi } from '../../api/file'
import { userApi } from '../../api/user'
import { docApi } from '../../api/document'
import { agentApi } from '../../api/agent'
import { useAgentChat } from '../../composables/useAgentChat'
import { isAdmin } from '../../utils/jwt'
import { clearSession } from '../../utils/session'
import { dashboardMode, coworkDocId } from '../../composables/dashboardMode'
import { useAgentSessions } from '../../composables/useAgentSessions'
import ChatPane from '../chat/ChatPane.vue'

// tiptap + yjs 有 500 多 KB，只在真的打开一篇文档时才拉
const EditorPane = defineAsyncComponent(() => import('../editor/EditorPane.vue'))

const { sessionList, sessionsLoading, fetchSessions } = useAgentSessions()

const MODES = [
  { value: 'chat', label: 'Chat', icon: 'ChatLineSquare' },
  { value: 'cowork', label: 'Cowork', icon: 'Files' }
]

const { sessionId, openSession: openChatSession, startNewSession } = useAgentChat()

const router = useRouter()
const loading = ref(false)
const currentUserName = ref('User')
const docList = ref([])
const search = ref('')
const fileInputRef = ref(null)

const isAdminUser = ref(isAdmin())

const getCurrentUserId = () => localStorage.getItem('userId')

// 获取用户信息
const fetchUser = async () => {
  const uid = getCurrentUserId()
  if (uid) {
    try {
      const res = await userApi.getUserInfo(uid)
      currentUserName.value = res.data.username || 'User'
    } catch (e) { currentUserName.value = 'User' }
  }
}

// 获取文档列表 (从 8084 获取)
const fetchFiles = async () => {
  const uid = getCurrentUserId()
  if (!uid) {
    router.push('/login')
    return
  }
  loading.value = true
  try {
    const res = await docApi.getUserDocs(uid)
    if (res.data && Array.isArray(res.data)) {
      docList.value = res.data.map(item => ({
        id: item.id,
        fileId: item.fileId,
        name: item.title || 'Untitled',
        time: item.createTime ? new Date(item.createTime).toLocaleDateString() : 'Unknown',
        analyzed: !!item.summary
      }))
    }
  } catch (e) {
    console.error('拉取列表失败', e)
  } finally { loading.value = false }
}

onMounted(() => { fetchUser(); fetchFiles(); fetchSessions() })

const filteredList = computed(() => docList.value.filter(d => d.name.toLowerCase().includes(search.value.toLowerCase())))

// 搜索逻辑
const handleSearch = async () => {
  if (!search.value.trim()) {
    return fetchFiles() // 如果搜空，恢复全量列表
  }

  loading.value = true
  try {
    // 调 8084 接口进行全文检索（搜标题+内容）
    const res = await docApi.searchDocs(search.value)

    if (res.data) {
      docList.value = res.data.map(item => ({
        id: item.id,
        fileId: item.fileId,
        name: item.title || 'Untitled',
        time: item.createTime ? new Date(item.createTime).toLocaleDateString() : 'Unknown',
        analyzed: !!item.summary
      }))
    }
    ElMessage.success(`Found ${docList.value.length} matching documents`)
  } catch (e) {
    ElMessage.error('Search failed')
  } finally {
    loading.value = false
  }
}


// 飞书化上传交互
const triggerUpload = () => fileInputRef.value?.click()

const onFileSelected = async (event) => {
  const rawFile = event.target.files[0]
  if (!rawFile) return

  loading.value = true
  try {
    await docApi.uploadDoc(rawFile)

    ElMessage.success(`“${rawFile.name}” uploaded`)
    await fetchFiles()
    event.target.value = ''
  } catch (e) {
    console.error('Upload failed', e)
    ElMessage.error('Upload failed')
  } finally {
    event.target.value = ''
    loading.value = false
  }
}

// 卡片逻辑(删除下载)
const handleCardCommand = async (cmd, doc) => {
  if (cmd === 'delete') {
    ElMessageBox.confirm(`Permanently delete “${doc.name}”?`).then(async () => {
      loading.value = true
      try {
        await docApi.deleteDoc(doc.id)
        if (doc.fileId) await fileApi.delete(doc.fileId).catch(()=>{})
        ElMessage.success('Deleted')
        fetchFiles()
      } catch (e) { ElMessage.error('Delete failed') }
      finally { loading.value = false }
    }).catch(() => {})
  }
  // 下载部分
  else if (cmd === 'download') {
    loading.value = true
    try {
      const res = await docApi.getDocDetail(doc.id)
      const content = res.data.content || ""

      // 使用 pre 标签保护格式
      const wordHtml = `
        <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
        <head><meta charset='utf-8'></head>
        <body style="font-family: sans-serif;">
          <pre style="white-space: pre-wrap;">${content}</pre>
        </body>
        </html>`

      const blob = new Blob([wordHtml], { type: 'application/msword' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url; link.setAttribute('download', `${doc.name}.doc`)
      document.body.appendChild(link); link.click(); document.body.removeChild(link)
      window.URL.revokeObjectURL(url); ElMessage.success('Downloaded')
    } catch (e) { ElMessage.error('Could not fetch the latest content') }
    finally { loading.value = false }
  }
}

// 一键清理文档
const clearDirtyData = () => {
  ElMessageBox.confirm(`This permanently deletes all ${docList.value.length} documents. Continue?`, 'Clear all documents', {
    confirmButtonText: 'Delete',
    cancelButtonText: 'Cancel',
    type: 'warning'
  }).then(async () => {
    loading.value = true
    for (const doc of docList.value) {
      await docApi.deleteDoc(doc.id).catch(()=>{})
    }
    docList.value = []
    loading.value = false
    ElMessage.success('All documents cleared')
  })
}


const formatTime = (ms) => ms ? new Date(ms).toLocaleDateString() : ''

const openSession = (s) => {
  if (dashboardMode.value === 'cowork') {
    if (!s.documentId) {
      ElMessage.info('This chat isn’t linked to a document yet')
      return
    }
    coworkDocId.value = s.documentId
    return
  }
  openChatSession(s.sessionId)
}

const handleSessionCommand = async (cmd, s) => {
  if (cmd === 'delete') {
    await ElMessageBox.confirm('Deleting this chat removes its history for good. Continue?', 'Delete chat', {
      confirmButtonText: 'Delete', cancelButtonText: 'Cancel', type: 'warning'
    })
    await agentApi.endSession(s.sessionId)
    // 删的正好是当前会话，别让它留在 localStorage 里被下一条消息接着用
    if (sessionId.value === s.sessionId) startNewSession()
    ElMessage.success('Deleted')
    return fetchSessions()
  }

}

// 清掉当前会话，否则「新对话」会接着上一个会话继续
const startNewChat = () => {
  startNewSession()
  dashboardMode.value = 'chat'
}

const goToEditor = (id) => { coworkDocId.value = String(id) }

const handleUserCommand = (cmd) => {
  if (cmd === 'logout') {
    clearSession()
    window.location.replace('/login')
  }
}

const goToAIOps = () => {
  router.push('/aiops')
}
</script>

<style scoped>
.feishu-layout {
  height: 100vh;
  background: var(--bg-app);
}

.full-height {
  height: 100%;
}

.feishu-aside {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-sunken);
  border-right: 1px solid var(--border);
}

.aside-top {
  flex: none;
  padding: 18px 14px 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 0 6px;
  margin-bottom: 14px;
}

.logo-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  color: #fff;
  font-size: 19px;
  background: var(--brand);
  border-radius: 10px;
  box-shadow: 0 5px 12px rgba(217, 119, 87, 0.2);
}

.brand-text {
  color: var(--text-primary);
  font-size: 17px;
  font-weight: 500;
  letter-spacing: -0.2px;
}

.primary-btn,
.utility-item {
  width: 100%;
  border: 0;
  font: inherit;
  cursor: pointer;
}

.mode-switch {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--bg-hover);
  border-radius: 10px;
  margin-bottom: 14px;
}

.mode-option {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 32px;
  padding: 0;
  border: none;
  background: transparent;
  border-radius: 8px;
  font-size: 13px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: 0.18s ease;
}

.mode-option:hover { color: var(--text-primary); }

.mode-option.active {
  background: var(--bg-app);
  color: var(--text-primary);
  font-weight: 500;
  box-shadow: 0 1px 2px rgba(20, 20, 19, 0.05);
}

.primary-btn {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 42px;
  padding: 0 12px;
  color: var(--text-primary);
  font-size: 14px;
  font-weight: 500;
  background: var(--bg-app);
  border: 1px solid var(--border);
  border-radius: 10px;
  transition: 0.18s ease;
}

.primary-btn:hover {
  background: var(--bg-raised);
  border-color: var(--border-strong);
}

.primary-btn.active {
  border-color: var(--border-strong);
}

.primary-btn-tail {
  margin-left: auto;
  color: var(--text-muted);
}

.file-input {
  display: none;
}

.doc-search {
  margin-top: 12px;
}

.doc-search :deep(.el-input__wrapper) {
  min-height: 38px;
  background: transparent;
  border-radius: 9px;
  box-shadow: none;
}

.doc-search :deep(.el-input__wrapper:hover),
.doc-search :deep(.el-input__wrapper.is-focus) {
  background: #fff;
  box-shadow: 0 0 0 1px var(--border-strong) inset;
}

.doc-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 8px;
  margin-top: 10px;
  color: var(--text-secondary);
  font-size: 12px;
  font-weight: 500;
}

.doc-list-actions {
  display: flex;
  align-items: center;
  gap: 4px;
}

.doc-count {
  min-width: 22px;
  padding: 2px 6px;
  color: var(--text-muted);
  font-size: 11px;
  text-align: center;
  background: var(--bg-hover);
  border-radius: 10px;
}

.icon-btn,
.doc-more-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  color: var(--text-muted);
  background: transparent;
  border: 0;
  border-radius: 6px;
  cursor: pointer;
}

.icon-btn {
  width: 26px;
  height: 26px;
}

.danger-btn:hover {
  color: var(--danger);
  background: var(--danger-soft);
}

.doc-list {
  flex: 1;
  min-height: 0;
  padding: 2px 8px 14px;
  overflow-x: hidden;
  overflow-y: auto;
}

.doc-list::-webkit-scrollbar {
  width: 5px;
}

.doc-list::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border-radius: 8px;
}

.doc-list-item,
.session-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  min-height: 58px;
  padding: 8px 6px 8px 8px;
  margin-bottom: 2px;
  outline: none;
  border-radius: 9px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.doc-list-item:hover,
.doc-list-item:focus-visible,
.session-item:hover,
.session-item:focus-visible {
  background: var(--bg-hover);
}

.doc-icon {
  display: flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 34px;
  color: var(--text-muted);
  font-size: 17px;
  background: var(--bg-app);
  border-radius: 7px;
}

.doc-list-content {
  flex: 1;
  min-width: 0;
}

.doc-name {
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  line-height: 20px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.doc-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  overflow: hidden;
  color: var(--text-muted);
  font-size: 10px;
  line-height: 16px;
  white-space: nowrap;
}

.status-dot {
  width: 5px;
  height: 5px;
  background: var(--text-muted);
  border-radius: 50%;
}

.status-dot.ready {
  background: var(--success);
}

.doc-more-btn {
  flex: none;
  width: 28px;
  height: 28px;
  opacity: 0;
}

.doc-list-item:hover .doc-more-btn,
.session-item:hover .doc-more-btn,
.doc-more-btn:focus {
  opacity: 1;
}

.doc-more-btn:hover {
  color: var(--text-primary);
  background: var(--bg-hover);
}

.doc-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 44px 12px;
  color: var(--text-muted);
  font-size: 12px;
  text-align: center;
}

.doc-empty > .el-icon {
  font-size: 24px;
}

.doc-empty button {
  padding: 4px 0;
  color: var(--text-primary);
  font: inherit;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.aside-bottom {
  flex: none;
  padding: 8px;
  border-top: 1px solid var(--border);
}

.utility-item {
  display: flex;
  align-items: center;
  gap: 9px;
  height: 38px;
  padding: 0 10px;
  margin-bottom: 2px;
  color: var(--text-secondary);
  font-size: 13px;
  background: transparent;
  border-radius: 8px;
}

.utility-item:hover,
.user-profile:hover {
  background: var(--bg-hover);
}

.aside-bottom :deep(.el-dropdown) {
  width: 100%;
}

.user-profile {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-height: 42px;
  padding: 6px 8px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.user-avatar {
  color: #fff;
  font-weight: 500;
  background: var(--brand);
}

.username {
  flex: 1;
  overflow: hidden;
  color: var(--text-primary);
  font-size: 13px;
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.more-icon {
  color: var(--text-muted);
}

.feishu-main.is-pane { overflow: hidden; }

.feishu-main {
  display: flex;
  justify-content: center;
  padding: 0;
  overflow-y: auto;
  background: var(--bg-raised);
}

.main-container {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  max-width: 1080px;
  min-height: 100%;
  padding: 28px 44px 96px;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.section-header h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 17px;
  font-weight: 500;
  color: var(--text-primary);
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.header-actions .doc-search {
  width: 240px;
  margin: 0;
}

.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(216px, 1fr));
  gap: 16px;
}

.doc-card {
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--border);
  border-radius: 12px;
  transition: box-shadow 0.18s ease, transform 0.18s ease;
}

.doc-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(20, 20, 19, 0.1);
}

.card-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 104px;
  color: var(--brand);
  cursor: pointer;
  background: var(--bg-sunken);
  border-bottom: 1px solid var(--border);
  transition: background 0.18s ease;
}

.doc-card:hover .card-cover {
  background: var(--ink-soft);
}

.card-info {
  padding: 12px 14px 14px;
}

.info-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.info-top .doc-name {
  cursor: pointer;
}

.more-btn {
  color: var(--text-muted);
  cursor: pointer;
}

.more-btn:hover {
  color: var(--text-primary);
}

.card-info .doc-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.doc-section .doc-empty {
  padding: 48px 0;
}

@media (max-width: 860px) {
  .feishu-aside {
    width: 250px !important;
  }

  .main-container {
    padding-right: 24px;
    padding-left: 24px;
  }
}
</style>
