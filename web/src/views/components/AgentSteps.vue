<template>
  <div v-if="steps && steps.length" class="agent-steps">
    <template v-for="(step, i) in steps" :key="i">
      <div v-if="step.type === 'text'" class="step-text">{{ step.text }}</div>

      <details v-else class="step-tool" :class="step.status">
        <summary>
          <el-icon v-if="step.status === 'waiting'"><Clock /></el-icon>
          <el-icon v-else-if="step.status === 'running'" class="is-loading"><Loading /></el-icon>
          <el-icon v-else-if="step.status === 'unknown'"><Remove /></el-icon>
          <el-icon v-else-if="step.status === 'error' || step.status === 'cancelled'"><Close /></el-icon>
          <el-icon v-else><Check /></el-icon>
          <span class="tool-name">{{ step.name }}</span>
          <span class="tool-brief">{{ brief(step) }}</span>
        </summary>
        <pre v-if="step.args" class="tool-args">{{ pretty(step.args) }}</pre>
        <div v-if="step.message" class="tool-message">{{ step.message }}</div>
      </details>
    </template>
  </div>
</template>

<script setup>
import { Check, Clock, Close, Loading, Remove } from '@element-plus/icons-vue'

defineProps({ steps: { type: Array, default: () => [] } })

const parse = (args) => {
  try {
    return JSON.parse(args)
  } catch {
    return null
  }
}

/** 一行里最能认出这次调用的那个参数：读哪篇、搜什么。找不到就不写。 */
function brief(step) {
  const parsed = parse(step.args)
  if (!parsed) return ''
  const value = Object.values(parsed).find(
      (v) => typeof v === 'string' && v.trim())
  if (!value) return ''
  return value.length > 40 ? `${value.slice(0, 40)}…` : value
}

const pretty = (args) => {
  const parsed = parse(args)
  return parsed ? JSON.stringify(parsed, null, 2) : args
}
</script>

<style scoped>
/* min-width:0 一路铺到 tool-brief：flex 子项默认 min-width:auto，撑不下也不肯缩，
   一个长 documentId 就能把整张卡片顶出气泡外面。 */
.agent-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
  max-width: 100%;
}

/* 各视图的 .preserve-format 都是 scoped 的，选不中这里，换行得自己写 */
.step-text {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-style: normal;
}

.step-tool {
  min-width: 0;
  max-width: 100%;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-sunken);
  font-size: 13px;
}

.step-tool.error {
  border-color: var(--brand-border);
  background: var(--danger-soft);
}

.step-tool.waiting {
  border-color: #f0c78a;
  background: #fffbf5;
}

.step-tool.waiting > summary > .el-icon {
  color: var(--warning);
}

.step-tool.cancelled,
.step-tool.unknown {
  background: var(--bg-sunken);
}

.step-tool.cancelled > summary,
.step-tool.unknown > summary {
  color: var(--text-muted);
}

.step-tool.cancelled .tool-name {
  color: var(--text-muted);
  text-decoration: line-through;
}

.step-tool.unknown .tool-name {
  color: var(--text-muted);
}

.step-tool > summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  padding: 7px 10px;
  cursor: pointer;
  list-style: none;
}

.step-tool > summary > .el-icon {
  flex: 0 0 auto;
}

.step-tool > summary::-webkit-details-marker {
  display: none;
}

.tool-name {
  flex: 0 0 auto;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: var(--brand);
}

.tool-brief {
  flex: 1 1 auto;
  min-width: 0;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-args,
.tool-message {
  margin: 0;
  padding: 8px 10px;
  border-top: 1px solid var(--border);
  font-size: 12px;
  color: var(--text-secondary);
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
