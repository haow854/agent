<template>
  <div class="collaborate-bar">
    <div class="bar-item">
      <el-icon><Connection /></el-icon>
      <span class="bar-title">Collaboration</span>
    </div>

    <div class="bar-item bar-error" v-if="connectionError">
      <el-icon><WarningFilled /></el-icon>
      <span>{{ connectionError }}</span>
      <button class="retry-btn" type="button" @click="emit('retry')">Retry</button>
    </div>

    <div
      class="bar-item"
      title="Shared documents appear in the other person’s list. The file stays in the owner’s storage."
    >
      <el-input
        v-model="collaboratorQuery"
        size="small"
        clearable
        placeholder="Share with username or ID"
        style="width: 160px;"
        @keyup.enter="handleGrant"
      />
      <el-select v-model="collaboratorRole" size="small" style="width: 96px;">
        <el-option label="Can edit" value="editor" />
        <el-option label="Read only" value="viewer" />
      </el-select>
      <el-button type="primary" size="small" :loading="granting" @click="handleGrant">
        Share
      </el-button>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { docApi } from '../api/document'
import { userApi } from '../api/user'
import { useCollaboration } from './useCollaboration'

const props = defineProps({
  documentId: { type: String, required: true },
  userId: { type: String, default: '1' }
})

const emit = defineEmits(['retry'])

const { connectionError } = useCollaboration()

const collaboratorQuery = ref('')
const collaboratorRole = ref('editor')
const granting = ref(false)

async function handleGrant() {
  const query = collaboratorQuery.value.trim()
  if (!query) {
    ElMessage.warning('Enter a username or user ID')
    return
  }

  granting.value = true
  try {
    let collaboratorUserId = query
    if (!/^\d+$/.test(query)) {
      const userRes = await userApi.getUserByUsername(query)
      collaboratorUserId = userRes?.data?.id
    }

    if (!collaboratorUserId) {
      ElMessage.error('No such user')
      return
    }
    if (String(collaboratorUserId) === String(props.userId)) {
      ElMessage.warning('You can’t share a document with yourself')
      return
    }

    await docApi.grantCollaborator(props.documentId, collaboratorUserId, collaboratorRole.value)
    ElMessage.success('Shared')
    collaboratorQuery.value = ''
  } catch (e) {
    ElMessage.error(e?.message || 'Could not share')
  } finally {
    granting.value = false
  }
}

</script>

<style scoped>
.collaborate-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bar-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--text-secondary);
}

.bar-item .el-icon {
  color: var(--brand);
  font-size: 14px;
}

.bar-title {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

.bar-error {
  color: var(--danger);
  background: var(--danger-soft);
  border-radius: 4px;
  padding: 2px 8px;
}

.bar-error .el-icon {
  color: var(--danger);
}

.retry-btn {
  border: none;
  background: transparent;
  color: var(--danger);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 0 2px;
}

</style>
