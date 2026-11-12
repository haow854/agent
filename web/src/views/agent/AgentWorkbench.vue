<template>
  <div class="agent-workbench">
    <header class="workbench-header">
      <h2>◈ Docs Agent Workbench</h2>
      <p>Tools, agent runs, approvals and task history in one place.</p>
    </header>

    <!-- 整个页面用 el-scrollbar 包裹 -->
    <el-scrollbar class="workbench-scrollbar">
      <div class="workbench-content">
        <el-card class="overview-card" shadow="hover">
          <div class="overview-header">
            <span>Overview</span>
            <div class="actions">
              <el-button type="primary" size="small" @click="fetchOverview" :loading="loading">Refresh</el-button>
            </div>
          </div>
          <el-row :gutter="20" class="stat-row" v-if="overviewData">
            <el-col :span="4"><div class="stat-box"><div class="num">{{ overviewData.tools?.length || 0 }}</div><div class="label">Tools</div></div></el-col>
            <el-col :span="4"><div class="stat-box"><div class="num">{{ overviewData.tasks?.length || 0 }}</div><div class="label">Agent tasks</div></div></el-col>
          </el-row>
        </el-card>

        <el-row :gutter="20" class="main-layout">
          <el-col :span="8">
            <el-card class="box-card" shadow="hover">
              <template #header>
                <div class="card-title">1. Tool schemas ({{ toolsList.length }})</div>
              </template>
              <!-- 工具列表用 el-scrollbar -->
              <el-scrollbar max-height="400px" v-loading="loading">
                <div class="tools-list">
                  <div v-for="tool in toolsList" :key="tool.name" class="tool-item">
                    <div class="tool-head">
                      <span class="tool-name">{{ tool.name }}</span>
                      <el-tag size="small" :type="tool.riskLevel === 'HIGH' ? 'danger' : 'success'">
                        {{ tool.riskLevel || 'LOW' }}
                      </el-tag>
                    </div>
                    <div class="tool-desc">{{ tool.description }}</div>
                  </div>
                </div>
              </el-scrollbar>
            </el-card>

            <el-card class="box-card mt-20" shadow="hover">
              <template #header>
                <div class="card-title">2. Approvals (action required)</div>
              </template>
              <el-input v-model="approvalTaskId" placeholder="taskId" style="margin-bottom: 10px;" />
              <div class="approval-actions">
                <el-button type="primary" @click="handleConfirmApproval" :loading="executing">Approve</el-button>
                <el-button type="danger" plain @click="handleCancelApproval">Reject</el-button>
              </div>
            </el-card>
          </el-col>

          <el-col :span="16">
            <el-card class="box-card" shadow="hover">
              <template #header>
                <div class="card-title">3. Run an agent task</div>
              </template>
              <el-input
                  v-model="agentPrompt"
                  type="textarea"
                  :rows="4"
                  placeholder="e.g. summarize the meeting notes and pull out action items…"
                  style="margin-bottom: 15px;"
              />
              <div class="execute-actions">
                <el-button type="primary" size="large" @click="executeAgent" :loading="executing">
                  <el-icon><VideoPlay /></el-icon> Run
                </el-button>
              </div>

              <!-- 执行结果用 el-scrollbar -->
              <div class="result-viewer" v-if="executionResult">
                <h4>Result / timeline</h4>
                <el-scrollbar max-height="300px">
                  <pre class="json-code">{{ JSON.stringify(executionResult, null, 2) }}</pre>
                </el-scrollbar>
              </div>
            </el-card>

            <el-card class="box-card mt-20" shadow="hover">
              <template #header>
                <div class="card-title">4. Task history</div>
              </template>
              <!-- 任务历史表格用 el-scrollbar -->
              <el-scrollbar max-height="400px">
                <el-table :data="tasksHistory" stripe style="width: 100%" size="small">
                  <el-table-column prop="taskId" label="Task ID" width="220" show-overflow-tooltip />
                  <el-table-column prop="task" label="Task" show-overflow-tooltip />
                  <el-table-column prop="status" label="Status" width="100">
                    <template #default="{ row }">
                      <el-tag :type="row.status === 'completed' ? 'success' : (row.status === 'action_required' ? 'warning' : 'info')">
                        {{ row.status }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="Actions" width="100">
                    <template #default="{ row }">
                      <el-button type="primary" link @click="viewTaskDetail(row.taskId)">Details</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-scrollbar>
            </el-card>
          </el-col>
        </el-row>
      </div>
    </el-scrollbar>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { agentApi } from '../../api/agent'
import { approvalsOf } from '../../utils/agentResult'
import { ElMessage } from 'element-plus'

// 数据状态
const loading = ref(false)
const executing = ref(false)
const overviewData = ref(null)
const toolsList = ref([])
const tasksHistory = ref([])

// 表单输入
const agentPrompt = ref('')
const approvalTaskId = ref('')
const approvedCallIds = ref([])
const executionResult = ref(null)

// 1. 获取全局大盘数据
const fetchOverview = async () => {
  loading.value = true
  try {
    const res = await agentApi.getOverview()
    overviewData.value = res.data
    toolsList.value = res.data.tools || []
    tasksHistory.value = res.data.tasks || []
  } catch (e) {
    ElMessage.error('Could not load workbench data')
  } finally {
    loading.value = false
  }
}

// 2. 发起 Agent tasks
const executeAgent = async () => {
  if (!agentPrompt.value.trim()) return ElMessage.warning('Enter an instruction')

  executing.value = true
  executionResult.value = null
  try {
    const res = await agentApi.executeTask({
      task: agentPrompt.value
    })
    executionResult.value = res.data

    // 如果需要审批
    if (res.data.status === 'action_required') {
      ElMessage.warning('A high-risk action needs approval — see the panel on the left')
      approvalTaskId.value = res.data.taskId || ''
      approvedCallIds.value = approvalsOf(res.data).map((a) => a.toolCallId)
    } else {
      ElMessage.success('Done')
    }

    fetchOverview() // 刷新列表
  } catch (e) {
    ElMessage.error('Run failed')
  } finally {
    executing.value = false
  }
}

// 3. 审批通过
const handleConfirmApproval = async () => {
  if (!approvalTaskId.value) return ElMessage.warning('Enter a taskId')
  executing.value = true
  try {
    const res = await agentApi.confirmApproval({
      taskId: approvalTaskId.value,
      approvedToolCallIds: approvedCallIds.value
    })
    ElMessage.success('Approved — the task is continuing')
    executionResult.value = res.data
    approvalTaskId.value = ''
    approvedCallIds.value = []
    fetchOverview()
  } catch (e) {
    ElMessage.error('Bad approval token, or the run failed')
  } finally {
    executing.value = false
  }
}

// 4. 审批拒绝
const handleCancelApproval = async () => {
  if (!approvalTaskId.value) return
  try {
    await agentApi.cancelApproval(approvalTaskId.value)
    ElMessage.success('Approval discarded')
    approvalTaskId.value = ''
    approvedCallIds.value = []
    fetchOverview()
  } catch (e) {
    ElMessage.error('Cancel failed')
  }
}

// 查看任务详情
const viewTaskDetail = async (taskId) => {
  try {
    const res = await agentApi.getTaskHistory(taskId)
    executionResult.value = res.data // 直接在右侧的 JSON 框里展示快照
    ElMessage.success('Task snapshot loaded')
  } catch (e) {}
}

onMounted(() => {
  fetchOverview()
})
</script>

<style scoped>
.agent-workbench {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-sunken);
  overflow: hidden; /* 防止整个页面出现双滚动条 */
}

