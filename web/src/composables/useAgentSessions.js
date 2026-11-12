import { ref } from 'vue'
import { agentApi } from '../api/agent'

const sessionList = ref([])
const sessionsLoading = ref(false)

async function fetchSessions() {
  sessionsLoading.value = true
  try {
    const res = await agentApi.listSessions()
    sessionList.value = (res.data || []).map(s => ({
      sessionId: s.sessionId,
      title: s.title || 'New chat',
      updatedAt: Number(s.updatedAt) || 0,
      documentId: s.activeDocumentId || ''
    }))
  } catch (e) {
    console.error('Could not load chats', e)
  } finally {
    sessionsLoading.value = false
  }
}

export function useAgentSessions() {
  return { sessionList, sessionsLoading, fetchSessions }
}
