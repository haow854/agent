/** 全局助理抽屉的开合。悬浮球和抽屉自己都要读它，所以放在模块作用域。 */
import { ref } from 'vue'

const isVisible = ref(false)

export function useAiPanel() {
  return {
    isVisible,
    toggle: () => { isVisible.value = !isVisible.value }
  }
}
