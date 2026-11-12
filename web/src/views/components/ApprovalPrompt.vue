<template>
  <div class="approval-prompt" ref="root" tabindex="0" @keydown="handleKeydown">
    <div class="prompt-head">
      <el-icon><WarnTriangleFilled /></el-icon>
      <span class="head-title">Approval needed</span>
      <span v-if="countdown" class="head-countdown">{{ countdown }} left</span>
    </div>

    <div class="prompt-calls">
      <div v-for="call in approvals" :key="call.toolCallId" class="call">
        <div class="call-name">{{ call.toolName }}</div>
        <div v-for="[key, value] in paramsOf(call)" :key="key" class="call-param">
          <span class="param-key">{{ key }}</span>
          <span class="param-value">{{ value }}</span>
        </div>
      </div>
    </div>

    <template v-if="expired">
      <div class="prompt-expired">This approval expired and the action did not run. Send the instruction again.</div>
    </template>

    <template v-else>
      <div class="prompt-question">Do you want to proceed?</div>
      <div class="prompt-options">
        <button
            v-for="(option, i) in OPTIONS"
            :key="option.value"
            class="option"
            :class="[option.value, { selected: cursor === i }]"
            type="button"
            @mouseenter="cursor = i"
            @click="emit(option.value)"
        >
          <span class="option-caret">{{ cursor === i ? '❯' : '' }}</span>
          <span class="option-index">{{ i + 1 }}.</span>
          <span class="option-label">{{ option.label }}</span>
        </button>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  approvals: { type: Array, required: true }
})

const emit = defineEmits(['approve', 'deny'])

const OPTIONS = [
  { value: 'approve', label: 'Yes, run it' },
  { value: 'deny', label: 'No, don’t run it' }
]

const root = ref(null)
const cursor = ref(0)
const now = ref(Date.now())
let ticker = null

const expiresAt = computed(() => {
  const stamps = props.approvals
      .map(a => a.expiresAt)
      .filter(t => typeof t === 'number' && t > 0)
  return stamps.length ? Math.min(...stamps) : 0
})

const remainMs = computed(() =>
    expiresAt.value ? expiresAt.value - now.value : Number.POSITIVE_INFINITY)

const expired = computed(() => remainMs.value <= 0)

const countdown = computed(() => {
  if (!expiresAt.value || expired.value) return ''
  const total = Math.ceil(remainMs.value / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
})

const paramsOf = (call) => Object.entries(call.params || {})
    .map(([key, value]) => [key, typeof value === 'string' ? value : JSON.stringify(value)])

function handleKeydown(e) {
  if (expired.value) return
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault()
    const step = e.key === 'ArrowDown' ? 1 : OPTIONS.length - 1
    cursor.value = (cursor.value + step) % OPTIONS.length
  } else if (e.key === 'Enter') {
    e.preventDefault()
    emit(OPTIONS[cursor.value].value)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    emit('deny')
  } else if (e.key >= '1' && e.key <= String(OPTIONS.length)) {
    e.preventDefault()
    emit(OPTIONS[Number(e.key) - 1].value)
  }
}

onMounted(() => {
  root.value?.focus({ preventScroll: false })
  if (expiresAt.value) ticker = setInterval(() => { now.value = Date.now() }, 1000)
})

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})
</script>

<style scoped>
.approval-prompt {
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--bg-raised);
  padding: 14px 16px;
  outline: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-style: normal;
}

.approval-prompt:focus-visible {
  border-color: var(--brand);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.14);
}

.prompt-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  line-height: 1.4;
}

.prompt-head .el-icon {
  color: var(--brand);
  font-size: 16px;
}

.head-title {
  font-weight: 500;
  color: var(--text-primary);
}

.head-countdown {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.prompt-calls {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--bg-sunken);
  border: 1px solid var(--border);
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.call-name {
  color: var(--text-primary);
  font-weight: 500;
}

.call-param {
  display: flex;
  gap: 10px;
  align-items: baseline;
}

.param-key {
  flex: 0 0 auto;
  color: var(--text-muted);
}

.param-value {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--text-secondary);
  word-break: break-all;
}

.prompt-question {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.4;
}

.prompt-options {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.option {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 14px;
  line-height: 1.4;
  color: var(--text-secondary);
  text-align: left;
  cursor: pointer;
  transition: 0.15s ease;
}

.option.selected {
  background: var(--ink-soft);
  color: var(--text-primary);
}

.option.selected.deny {
  background: var(--danger-soft);
  color: var(--danger);
}

.option-caret {
  flex: 0 0 12px;
  color: var(--brand);
  font-size: 12px;
}

.option.selected.deny .option-caret {
  color: var(--danger);
}

.option-index {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.prompt-expired {
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-primary);
}
</style>
