<template>
  <div class="doc-picker">
    <el-popover
        v-model:visible="open"
        :width="300"
        :disabled="readonly"
        trigger="click"
        placement="top-start"
        popper-class="doc-picker-popper">
      <template #reference>
        <button v-if="modelValue" type="button" class="doc-chip" :class="{ readonly }">
          <el-icon><Document /></el-icon>
          <span class="chip-title">{{ modelValue.title || 'Untitled' }}</span>
          <el-icon v-if="!readonly" class="chip-close" title="Detach" @click.stop="clear"><Close /></el-icon>
        </button>
        <button v-else type="button" class="attach-btn">
          <el-icon><Paperclip /></el-icon> Attach
        </button>
      </template>

      <el-input v-model="keyword" size="small" placeholder="Search my documents" clearable @input="scheduleSearch">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <div class="picker-list" v-loading="loading">
        <div
            v-for="doc in docs"
            :key="doc.id"
            class="picker-item"
            :class="{ active: doc.id === modelValue?.id }"
            @click="choose(doc)">
          <el-icon><Document /></el-icon>
          <div class="item-main">
            <div class="item-title">{{ doc.title || 'Untitled' }}</div>
            <div class="item-meta">{{ formatTime(doc.updateTime || doc.createTime) }}</div>
          </div>
        </div>
        <el-empty v-if="!loading && !docs.length" :image-size="40" :description="keyword ? 'No matches' : 'No documents yet'" />
      </div>
    </el-popover>
  </div>
</template>

<script setup>
/**
 * 给这一轮对话挂一篇文档。
 *
 * Agent 没有「发现文档」的能力——它能写出的任何 documentId 都只可能是从对话文本里
 * 抄来的，抄错就是改了别人的文档。所以目标由用户在这里显式选定，模型只能照着办。
 * 列表和搜索都走服务端按当前用户收口的接口，选不到别人的文档。
 */
import { ref, watch } from 'vue'
import { docApi } from '../../api/document'

const props = defineProps({
  /** 已挂载的文档 { id, title }，未挂载为 null */
  modelValue: { type: Object, default: null },
  /** 编辑器侧栏用：文档由页面锁定，只展示不可换 */
  readonly: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const keyword = ref('')
const docs = ref([])
const loading = ref(false)
let searchTimer = null
let loadedOnce = false

const fetchDocs = async () => {
  loading.value = true
  try {
    const word = keyword.value.trim()
    const res = word
        ? await docApi.searchDocs(word)
        : await docApi.getUserDocs(localStorage.getItem('userId') || '1')
    docs.value = res.data || []
    loadedOnce = true
  } catch (e) {
    // 列表拿不到就空着，别拿旧结果冒充这次的搜索
    console.error('Could not load documents', e)
    docs.value = []
  } finally {
    loading.value = false
  }
}

/** 输入过程中不逐字打接口 */
const scheduleSearch = () => {
  clearTimeout(searchTimer)
  searchTimer = setTimeout(fetchDocs, 300)
}

// 第一次打开才加载：没点开选择器的用户不该为它付一次请求
watch(open, (visible) => {
  if (visible && !loadedOnce) fetchDocs()
})

const choose = (doc) => {
  emit('update:modelValue', { id: doc.id, title: doc.title })
  open.value = false
}

const clear = () => {
  emit('update:modelValue', null)
  open.value = false
}

const formatTime = (value) => {
  if (!value) return ''
  const time = new Date(value)
  return Number.isNaN(time.getTime()) ? '' : time.toLocaleString('zh-CN', {
    month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit'
  })
}
</script>

<style scoped>
.doc-picker { display: inline-flex; }

.attach-btn, .doc-chip {
  display: inline-flex; align-items: center; gap: 5px; max-width: 260px;
  height: 26px; padding: 0 10px; border-radius: 13px; font-size: 13px;
  border: 1px solid transparent; cursor: pointer; transition: all 0.2s;
}

.attach-btn { background: var(--bg-sunken); color: var(--text-muted); }
.attach-btn:hover { background: var(--border); color: var(--brand); }

.doc-chip { background: var(--ink-soft); color: var(--text-primary); }
.doc-chip:hover { background: var(--brand-border); }
.doc-chip.readonly { cursor: default; }
.doc-chip.readonly:hover { background: var(--ink-soft); }
.chip-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.chip-close { opacity: 0.6; }
.chip-close:hover { opacity: 1; }

.picker-list { max-height: 260px; overflow-y: auto; margin-top: 8px; }
.picker-item {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-radius: 6px; cursor: pointer; color: var(--text-secondary);
}
.picker-item:hover { background: var(--bg-sunken); }
.picker-item.active { background: var(--ink-soft); color: var(--text-primary); }
.item-main { min-width: 0; }
.item-title { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.item-meta { font-size: 11px; color: var(--text-muted); margin-top: 2px; }
.picker-item.active .item-meta { color: var(--text-secondary); }
</style>
