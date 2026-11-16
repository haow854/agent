import test from 'node:test'
import assert from 'node:assert/strict'
import { approvalsOf, questionOf } from './agentResult.js'
import { toChatMessages } from './chatTurns.js'

const askingResult = {
  taskId: 'task-1',
  status: 'action_required',
  answer: '工作区里没有找到这份资料，先确认一下。',
  pending: [{
    kind: 'ASK_USER',
    toolCallId: 'call-ask',
    toolName: 'ask_user',
    question: '要基于哪份资料写？',
    params: { question: '要基于哪份资料写？', options: 'A. 周报模板; B. 项目日志；C. 其他（请说明）' }
  }]
}

test('a run paused on ask_user yields the question and its options', () => {
  assert.deepEqual(questionOf(askingResult), {
    toolCallId: 'call-ask',
    text: '要基于哪份资料写？',
    options: ['A. 周报模板', 'B. 项目日志', 'C. 其他（请说明）']
  })
  // 提问不是审批，不能出现在审批卡片里
  assert.deepEqual(approvalsOf(askingResult), [])
})

test('a run paused only for approval has no question', () => {
  const result = { pending: [{ kind: 'APPROVAL', toolCallId: 'call-del', toolName: 'document_delete' }] }
  assert.equal(questionOf(result), null)
  assert.equal(approvalsOf(result).length, 1)
})

const askCall = {
  id: 'call-ask',
  name: 'ask_user',
  arguments: JSON.stringify({ question: '要基于哪份资料写？', options: '周报模板;项目日志' })
}
const pausedHistory = [
  { role: 'user', taskId: 'task-1', content: '按那份资料写一篇总结' },
  { role: 'assistant', taskId: 'task-1', content: '', toolCalls: [{ id: 'call-list', name: 'document_list', arguments: '{}' }] },
  { role: 'tool', taskId: 'task-1', toolCallId: 'call-list', content: JSON.stringify({ status: 'success', message: 'ok' }) },
  { role: 'assistant', taskId: 'task-1', content: '没找到这份资料。', toolCalls: [askCall] }
]

test('history that ends on an unanswered ask_user restores the question card', () => {
  const messages = toChatMessages(pausedHistory)
  const last = messages[messages.length - 1]

  assert.equal(last.taskId, 'task-1')
  assert.deepEqual(last.question, { toolCallId: 'call-ask', text: '要基于哪份资料写？', options: ['周报模板', '项目日志'] })
  assert.ok(!last.steps.some((s) => s.name === 'ask_user'))
})

test('an answered ask_user is not shown as a question again', () => {
  const messages = toChatMessages([
    ...pausedHistory,
    { role: 'tool', taskId: 'task-1', toolCallId: 'call-ask', content: JSON.stringify({ status: 'success', message: 'User input received' }) },
    { role: 'assistant', taskId: 'task-1', content: '已按项目日志写好总结。', toolCalls: [] }
  ])

  assert.ok(messages.every((m) => !m.question))
  assert.equal(messages[messages.length - 1].text, '已按项目日志写好总结。')
})
