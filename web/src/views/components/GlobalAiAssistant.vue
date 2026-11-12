<template>
  <div>
    <div class="global-ai-fab" @click="toggle">◈</div>

    <el-drawer
        v-model="isVisible"
        size="400px"
        :modal="false"
        :with-header="false"
        class="global-ai-drawer"
    >
      <div class="chat-layout">
        <div class="drawer-header">
          <div class="header-title">
            <div class="ai-icon-wrapper">◈</div>
            <span>Docs Assistant</span>
          </div>

          <div class="header-actions">
            <el-select v-model="currentModel" size="small" placeholder="Model" style="width: 120px;">
              <el-option v-for="m in modelOptions" :key="m.code" :label="m.name" :value="m.code" />
            </el-select>
            <el-button link icon="Plus" title="Start a new chat" @click="handleNewSession" />
            <el-button link icon="Close" title="Collapse" @click="toggle" />
          </div>
        </div>

        <div class="chat-scroll-area" v-loading="historyLoading">
          <div class="message-row ai" v-if="!messages.length">
            <div class="avatar-box ai-avatar">◈</div>
            <div class="bubble ai-bubble">
              Hi, I’m the Docs assistant. Ask me to manage your documents, clean up the trash, or look things up across files.
            </div>
          </div>

          <template v-for="(msg, i) in messages" :key="i">
            <div :class="['message-row', msg.role]">
              <div class="avatar-box" :class="msg.role === 'user' ? 'user-avatar' : 'ai-avatar'">
                <el-icon v-if="msg.role === 'user'" size="16" color="#fff"><User /></el-icon>
                <template v-else>◈</template>
              </div>

              <div class="message-content" :class="{ 'needs-room': msg.approvals?.length }">
                <AgentSteps :steps="msg.steps" />
                <div
                    v-if="msg.text"
                    :class="['bubble', msg.role === 'user' ? 'user-bubble' : 'ai-bubble', 'preserve-format']">
                  {{ msg.text }}
                </div>

                <ApprovalPrompt
                    v-if="msg.approvals?.length"
                    :approvals="msg.approvals"
                    @approve="approve(msg)"
                    @deny="deny(msg)"
                />
                <div v-else-if="msg.approvalOutcome" class="approval-outcome" :class="msg.approvalOutcome">
                  {{ msg.approvalOutcome === 'approved' ? 'You approved this action' : 'You rejected this action — it did not run' }}
                </div>

                <div v-if="msg.actionResult" class="agent-file-card" @click="openDocument(msg.actionResult)">
                  <el-icon size="24" color="var(--brand)"><Document /></el-icon>
                  <div class="file-info">
                    <div class="file-name">{{ msg.actionResult.title }}</div>
                    <div class="file-desc">Open</div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <div v-if="isThinking" class="message-row ai">
            <div class="avatar-box ai-avatar">◈</div>
            <div class="message-content">
              <AgentSteps :steps="steps" />
              <div class="bubble ai-bubble thinking">
                <el-icon class="is-loading"><Loading /></el-icon> Working…
              </div>
            </div>
          </div>
        </div>

        <div class="input-area">
          <div class="attach-row">
            <DocPicker v-model="attachedDoc" />
          </div>

          <div class="input-wrapper" :class="{ 'is-focused': isInputFocused }">
            <input
                v-model="userInput"
                class="custom-input"
                placeholder="Ask me to look something up, clean up files…"
                @keyup.enter="handleSend"
                @focus="isInputFocused = true"
                @blur="isInputFocused = false"
            />
            <div class="send-btn" :class="{ active: userInput.trim().length > 0 }" @click="handleSend">
              <el-icon size="18"><Top /></el-icon>
            </div>
          </div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAiPanel } from '../../composables/useAiPanel'
import { useAgentChat } from '../../composables/useAgentChat'
import { useAiModels } from '../../composables/useAiModels'
import { openDocument } from '../../utils/openDocument'
import DocPicker from './DocPicker.vue'
import AgentSteps from './AgentSteps.vue'
import ApprovalPrompt from './ApprovalPrompt.vue'

const route = useRoute()
const { isVisible, toggle } = useAiPanel()
const { messages, steps, isThinking, historyLoading, startNewSession, loadHistory, ask, approve, deny } = useAgentChat()
const { modelOptions, currentModel } = useAiModels()

const userInput = ref('')
const isInputFocused = ref(false)
const attachedDoc = ref(null)

/**
 * 站在编辑器页面上就默认挂This document。
 *
 * 以前这是隐式的——documentId 直接从路由里取，用户看不见也换不掉。现在它是
 * 一个挂上的 chip：默认值不变，但摘得掉，也能换成别的文档。
 */
watch(() => route.params.id || route.query.documentId, (id) => {
  if (!id) return
  if (attachedDoc.value?.id === String(id)) return
  attachedDoc.value = { id: String(id), title: 'This document' }
}, { immediate: true })

const scrollToBottom = () => {
  nextTick(() => {
    const c = document.querySelector('.global-ai-drawer .chat-scroll-area')
    if (c) c.scrollTop = c.scrollHeight
  })
}

