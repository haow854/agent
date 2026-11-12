/**
 * 可选模型列表，全应用一份、只拉一次。
 *
 * 以前每个对话入口各自 fetch 一遍、各自带一份兜底数组，改一处漏一处。
 */
import { ref } from 'vue'
import { aiApi } from '../api/ai'
import { DEFAULT_MODEL } from '../constants'

// 接口不通时的保底，至少让下拉框不是空的
const FALLBACK_MODELS = [
  { code: DEFAULT_MODEL, name: 'DeepSeek V4 Pro', available: true },
  { code: 'glm-5', name: 'Zhipu GLM-5', available: true },
  { code: 'qwen3.6-plus', name: 'Qwen Plus', available: true }
]

const modelOptions = ref([])
const currentModel = ref(DEFAULT_MODEL)
let loading = null

export function useAiModels() {
  if (!loading) {
    loading = aiApi.getModels()
        .then(res => {
          modelOptions.value = res.data?.length ? res.data : FALLBACK_MODELS
        })
        .catch(() => {
          modelOptions.value = FALLBACK_MODELS
        })
  }
  return { modelOptions, currentModel, ready: loading }
}