.workbench-header {
  background: var(--bg-sunken);
  color: var(--text-primary);
  padding: 20px 30px;
  flex-shrink: 0; /* 固定头部 */
}

.workbench-header h2 {
  margin: 0 0 8px 0;
  display: flex;
  align-items: center;
  gap: 10px;
}

.workbench-header p {
  margin: 0;
  font-size: 14px;
  opacity: 0.9;
}

/* 👇 整个内容区域的滚动容器 */
.workbench-scrollbar {
  flex: 1;
  overflow: hidden;
}

.workbench-content {
  padding: 20px 30px;
}

.mt-20 {
  margin-top: 20px;
}

/* 概览卡片 */
.overview-card {
  margin-bottom: 20px;
  border-radius: 8px;
}

.overview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
  font-weight: 500;
}

.stat-box {
  background: var(--bg-sunken);
  padding: 15px;
  border-radius: 6px;
  text-align: center;
}

.stat-box .num {
  font-size: 24px;
  font-weight: 500;
  color: var(--brand);
}

.stat-box .label {
  font-size: 12px;
  color: var(--text-muted);
  margin-top: 5px;
}

/* 工具列表 */
.tools-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 10px;
}

.tool-item {
  border: 1px solid var(--border);
  padding: 10px 15px;
  border-radius: 6px;
  background: #fff;
  transition: all 0.2s;
}

.tool-item:hover {
  border-color: var(--brand);
  box-shadow: 0 2px 8px rgba(217, 119, 87, 0.1);
}

.tool-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}

.tool-name {
  font-weight: 500;
  color: var(--text-primary);
}

.tool-desc {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.5;
}

/* 审批操作 */
.approval-actions {
  display: flex;
  gap: 10px;
}

/* 执行与代码展示区 */
.execute-actions {
  display: flex;
  justify-content: flex-end;
}

.result-viewer {
  margin-top: 20px;
  background: #1e1e1e;
  border-radius: 8px;
  padding: 15px;
}

.result-viewer h4 {
  color: var(--text-muted);
  margin: 0 0 10px 0;
  font-size: 13px;
}

.json-code {
  margin: 0;
  color: #a9b7c6;
  font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 13px;
  line-height: 1.6;
}

/* 卡片样式 */
.box-card {
  border-radius: 8px;
}

.card-title {
  font-weight: 500;
  color: var(--text-primary);
}
</style>
