import router from '../router'
import { ElMessage } from 'element-plus'
import { openInCowork } from '../composables/dashboardMode'

/** Agent 新建的就是一篇正式文档，跟上传的走同一个编辑器。 */
export function openDocument(card) {
  if (!card?.documentId) {
    ElMessage.info('This run didn’t create a document')
    return
  }
  if (router.currentRoute.value.path.startsWith('/dashboard')) {
    openInCowork(card.documentId)
    return
  }
  router.push(`/editor/${card.documentId}`)
}
