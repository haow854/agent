import request from '../utils/request'
import { createAsyncJobRunner } from './asyncJob.js'

const wrapResult = (data) => ({ code: 200, message: 'success', data })
const getAsyncJob = (jobId) => request.get(`/ai/async/jobs/${jobId}`)

/**
 * 任何一次会跑 Agent 的调用都走这条：提交进队列拿 jobId，然后轮询。
 * 同步端点会把 HTTP 连接占到整个 run 跑完，而 run 没有时长上界，网关先断。
 */
const runAsyncAgentJob = (path, data) => createAsyncJobRunner({
    submit: () => request.post(path, data),
    getJob: getAsyncJob,
    timeoutMs: 300000
}).runAsyncJob().then(wrapResult)

export const agentApi = {
    // 获取Overview（包含工具列表、任务数等）
    getOverview: () => request.get('/ai/agent/workbench/overview'),

    // 执行 Agent tasks（核心交互）。幂等键服务端必填，重投递/重试靠它收敛成同一个任务；
    // 调用方没带就在这里生成一个，带了以调用方的为准（重试复用同一个键才有意义）。
    executeTask: (data) => runAsyncAgentJob('/ai/async/agent/execute', {
        idempotencyKey: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
        ...data
    }),

    // 查询单个任务快照
    getTaskHistory: (taskId) => request.get(`/ai/agent/tasks/${taskId}`),

    // 审批危险操作：Approve
    confirmApproval: (data) => request.post('/ai/agent/approvals/confirm', data),

    // 审批危险操作：取消
    cancelApproval: (taskId) => request.post(`/ai/agent/tasks/${taskId}/approvals/cancel`),

    // 列出当前用户的会话，最近使用的在前
    listSessions: () => request.get('/ai/agent/sessions'),

    // 一个会话的完整消息链
    getSessionHistory: (sessionId) =>
        request.get(`/ai/agent/session/${sessionId}/history`),

    // 结束会话
    endSession: (sessionId) => request.post(`/ai/agent/session/${sessionId}/end`)
}
