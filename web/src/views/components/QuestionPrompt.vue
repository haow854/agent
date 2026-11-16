<template>
  <div class="question-prompt">
    <div class="prompt-head">
      <el-icon><QuestionFilled /></el-icon>
      <span class="head-title">{{ question.text }}</span>
    </div>

    <div v-if="question.options.length" class="prompt-options">
      <button
          v-for="(option, i) in question.options"
          :key="option"
          class="option"
          type="button"
          @click="emit('answer', option)"
      >
        <span class="option-index">{{ i + 1 }}.</span>
        <span class="option-label">{{ option }}</span>
      </button>
    </div>

    <div class="prompt-hint">
      {{ question.options.length ? 'Pick one, or type your own answer below.' : 'Type your answer below.' }}
    </div>
  </div>
</template>

<script setup>
defineProps({
  question: { type: Object, required: true }
})

const emit = defineEmits(['answer'])
</script>

<style scoped>
.question-prompt {
  border: 1px solid var(--border-strong);
  border-radius: 12px;
  background: var(--bg-raised);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-style: normal;
}

.prompt-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 14px;
  line-height: 1.5;
}

.prompt-head .el-icon {
  flex: 0 0 auto;
  margin-top: 3px;
  color: var(--brand);
  font-size: 16px;
}

.head-title {
  color: var(--text-primary);
  white-space: pre-wrap;
}

.prompt-options {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.option {
  display: flex;
  align-items: baseline;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  font-size: 14px;
  line-height: 1.5;
  color: var(--text-secondary);
  text-align: left;
  cursor: pointer;
  transition: 0.15s ease;
}

.option:hover {
  background: var(--ink-soft);
  color: var(--text-primary);
}

.option-index {
  flex: 0 0 auto;
  color: var(--text-muted);
  font-variant-numeric: tabular-nums;
}

.prompt-hint {
  font-size: 12px;
  color: var(--text-muted);
}
</style>
