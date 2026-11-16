/**
 * 可选模型列表，全应用一份、只拉一次。模型名和是否可用都以后端为准。
 */
import { ref } from 'vue'
import { aiApi } from '../api/ai'
import { DEFAULT_MODEL } from '../constants'

const modelOptions = ref([])
const currentModel = ref(DEFAULT_MODEL)
let loading = null

export function useAiModels() {
  if (!loading) {
    loading = aiApi.getModels()
        .then(res => {
          modelOptions.value = res.data
        })
        .catch(() => {
          // 失败不缓存（比如登录过期被拒），重新登录后下一个用到它的组件会再拉一次
          loading = null
        })
  }
  return { modelOptions, currentModel }
}