watch(messages, scrollToBottom, { deep: true })
watch(isVisible, (open) => { if (open && !messages.value.length) loadHistory() })

onMounted(loadHistory)

const handleSend = async () => {
  const task = userInput.value.trim()
  if (!task || isThinking.value) return
  userInput.value = ''

  await ask({
    task,
    model: currentModel.value,
    documentId: attachedDoc.value?.id
  })
}

const handleNewSession = () => {
  ElMessageBox.confirm('Start a new chat? The current one will be cleared.', 'New chat', {
    confirmButtonText: 'OK', cancelButtonText: 'Cancel', type: 'warning'
  }).then(() => {
    startNewSession()
    ElMessage.success('Started a new chat')
  }).catch(() => {})
}
</script>

<style scoped>
.preserve-format { white-space: pre-wrap !important; }

:deep(.el-drawer__body) { padding: 0; overflow: hidden; }

/* 悬浮球 */
.global-ai-fab {
  position: fixed; bottom: 40px; right: 40px; width: 52px; height: 52px;
  border-radius: 50%; background: var(--brand); color: white;
  display: flex; justify-content: center; align-items: center;
  box-shadow: 0 4px 16px rgba(217, 119, 87, 0.3); cursor: pointer; z-index: 9999; transition: all 0.25s;
}
.global-ai-fab:hover { transform: scale(1.06); background: var(--brand-strong); }

.chat-layout { display: flex; flex-direction: column; height: 100%; background: var(--bg-sunken); }

.drawer-header {
  height: 56px; padding: 0 16px; background: #fff;
  display: flex; justify-content: space-between; align-items: center;
  border-bottom: 1px solid var(--border); z-index: 10;
}
.header-title { display: flex; align-items: center; gap: 8px; font-size: 15px; font-weight: 500; color: var(--text-primary); }
.ai-icon-wrapper {
  width: 24px; height: 24px; background: var(--brand); border-radius: 6px;
  color: #fff; font-size: 13px;
  display: flex; justify-content: center; align-items: center;
}
.header-actions { display: flex; align-items: center; gap: 4px; }
.header-actions .el-button { padding: 4px; font-size: 16px; }

.chat-scroll-area { flex: 1; overflow-y: auto; padding: 20px; display: flex; flex-direction: column; gap: 16px; }

.message-row { display: flex; gap: 12px; align-items: flex-start; }
.message-row.user { flex-direction: row-reverse; }
.message-content { max-width: 78%; display: flex; flex-direction: column; gap: 6px; }
.message-content.needs-room { max-width: 100%; }

.avatar-box { width: 32px; height: 32px; border-radius: 50%; display: flex; justify-content: center; align-items: center; flex-shrink: 0; }
.ai-avatar { background: var(--brand); color: #fff; font-size: 17px; box-shadow: 0 2px 6px rgba(217, 119, 87, 0.2); }
.user-avatar { background: var(--text-secondary); box-shadow: 0 2px 6px rgba(20, 20, 19, 0.12); }

.bubble { padding: 10px 14px; font-size: 14px; line-height: 1.5; border-radius: 8px; word-break: break-word; }
.user-bubble { background: var(--bg-sunken); color: var(--text-primary); border-top-right-radius: 2px; }
.ai-bubble { background: #fff; color: var(--text-primary); border-top-left-radius: 2px; border: 1px solid var(--border); }
.ai-bubble.thinking { color: var(--text-muted); display: flex; align-items: center; gap: 6px; background: var(--bg-sunken); }

.approval-outcome { font-size: 12px; line-height: 1.6; color: var(--text-muted); }
.approval-outcome.denied { color: var(--danger); }


.agent-file-card {
  display: flex; align-items: center; gap: 10px; background: #fff; border: 1px solid var(--border);
  padding: 10px 14px; border-radius: 6px; cursor: pointer; transition: all 0.2s;
}
.agent-file-card:hover { border-color: var(--border-strong); background: var(--ink-soft); }
.file-info .file-name { font-weight: 500; font-size: 13px; color: var(--text-primary); }
.file-info .file-desc { font-size: 11px; color: var(--text-muted); margin-top: 2px; }

.input-area { background: #fff; padding: 14px 16px; border-top: 1px solid var(--border); }
.attach-row { display: flex; align-items: center; margin-bottom: 8px; }
.input-wrapper {
  display: flex; align-items: center; background: var(--bg-sunken); border-radius: 20px;
  padding: 4px 4px 4px 14px; border: 1px solid transparent; transition: all 0.2s;
}
.input-wrapper.is-focused { border-color: var(--brand); background: #fff; box-shadow: 0 0 0 2px rgba(217,119,87,0.08); }
.custom-input { flex: 1; border: none; background: transparent; outline: none; font-size: 14px; color: var(--text-primary); }
.send-btn {
  width: 28px; height: 28px; border-radius: 50%; background: var(--border); color: #fff;
  display: flex; justify-content: center; align-items: center; cursor: not-allowed; transition: all 0.2s;
}
.send-btn.active { background: var(--brand); cursor: pointer; }
.send-btn.active:hover { background: var(--brand-strong); }
</style>
