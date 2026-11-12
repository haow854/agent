/**
 * 当前这段对话，全应用一份。
 *
 * 沉浸式对话页、文档页右侧的助理栏、全局助理抽屉，是同一段对话的三个视图：
 * 会话 id、消息列表、"正在思考"都该是同一份。以前每处各存一份，只靠
 * localStorage 里的约定同步，谁忘了写谁就跑偏，同一个 bug 要修好几遍。
 */
import { ref } from 'vue'
import { agentApi } from '../api/agent'
import { onAgentProgress } from '../api/agentProgress'
import { STORAGE_KEYS } from '../constants'
import { toChatMessages } from '../utils/chatTurns'
import { approvalsOf, documentCardOf } from '../utils/agentResult'

/**
 * 一次提交一个键。服务端拿它推导 taskId（同键同 id），所以同一条提交被重放——
 * 比如消费者崩溃后 MQ 重投同一条消息——第二次会撞上已存在的任务直接返回，
 * 不会再跑一遍。重试要复用同一个键才有这个效果，所以别在重试路径上重新生成。
 */
const submissionKey = () => `${Date.now()}-${Math.random().toString(36).slice(2)}`

// 模块作用域：所有调用方拿到的是同一份
const sessionId = ref(localStorage.getItem(STORAGE_KEYS.GLOBAL_SESSION) || '')
const messages = ref([])
const isThinking = ref(false)
const historyLoading = ref(false)

/** 这一轮正在跑的步骤，run 结束后挪进那条消息里留档 */
const steps = ref([])
let activeTaskId = null
let awaitedTask = null

/**
 * 只认自己这一轮的事件。
 *
 * 主题是 per-user 的，别的标签页在跑的任务也会推到这里来；taskId 从
 * 开跑事件里认领一次，认领时比对提问原文，认错了整条时间线都是别人的。
 */
onAgentProgress((event) => {
  if (!isThinking.value || !event?.taskId) return

  if (event.eventType === 'task_started') {
    if (!activeTaskId && event.payload?.task === awaitedTask) activeTaskId = event.taskId
    return
  }
  if (event.eventType === 'task_continued') {
    if (!activeTaskId) activeTaskId = event.taskId
    return
  }
  if (event.taskId !== activeTaskId) return

  if (event.eventType === 'step_model') {
    const calls = event.payload?.toolCalls || []
    const text = event.payload?.text
    // 不带工具调用的那一轮就是终轮，它的文字就是答复本身，会作为消息出现。
    // 这里再放一份就是同一段话显示两遍。
    if (text && text.trim() && calls.length) steps.value.push({ type: 'text', text })
    for (const call of calls) {
      steps.value.push({
        type: 'tool',
        id: call.id,
        name: call.name,
        args: call.arguments,
        status: 'running',
        message: ''
      })
    }
  } else if (event.eventType === 'step_tool') {
    const row = steps.value.find(
        (s) => s.type === 'tool' && s.id === event.payload?.toolCallId)
    if (row) {
      row.status = event.payload.status
      row.message = event.payload.message
    } else {
      steps.value.push({
        type: 'tool',
        id: event.payload.toolCallId,
        name: event.payload.toolName,
        args: '',
        status: event.payload.status,
        message: event.payload.message
      })
    }
  }
})

function beginRun(task) {
  steps.value = []
  activeTaskId = null
  awaitedTask = task
  isThinking.value = true
}

/** 步骤跟着那条答复走，下一轮开始时 steps 会清空，留在这儿才回得去看。 */
const takeSteps = () => steps.value.slice()

const STATUS_TEXT = {
  not_found: 'That approval expired or was already handled. Send the instruction again.',
  not_resumable: 'This task already finished and can’t continue.',
  still_running: 'This task is still running. Check back in a moment.',
  duplicate: 'That instruction is already running.',
  cancelled: 'This task was cancelled.'
}

function failureOf(result) {
  const status = result?.status
  if (!status || status === 'success' || status === 'action_required') return ''
  if (STATUS_TEXT[status]) return STATUS_TEXT[status]
  if (status === 'error') {
    return result.answer || `Failed: ${result.stoppedReason || 'unknown reason'}`
  }
  return result.message || `Didn’t finish (${status})`
}

function reconcileSteps(executed) {
  const pool = (executed || []).slice()
  for (const row of steps.value) {
    if (row.type !== 'tool' || row.status !== 'running') continue
    const i = pool.findIndex((s) => s.toolName === row.name)
    if (i === -1) {
      row.status = 'unknown'
      row.message = row.message || 'No result reported for this step'
      continue
    }
    const [step] = pool.splice(i, 1)
    row.status = step.status || 'success'
    row.message = step.message || row.message
  }
}

