/**
 * 从 Agent 的响应里读出前端要用的东西。
 *
 * 这些字段名跟着后端 DTO 走，集中在这里，改的时候只有一个地方要动。
 */

const card = (documentId, title) => ({
  documentId,
  title: title || 'Untitled'
})

/**
 * 挑出这次 run 新建的文档。
 *
 * 读的是 artifacts——run 产出什么记在那里，形状是 {kind:"documentId", ref, name}。
 * steps 里只有工具调用的状态，工具返回的 data 没有投影进去，翻它翻不到。
 */
export function documentCardOf(result) {
  const artifact = (result?.artifacts || []).find(a => a?.kind === 'documentId' && a.ref)
  return artifact ? card(artifact.ref, artifact.name) : null
}

/**
 * 同一张卡，但从历史里的工具结果还原。
 *
 * 历史没有 artifacts，只有每次调用存下的 AgentToolResult；document_create 的 data
 * 里带着同样那两个字段。不还原的话，刷新一次页面文档就再也点不到了。
 */
export function documentCardOfToolResult(result) {
  const data = result?.data
  return data?.documentId && data.persisted ? card(data.documentId, data.title) : null
}

/**
 * run 停下来等用户确认时，要确认的那批操作。
 *
 * 是个列表：一批工具调用可能同时停在好几个审批上，所以确认也是一次交一批。
 */
export function approvalsOf(result) {
  return (result?.pending || []).filter(p => p?.kind === 'APPROVAL' && p.toolCallId)
}

/**
 * run 停下来向用户提问时（ask_user），那个问题。
 *
 * 候选项在模型传给 ask_user 的参数里，是一个分号分隔的字符串。
 */
export function questionOf(result) {
  const pending = (result?.pending || []).find(p => p?.kind === 'ASK_USER' && p.toolCallId)
  return pending ? toQuestion(pending.toolCallId, pending.params || { question: pending.question }) : null
}

/** 从 ask_user 的调用参数还原问题；历史里只有参数，没有 pending 条目。 */
export function toQuestion(toolCallId, params) {
  const options = String(params?.options || '')
    .split(/[;；]/)
    .map(o => o.trim())
    .filter(Boolean)
  return { toolCallId, text: params?.question || '', options }
}
