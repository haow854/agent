/**
 * 实时协同状态（yjs + Hocuspocus）。
 *
 * 模块级单例：同一时刻只协同一篇文档。谁打开文档谁负责启停，面板只负责展示
 * 和授权。加入协同后，同步与合并全部由 yjs 处理，持久化由 collab-server
 * 防抖回存到 Spring。
 */
import { ref, shallowRef, markRaw } from 'vue'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { getAccessToken } from '../utils/session'

const USER_COLORS = [
  '#d97757', '#f56c6c', '#67c23a', '#e6a23c', '#909399',
  '#b37feb', '#36cfc9', '#ff85c0', '#597ef7', '#ffc53d'
]

let colorIndex = 0
const userColorMap = {}

function userColor(userId) {
  if (!userColorMap[userId]) {
    userColorMap[userId] = USER_COLORS[colorIndex % USER_COLORS.length]
    colorIndex++
  }
  return userColorMap[userId]
}

function collabUrl() {
  const explicit = import.meta.env.VITE_COLLAB_URL
  if (explicit) return explicit
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/collab`
}

// provider 持有 yjs Doc，绝不能被 Vue 深度代理（yjs 依赖对象身份判断）
const provider = shallowRef(null)
const isCollaborating = ref(false)
const connectionError = ref('')
const collabUser = ref(null)
let activeDocumentId = null

/**
 * 连上这篇文档。换文档就是断掉重连——参数在这里传，不在 useCollaboration
 * 里闭包住，否则换了文档还连着上一篇。
 */
export function startCollaboration({ documentId, userId, userName }) {
  const id = String(documentId || '')
  if (!id) return
  if (isCollaborating.value && activeDocumentId === id) return

  stopCollaboration()
  connectionError.value = ''
  activeDocumentId = id
  collabUser.value = {
    name: userName || `User ${userId}`,
    color: userColor(String(userId))
  }

  const instance = new HocuspocusProvider({
    url: collabUrl(),
    name: id,
    token: getAccessToken() || 'anonymous',
    onAuthenticationFailed: ({ reason }) => {
      connectionError.value = `Collaboration auth failed: ${reason || 'no permission'}`
      stopCollaboration()
    }
  })

  provider.value = markRaw(instance)
  isCollaborating.value = true
}

export function stopCollaboration() {
  const instance = provider.value
  if (instance) {
    try { instance.destroy() } catch { /* 已断开 */ }
  }
  provider.value = null
  isCollaborating.value = false
  activeDocumentId = null
}

export function useCollaboration() {
  return {
    provider,
    collabUser,
    connectionError,
    startCollaboration,
    stopCollaboration
  }
}
