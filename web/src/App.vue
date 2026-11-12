<template>
  <router-view />

  <GlobalAiAssistant v-if="showAiAssistant" />
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import GlobalAiAssistant from './views/components/GlobalAiAssistant.vue'
import { isLoggedIn } from './utils/session'

const route = useRoute()

// 自己已经有对话入口的页面不再挂抽屉：/chat 是对话页本身，/editor 右侧就是
// 助理栏，两个界面共享同一段对话，叠在一起只会让人分不清在跟谁说话。
const OWN_AI_ENTRY = ['/editor', '/dashboard']

const showAiAssistant = computed(() => {
  if (!isLoggedIn.value) return false
  return !OWN_AI_ENTRY.some(path => route.path.startsWith(path))
})
</script>

<style>
</style>