function pushResult(result) {
  const approvals = approvalsOf(result)
  const failure = failureOf(result)

  for (const approval of approvals) {
    const row = steps.value.find(
        (s) => s.type === 'tool' && s.id === approval.toolCallId)
    if (row) row.status = 'waiting'
  }
  reconcileSteps(result?.steps)

  messages.value.push({
    role: 'ai',
    text: failure || (approvals.length ? '' : (result?.answer || '(no text reply)')),
    taskId: result?.taskId,
    actionResult: documentCardOf(result),
    approvals,
    steps: takeSteps()
  })
}

/** 认下一个会话 id。服务端可能刚为这次请求建了会话，认下它，后面几轮才落在一起。 */
function useSession(id) {
  if (!id || id === sessionId.value) return
  sessionId.value = id
  localStorage.setItem(STORAGE_KEYS.GLOBAL_SESSION, id)
}

/** 开新对话。会话 id 必须一起清掉，否则下一条消息还是接着上一个会话。 */
function startNewSession() {
  sessionId.value = ''
  messages.value = []
  localStorage.removeItem(STORAGE_KEYS.GLOBAL_SESSION)
}

/** 换到另一个会话，并把它的历史铺上 */
async function openSession(id) {
  if (!id) return
  useSession(id)
  await loadHistory()
}

/**
 * 把这个会话已有的轮次拉回来铺上。
 *
 * 本地的 messages 只是缓存：换了会话不重载，看到的还是上一个会话的消息。
 */
async function loadHistory() {
  if (!sessionId.value) {
    messages.value = []
    return
  }
  historyLoading.value = true
  try {
    const res = await agentApi.getSessionHistory(sessionId.value)
    messages.value = toChatMessages(res.data)
  } catch (e) {
    console.error('Could not load chat history', e)
  } finally {
    historyLoading.value = false
  }
}

/**
 * 发一轮，把提问和答复都记进 messages。
 *
 * @returns 服务端响应，调用方要看 pending / taskId 时用；失败返回 null
 */
async function ask({ task, model, documentId, workspace } = {}) {
  if (!task || !task.trim() || isThinking.value) return null

  messages.value.push({ role: 'user', text: task })
  beginRun(task)

  try {
    const res = await agentApi.executeTask({
      task,
      model,
      // 不带就让服务端建一个。先建再发的话，发送失败会在列表里留下一个永远空着的会话。
      sessionId: sessionId.value || undefined,
      documentId: documentId || undefined,
      // all | current。current 时 Agent 只够得到挂着的这篇。
      workspace: workspace || 'all',
      idempotencyKey: submissionKey()
    })

    const result = res.data
    useSession(result?.sessionId)
    pushResult(result)
    return result
  } catch (e) {
    // 不编答案兜底。失败伪装成成功，比直接报错难查得多。
    console.error('Agent call failed', e)
    reconcileSteps([])
    messages.value.push({
      role: 'ai',
      text: `Failed: ${e.message || 'unknown error'}`,
      steps: takeSteps()
    })
    return null
  } finally {
    isThinking.value = false
    awaitedTask = null
  }
}

/** 允许这条答复停下来等的那批操作，让 run 接着跑。 */
async function approve(message) {
  const approvals = takeApprovals(message)
  if (!approvals.length) return null

  message.approvalOutcome = 'approved'
  message.steps = (message.steps || []).filter(
      (s) => !approvals.some((a) => a.toolCallId === s.id))

  beginRun(null)
  steps.value = approvals.map((a) => ({
    type: 'tool',
    id: a.toolCallId,
    name: a.toolName,
    args: JSON.stringify(a.params || {}),
    status: 'running',
    message: ''
  }))

  try {
    const res = await agentApi.confirmApproval({
      taskId: message.taskId,
      approvedToolCallIds: approvals.map((a) => a.toolCallId)
    })
    pushResult(res.data)
    return res.data
  } catch (e) {
    console.error('Could not confirm approval', e)
    reconcileSteps([])
    messages.value.push({
      role: 'ai',
      text: `Action failed: ${e.message || 'unknown error'}`,
      steps: takeSteps()
    })
    return null
  } finally {
    isThinking.value = false
    awaitedTask = null
  }
}

async function deny(message) {
  const approvals = takeApprovals(message)
  if (!approvals.length) return

  message.approvalOutcome = 'denied'
  for (const approval of approvals) {
    const row = (message.steps || []).find((s) => s.id === approval.toolCallId)
    if (row) {
      row.status = 'cancelled'
      row.message = 'You rejected this action'
    }
  }

  try {
    await agentApi.cancelApproval(message.taskId)
  } catch (e) {
    console.error('Could not cancel', e)
  }
}

function takeApprovals(message) {
  const approvals = message?.approvals || []
  if (message) message.approvals = []
  return approvals
}

export function useAgentChat() {
  return {
    sessionId,
    messages,
    steps,
    isThinking,
    historyLoading,
    useSession,
    openSession,
    startNewSession,
    loadHistory,
    ask,
    approve,
    deny
  }
}
