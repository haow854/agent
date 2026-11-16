import { documentCardOfToolResult, toQuestion } from './agentResult.js'

/**
 * 把服务端的会话历史转成聊天气泡。
 *
 * 历史里的工具调用和工具结果要折叠成实时那条路一样的 steps 挂在答复上，
 * 否则同一轮对话跑完是步骤卡、刷新一下变回裸 JSON。
 */
export function toChatMessages(entries) {
    const messages = []
    let steps = []
    let actionResult = null
    let lastTaskId = null

    const flush = (taskId, text, question = null) => {
        messages.push({ role: 'ai', text, taskId, steps, actionResult, question })
        steps = []
        actionResult = null
    }

    for (const e of entries || []) {
        if (e.taskId) lastTaskId = e.taskId
        if (e.role === 'user') {
            messages.push({ role: 'user', text: e.content, taskId: e.taskId })
            steps = []
            continue
        }

        if (e.role === 'assistant') {
            const calls = e.toolCalls || []
            // 不带工具调用的那一轮是终轮，它的文字就是答复
            if (!calls.length) {
                if (e.content) flush(e.taskId, e.content)
                continue
            }
            if (e.content && e.content.trim()) steps.push({ type: 'text', text: e.content })
            for (const call of calls) {
                steps.push({
                    type: 'tool',
                    id: call.id,
                    name: call.name,
                    args: call.arguments,
                    status: 'running',
                    message: ''
                })
            }
            continue
        }

        if (e.role === 'tool') {
            const row = steps.find((s) => s.type === 'tool' && s.id === e.toolCallId)
            const result = parseResult(e.content)
            if (row && result) {
                row.status = result.status || 'success'
                row.message = result.message || ''
            }
            actionResult = documentCardOfToolResult(result) || actionResult
        }
    }

    // 跑到一半停下的轮次（等审批、等回答、失败、被取消）没有终轮答复，
    // 步骤不能跟着丢——那正是最需要看见发生过什么的时候。
    // 最后一次 ask_user 还没有回答，就是任务还停在这个问题上：还原成问题卡片。
    if (steps.length) {
        const asking = steps.find((s) => s.type === 'tool' && s.name === 'ask_user' && s.status === 'running')
        if (asking) steps = steps.filter((s) => s !== asking)
        flush(lastTaskId, '', asking ? toQuestion(asking.id, parseResult(asking.args)) : null)
    }
    return messages
}

function parseResult(content) {
    try {
        return JSON.parse(content)
    } catch {
        return null
    }
}
