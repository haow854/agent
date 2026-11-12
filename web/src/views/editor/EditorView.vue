<template>
  <div class="editor-page">
    <EditorPane :document-id="route.params.id">
      <template #leading>
        <el-button icon="ArrowLeft" link @click="router.push('/dashboard')"></el-button>
        <el-divider direction="vertical" />
      </template>
    </EditorPane>
  </div>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import EditorPane from './EditorPane.vue'

const route = useRoute()
const router = useRouter()
</script>

<style scoped>
.editor-page { height: 100vh; }
</style>
