<template>
  <main class="chat-main">
      <div
          class="chat-scroll-area"
          ref="scrollArea"
          :style="{ paddingBottom: inputInset + 'px' }"
          v-loading="historyLoading">
        <div class="chat-content-container">
          <div v-if="!messages.length" class="pane-greeting">
            <span class="greeting-mark">◈</span>
            <h1>Welcome back，{{ userName }}</h1>
          </div>

          <template v-for="(msg, i) in messages" :key="i">
            <div :class="['message-row', msg.role]">
              <div class="avatar-col" v-if="msg.role === 'ai'">
                <el-avatar :size="36" class="ai-avatar">◈</el-avatar>
              </div>
              <div class="message-content-col">
                <template v-if="msg.role === 'user'">
                  <div class="user-bubble preserve-format">{{ msg.text }}</div>
                </template>

                <div v-else class="ai-structured-card">
                  <AgentSteps :steps="msg.steps" />
                  <div v-if="msg.text" class="card-body preserve-format">{{ msg.text }}</div>

                  <ApprovalPrompt
                      v-if="msg.approvals?.length"
                      :approvals="msg.approvals"
                      @approve="approve(msg)"
                      @deny="deny(msg)"
                  />
                  <div v-else-if="msg.approvalOutcome" class="approval-outcome" :class="msg.approvalOutcome">
                    {{ msg.approvalOutcome === 'approved' ? 'You approved this action' : 'You rejected this action — it did not run' }}
                  </div>

                  <div v-if="msg.actionResult" class="file-card" @click="openDocument(msg.actionResult)">
                    <el-icon size="28" color="var(--brand)"><Document /></el-icon>
                    <div>
                      <div class="file-name">{{ msg.actionResult.title }}</div>
                      <div class="file-desc">Open</div>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>

          <div v-if="isThinking" class="message-row ai">
            <div class="avatar-col"><el-avatar :size="36" class="ai-avatar">◈</el-avatar></div>
            <div class="message-content-col">
              <div class="ai-structured-card thinking">
                <AgentSteps :steps="steps" />
                <div class="thinking-line">
                  <el-icon class="is-loading"><Loading /></el-icon> Working…
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="chat-input-container" ref="inputContainer">
        <div class="input-wrapper-inner" :class="{ 'is-focused': isInputFocused }">
          <input
              v-model="userMsg"
              class="chat-input"
              placeholder="Ask anything, or give me a task…"
              @keyup.enter="handleSend"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
          />
          <div class="send-btn-circle" :class="{ active: userMsg.trim().length > 0 }" @click="handleSend">
            <el-icon size="20"><Top /></el-icon>
          </div>
        </div>

        <div class="input-toolbar">
          <DocPicker v-model="attachedDoc" />

          <span v-if="attachedDoc" class="attach-hint">Reads and writes stay within this document</span>

          <span class="toolbar-spacer"></span>

          <el-dropdown trigger="click" placement="top-end" @command="(code) => currentModel = code">
            <button class="model-trigger" type="button">
              <span>{{ currentModelName }}</span>
              <el-icon><ArrowDown /></el-icon>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item
                    v-for="m in modelOptions"
                    :key="m.code"
                    :command="m.code"
                    :disabled="m.available === false"
                >
                  {{ m.name }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>

        <div class="ai-hint">AI can make mistakes. Double-check important details.</div>
      </div>
  </main>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useAgentChat } from '../../composables/useAgentChat'
import { useAgentSessions } from '../../composables/useAgentSessions'
import { useAiModels } from '../../composables/useAiModels'
import { openDocument } from '../../utils/openDocument'
import DocPicker from '../components/DocPicker.vue'
import AgentSteps from '../components/AgentSteps.vue'
import ApprovalPrompt from '../components/ApprovalPrompt.vue'

defineProps({
  userName: { type: String, default: 'User' }
})

