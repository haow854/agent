<template>
  <div class="aiops-container">
    <div class="aiops-header">
      <h1>
        <el-icon :size="28"><Monitor /></el-icon>
        AI Ops
      </h1>
      <div class="header-actions">
        <el-button type="primary" @click="refreshAll" :loading="loading">
          <el-icon><Refresh /></el-icon> Refresh
        </el-button>
        <el-button type="warning" @click="handleResetMetrics">
          <el-icon><Delete /></el-icon> Reset metrics
        </el-button>
      </div>
    </div>

    <el-row :gutter="20" class="metrics-row">
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">Total AI requests</div>
          <div class="metric-value">{{ getMetricValue('ai.requests.count') }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">AI errors</div>
          <div class="metric-value error">{{ getMetricValue('ai.errors.count') }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">Average latency</div>
          <div class="metric-value">{{ getAvgResponseTime() }} ms</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="metric-card">
          <div class="metric-label">System health</div>
          <div class="metric-value" :class="healthStatus === 'UP' ? 'success' : 'error'">
            {{ healthStatus }}
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" class="detail-row">
      <el-col :span="12">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span><el-icon><DataLine /></el-icon> Metrics</span>
            </div>
          </template>
          <el-table :data="metricsTableData" stripe size="small" empty-text="No metrics yet">
            <el-table-column prop="name" label="Metric" />
            <el-table-column prop="value" label="Value" width="120" />
          </el-table>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="hover" class="section-card">
          <template #header>
            <div class="card-header">
              <span><el-icon><WarningFilled /></el-icon> Incidents</span>
              <el-button type="primary" size="small" text @click="detectFaults">
                <el-icon><Search /></el-icon> Run detection
              </el-button>
            </div>
          </template>
          <el-table :data="faults" stripe size="small" empty-text="No incidents">
            <el-table-column prop="type" label="Type" width="160" />
            <el-table-column prop="message" label="Description" show-overflow-tooltip />
            <el-table-column prop="status" label="Status" width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === 'resolved' ? 'success' : 'danger'" size="small">
                  {{ row.status === 'resolved' ? 'Resolved' : 'Pending' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="Actions" width="100">
              <template #default="{ row }">
                <el-button
                  v-if="row.status !== 'resolved'"
                  type="primary"
                  size="small"
                  text
                  @click="resolveFault(row.faultId)"
                >
                  Resolve
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiopsApi } from '../../api/aiops'

const loading = ref(false)
const metrics = ref({})
const healthStatus = ref('UNKNOWN')
const faults = ref([])

const metricsTableData = computed(() => {
  return Object.entries(metrics.value).map(([name, value]) => {
    if (typeof value === 'object') {
      return { name, value: JSON.stringify(value) }
    }
    return { name, value }
  })
})

const getMetricValue = (key) => {
  return metrics.value[key] ?? 0
}

const getAvgResponseTime = () => {
  const stats = metrics.value['ai.request.stats']
  if (stats && stats.avg !== undefined) {
    return Math.round(stats.avg)
  }
  return 0
}

const fetchMetrics = async () => {
  try {
    const res = await aiopsApi.getMetrics()
    metrics.value = res.data || {}
  } catch {
    ElMessage.error('Could not load metrics')
  }
}

const fetchHealth = async () => {
  try {
    const res = await aiopsApi.healthCheck()
    healthStatus.value = res.data?.status || 'UNKNOWN'
  } catch {
    healthStatus.value = 'DOWN'
  }
}

const fetchFaults = async () => {
  try {
    const res = await aiopsApi.getAllFaults()
    faults.value = res.data || []
  } catch {
    ElMessage.error('Could not load incidents')
  }
}

const detectFaults = async () => {
  loading.value = true
  try {
    const res = await aiopsApi.detectFaults()
    ElMessage.success(`Detection finished — ${res.data?.length || 0} incident(s)`)
    await fetchFaults()
  } catch {
    ElMessage.error('Detection failed')
  } finally {
    loading.value = false
  }
}

const resolveFault = async (faultId) => {
  loading.value = true
  try {
    await aiopsApi.resolveFault(faultId)
    ElMessage.success('Incident resolved')
    await fetchFaults()
  } catch {
    ElMessage.error('Could not resolve the incident')
  } finally {
    loading.value = false
  }
}

const handleResetMetrics = () => {
  ElMessageBox.confirm('Reset all metrics?').then(async () => {
    loading.value = true
    try {
      await aiopsApi.resetMetrics()
      ElMessage.success('Metrics reset')
      await fetchMetrics()
    } catch {
      ElMessage.error('Reset failed')
    } finally {
      loading.value = false
    }
  }).catch(() => {})
}

const refreshAll = async () => {
  loading.value = true
  await Promise.all([fetchMetrics(), fetchHealth(), fetchFaults()])
  loading.value = false
  ElMessage.success('Refreshed')
}

onMounted(() => {
  refreshAll()
})
</script>

<style scoped>
.aiops-container {
  padding: 24px 32px;
  max-width: 1400px;
  margin: 0 auto;
}

.aiops-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 28px;
}

.aiops-header h1 {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 22px;
  font-weight: 500;
  color: var(--text-primary);
  margin: 0;
}

.header-actions {
  display: flex;
  gap: 10px;
}

.metrics-row {
  margin-bottom: 20px;
}

.metric-card {
  text-align: center;
  border-radius: 12px;
}

.metric-label {
  font-size: 13px;
  color: var(--text-muted);
  margin-bottom: 8px;
}

.metric-value {
  font-size: 28px;
  font-weight: 500;
  color: var(--text-primary);
}

.metric-value.error {
  color: var(--danger);
}

.metric-value.success {
  color: var(--success);
}

.detail-row {
  margin-top: 0;
}

.section-card {
  border-radius: 12px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 500;
  font-size: 15px;
}

.card-header span {
  display: flex;
  align-items: center;
  gap: 6px;
}
</style>