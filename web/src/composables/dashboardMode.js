import { ref } from 'vue'

export const dashboardMode = ref('chat')
export const coworkDocId = ref('')

export function openInCowork(documentId) {
  coworkDocId.value = String(documentId || '')
  dashboardMode.value = 'cowork'
}