const { messages, steps, isThinking, historyLoading, loadHistory, ask, approve, deny } = useAgentChat()
const { fetchSessions } = useAgentSessions()
const { modelOptions, currentModel } = useAiModels()

const userMsg = ref('')
const isInputFocused = ref(false)
// 这一轮要操作的文档，不挂就是纯对话
const attachedDoc = ref(null)

const currentModelName = computed(() => {
  const hit = modelOptions.value.find(m => m.code === currentModel.value)
  return hit ? hit.name : currentModel.value
})

/**
 * 输入区悬浮在对话流之上（absolute + bottom:0），滚动区必须自己空出等高的一块，
 * 否则最后几行永远压在输入框底下、怎么滚都露不出来。
 *
 * 这个高度不是常数：挂上文档会多一行 chip 和提示，chip 长了还会换行。原来写死
 * 160px，加了挂载行之后就不够了。这里按实际测量值让位，以后再往输入区加东西
 * 也不用回来改数字。
 */
const inputContainer = ref(null)
const inputInset = ref(160)
let inputResizeObserver = null

/** 多留 16px，别让最后一条正好贴着输入框的上沿 */
const measureInputInset = () => {
  if (inputContainer.value) inputInset.value = inputContainer.value.offsetHeight + 16
}

const handleSend = async () => {
  const task = userMsg.value.trim()
  if (!task || isThinking.value) return
  userMsg.value = ''
  scrollToBottom()

  await ask({
    task,
    model: currentModel.value,
    documentId: attachedDoc.value?.id
  })
  scrollToBottom()
  // 第一轮之后会话才存在，列表里才有它
  fetchSessions()
}

const scrollToBottom = () => {
  nextTick(() => {
    const c = document.querySelector('.chat-scroll-area')
    if (c) c.scrollTop = c.scrollHeight
  })
}

watch(messages, scrollToBottom, { deep: true })

onMounted(() => {
  fetchSessions()
  if (!messages.value.length) loadHistory()

  // 先量一次。ResizeObserver 的首次回调不保证送达，只靠它的话让位会一直停在兜底值。
  nextTick(measureInputInset)
  if (inputContainer.value) {
    inputResizeObserver = new ResizeObserver(() => {
      const area = document.querySelector('.chat-scroll-area')
      // 贴着底看的人，让位高度变了之后还得贴着底，否则挂个文档就把最新那条顶出视野
      const wasAtBottom = area
          ? area.scrollHeight - area.scrollTop - area.clientHeight < 4
          : false
      measureInputInset()
      if (wasAtBottom) scrollToBottom()
    })
    inputResizeObserver.observe(inputContainer.value)
  }
})

onBeforeUnmount(() => {
  inputResizeObserver?.disconnect()
})
</script>

<style scoped>
.preserve-format { white-space: pre-wrap !important; word-break: break-word; text-align: left; }


.chat-main { flex: 1; height: 100%; display: flex; flex-direction: column; position: relative; background: #fff; min-width: 0; }
.pane-greeting { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.greeting-mark { font-size: 26px; line-height: 1; color: var(--brand); }
.pane-greeting h1 { margin: 0; font-size: 26px; font-weight: 500; color: var(--text-primary); }

/*
 * 这里刻意不用 flex + justify-content 居中。
 *
 * 那样写的话 .chat-content-container 会被当成 flex 子项拉伸到容器高度，消息就整片
 * 溢出到它的盒子外面，滚动区的 padding-bottom 也就挡不住任何东西——滚到底时最后
 * 一条会整条压在悬浮输入框底下。居中改用 margin auto，滚动区退回普通块，底部留白
 * 才真的占得住位置。
 *
 * padding-bottom 的值由 inputInset 覆盖，这里的 160px 只是首帧测量前的兜底。
 */
.chat-scroll-area { flex: 1; overflow-y: auto; padding: 40px 0 160px 0; }
.chat-content-container { width: 100%; max-width: 850px; margin: 0 auto; padding: 0 24px; display: flex; flex-direction: column; }

.message-row { display: flex; gap: 16px; margin-bottom: 32px; }
.message-row.user { flex-direction: row-reverse; }
.message-content-col { max-width: 80%; }
.ai-avatar { background: var(--brand); color: #fff; font-size: 19px; }

.user-bubble { background: var(--bg-sunken); color: var(--text-primary); padding: 14px 20px; border-radius: 16px 4px 16px 16px; font-size: 15px; line-height: 1.6; box-shadow: 0 2px 8px rgba(20, 20, 19, 0.12); }
.ai-structured-card {
  background: #fff; border: 1px solid var(--border); border-radius: 4px 16px 16px 16px;
  padding: 24px; box-shadow: 0 6px 18px rgba(0,0,0,0.04);
  font-size: 15px; line-height: 1.8; color: var(--text-primary);
  display: flex; flex-direction: column; align-items: stretch; gap: 14px;
}
.ai-structured-card.thinking { color: var(--text-muted); font-style: italic; gap: 10px; padding: 16px 24px; }
.thinking-line { display: flex; align-items: center; gap: 10px; }


.approval-outcome { font-size: 13px; line-height: 1.6; color: var(--text-muted); }
.approval-outcome.denied { color: var(--danger); }

.file-card {
  display: flex; align-items: center; gap: 12px; background: var(--bg-sunken); padding: 12px 16px;
  border-radius: 8px; cursor: pointer; margin-top: 16px; transition: background 0.2s;
}
.file-card:hover { background: var(--bg-hover); }
.file-name { font-weight: 500; font-size: 14px; color: var(--text-primary); }
.file-desc { font-size: 12px; color: var(--text-muted); margin-top: 4px; }

/* 底部悬浮输入框 */
.chat-input-container {
  position: absolute; bottom: 0; left: 0; width: 100%;
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 0 32px 0; background: linear-gradient(to top, #fff 80%, rgba(255,255,255,0));
}
.attach-hint { font-size: 12px; color: var(--text-muted); }
.input-wrapper-inner {
  width: 100%; max-width: 800px; min-height: 56px; background: #fff; border-radius: 16px;
  box-shadow: 0 1px 2px rgba(20, 20, 19, 0.04); display: flex; align-items: center; gap: 10px;
  padding: 0 10px 0 18px; border: 1px solid var(--border); transition: 0.18s ease;
}
.input-wrapper-inner:hover { border-color: var(--border-strong); }
.input-wrapper-inner.is-focused {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.08);
}
.chat-input { flex: 1; border: none; outline: none; font-size: 15px; color: var(--text-primary); background: transparent; }
.chat-input::placeholder { color: var(--text-muted); }

.input-toolbar {
  width: 100%; max-width: 800px; display: flex; align-items: center; flex-wrap: wrap;
  gap: 8px; margin-top: 10px; padding: 0 6px;
}

.toolbar-spacer { flex: 1 1 auto; }

.model-trigger {
  display: inline-flex; align-items: center; gap: 5px; height: 28px; padding: 0 8px;
  font-size: 13px; color: var(--text-secondary); background: transparent; border: none;
  border-radius: 8px; cursor: pointer; transition: 0.18s ease;
}
.model-trigger:hover { color: var(--text-primary); background: var(--bg-sunken); }
.model-trigger .el-icon { font-size: 13px; color: var(--text-muted); }

.send-btn-circle {
  width: 32px; height: 32px; border-radius: 10px; background: transparent; color: var(--text-muted);
  display: flex; justify-content: center; align-items: center; cursor: pointer;
  transition: 0.18s ease; flex-shrink: 0;
}
.send-btn-circle.active { background: var(--brand); color: #fff; }
.ai-hint { font-size: 12px; color: var(--text-muted); margin-top: 16px; }
</style>
