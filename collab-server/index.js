/**
 * docAgent 协同同步服务（Hocuspocus）。
 *
 * 职责边界：只管实时同步转发与内容中转，业务真理源仍是 Spring 后端——
 * 打开文档时从 Spring 拉最新 HTML 灌入协同文档，编辑停顿后把协同文档
 * 转回 HTML 存回 Spring（走既有 PUT /api/documents/{id}，复用文档版本管理）。
 *
 * 环境变量：
 *   COLLAB_PORT    监听端口，默认 1234
 *   SPRING_URL     Spring 后端地址，默认 http://localhost:8080
 *   COLLAB_AUTH    strict = 后端校验失败即拒绝；lenient(默认) = 后端不可达时放行并告警；off = 跳过校验
 */
// 认仓库根上那份 .env，跟 Spring 的 spring.config.import 读的是同一个文件。
// 用 __dirname 而不是相对路径：从哪个目录起进程都一样。容器里没这个文件，
// dotenv 会跳过，compose 注入的环境变量本来就优先。
require('dotenv').config({
  path: require('path').join(__dirname, '..', '.env'),
  quiet: true
})

const { Server } = require('@hocuspocus/server')
const { TiptapTransformer } = require('@hocuspocus/transformer')
const Y = require('yjs')
const StarterKit = require('@tiptap/starter-kit').default
const Image = require('@tiptap/extension-image').default
const TextAlign = require('@tiptap/extension-text-align').default
const { toMarkdown, markdownToNodes } = require('./markdown')

const PORT = Number(process.env.COLLAB_PORT || 1234)
const SPRING_URL = (process.env.SPRING_URL || 'http://localhost:8080').replace(/\/$/, '')
const AUTH_MODE = process.env.COLLAB_AUTH || 'lenient'
// /ai/* 只允许 Spring 调。这个端口浏览器要连，所以它是暴露的，
// 而那几个接口能改任何调用者有读权限的文档。
const AI_SECRET = process.env.COLLAB_AI_SECRET || ''

// 必须与前端 DocEditor.vue 的扩展列表保持同构，否则序列化会丢节点
const extensions = [
  StarterKit.configure({ heading: { levels: [1, 2, 3] }, undoRedo: false }),
  Image.configure({ inline: false, allowBase64: false }),
  TextAlign.configure({ types: ['heading', 'paragraph'] })
]

const FRAGMENT = 'default' // TipTap Collaboration 扩展的默认片段名

const log = (...args) => console.log(new Date().toISOString(), ...args)

async function fetchDocument(documentId, token) {
  const res = await fetch(`${SPRING_URL}/api/documents/${encodeURIComponent(documentId)}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (!res.ok) throw new Error(`GET document ${documentId} -> ${res.status}`)
  const body = await res.json()
  return body?.data || body
}

async function fetchYdoc(documentId, token) {
  const res = await fetch(`${SPRING_URL}/api/documents/${encodeURIComponent(documentId)}/ydoc`, {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (res.status === 404) return null      // 旧文档，还没存过二进制
  if (!res.ok) throw new Error(`GET ydoc ${documentId} -> ${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}

async function saveYdoc(documentId, state, token) {
  const res = await fetch(`${SPRING_URL}/api/documents/${encodeURIComponent(documentId)}/ydoc`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/octet-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: state
  })
  if (!res.ok) throw new Error(`PUT ydoc ${documentId} -> ${res.status}`)
}

async function saveDocument(documentId, doc, markdown, token) {
  const payload = {
    title: doc?.title || '未命名文档',
    content: markdown,
    category: doc?.category || 'default',
    autosave: true          // 防抖回存不建版本，手动保存那条路照旧
  }
  const res = await fetch(`${SPRING_URL}/api/documents/${encodeURIComponent(documentId)}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(payload)
  })
  if (!res.ok) throw new Error(`PUT document ${documentId} -> ${res.status}`)
}

const readBody = (req) => new Promise(resolve => {
  let s = ''
  req.on('data', c => { s += c })
  req.on('end', () => resolve(s))
})

const occurrences = (hay, needle) => needle ? hay.split(needle).length - 1 : 0

// AI 也是一个编辑者：读写都经协同文档，改动即刻广播，防抖回存复用既有链路。
async function withDocument(documentId, token, fn) {
  // hocuspocus v3 的 new Server() 返回的是包装层，直连开在里面那个实例上
  const conn = await server.hocuspocus.openDirectConnection(documentId, { token, isAi: true })
  try {
    let result
    await conn.transact(doc => { result = fn(doc) })
    return result
  } finally {
    await conn.disconnect()
  }
}

function readMarkdown(doc, offset, limit) {
  const nodes = TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []
  const from = Math.max(0, offset - 1)
  const slice = nodes.slice(from, from + limit)
  return {
    markdown: slice.map(toMarkdown).join('\n\n'),
    from: from + 1,
    to: from + slice.length,
    total: nodes.length,
    truncated: from + slice.length < nodes.length
  }
}

/** markdown → Y.Doc 二进制。纯函数：不认识 documentId，也不碰任何已存在的文档。 */
function ydocFromMarkdown(markdown) {
  const nodes = markdownToNodes(markdown)
  const doc = TiptapTransformer.toYdoc(
      { type: 'doc', content: nodes.length ? nodes : [{ type: 'paragraph' }] },
      FRAGMENT, extensions)
  try {
    return Y.encodeStateAsUpdate(doc)
  } finally {
    doc.destroy()
  }
}

// 只重建命中的那一个顶层节点：其余节点的 CRDT 身份不动，并发编辑照常合并。
function applyEdit(doc, old, replacement) {
  const nodes = TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []
  const mds = nodes.map(toMarkdown)
  const hits = occurrences(mds.join('\n\n'), old)
  if (hits === 0) return { error: '找不到这段文本，文档可能已被修改，请重新读取' }
  if (hits > 1) return { error: `这段文本出现了 ${hits} 次，请带上更多上下文使其唯一` }

  const idx = mds.findIndex(md => md.includes(old))
  if (idx < 0) return { error: '这段文本跨越了多个段落，请分两次调用' }

  const nextMd = mds[idx].replace(old, replacement)
  const frag = doc.getXmlFragment(FRAGMENT)
  if (!nextMd.trim()) {
    frag.delete(idx, 1)
    return { ok: true, removed: true }
  }
  if (nodes[idx].type === 'image') return { error: '图片节点只支持整块删除' }

  const newNodes = markdownToNodes(nextMd)
  if (newNodes.map(toMarkdown).join('\n\n') !== nextMd) {
    return { error: '替换后的 markdown 无法解析，标记可能不成对' }
  }
  if (newNodes[0].type === nodes[idx].type && nodes[idx].attrs) {
    newNodes[0].attrs = { ...nodes[idx].attrs, ...newNodes[0].attrs }
  }

  const staged = TiptapTransformer.toYdoc({ type: 'doc', content: newNodes }, FRAGMENT, extensions)
  const clones = staged.getXmlFragment(FRAGMENT).toArray().map(n => n.clone())
  frag.delete(idx, 1)
  frag.insert(idx, clones)
  return { ok: true, blocks: clones.length }
}

const server = new Server({
  port: PORT,
  timeout: 30000,
  debounce: 2000,      // 停止编辑 2 秒后回存
  maxDebounce: 10000,  // 持续编辑时最迟 10 秒强制回存一次

  onRequest({ request, response }) {
    const url = new URL(request.url, 'http://localhost')
    if (!url.pathname.startsWith('/ai/')) return Promise.resolve()

    const token = (request.headers.authorization || '').replace(/^Bearer\s+/i, '')
    const send = (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' })
      response.end(JSON.stringify(body))
    }
    const sendBinary = (status, bytes) => {
      response.writeHead(status, { 'Content-Type': 'application/octet-stream' })
      response.end(Buffer.from(bytes))
    }

    // 这几个接口能改任何调用者有读权限的文档，写权限是 Spring 侧的 agent 工具
    // 在放行前查的——所以只有 Spring 能调，别人拿着合法的读 token 也不行。
    if (!AI_SECRET || request.headers['x-ai-secret'] !== AI_SECRET) {
      send(403, { error: 'forbidden' })
      return Promise.reject()
    }

    return readBody(request)
      .then(async raw => {
        const params = raw ? JSON.parse(raw) : Object.fromEntries(url.searchParams)

        // 纯函数，不碰任何文档，所以排在 documentId 检查之前：新建文档调它的时候
        // 那篇文档还没有 ydoc——那正是这里要造的东西。
        if (url.pathname === '/ai/convert') {
          return sendBinary(200, ydocFromMarkdown(String(params.markdown || '')))
        }

        const documentId = params.documentId
        if (!documentId) return send(400, { error: 'documentId 必填' })

        // 文档已被别人打开时 onLoadDocument 会跳过，授权检查也就跟着跳过了，
        // 所以这里每次都自己查一次。
        let meta = null
        if (AUTH_MODE !== 'off') {
          try {
            meta = await fetchDocument(documentId, token)
          } catch (e) {
            log(`[ai] denied doc=${documentId}: ${e.message}`)
            return send(401, { error: '无权访问该文档，或文档不存在' })
          }
        }

        if (url.pathname === '/ai/read') {
          const offset = Number(params.offset) || 1
          const limit = Math.min(Number(params.limit) || 200, 500)
          const view = await withDocument(documentId, token, d => readMarkdown(d, offset, limit))
          return send(200, { ...view, title: meta?.title })
        }
        if (url.pathname === '/ai/replace' || url.pathname === '/ai/delete') {
          const replacement = url.pathname === '/ai/delete' ? '' : String(params.new ?? '')
          const result = await withDocument(documentId, token,
            d => applyEdit(d, String(params.old ?? ''), replacement))
          return send(result.error ? 409 : 200, result)
        }
        send(404, { error: 'unknown endpoint' })
      })
      .catch(e => {
        log(`[ai] ERROR ${url.pathname}: ${e.message}`)
        send(500, { error: e.message })
      })
      .then(() => Promise.reject())  // 已自行响应，阻止 hocuspocus 继续处理
  },

  async onAuthenticate({ token, documentName }) {
    if (AUTH_MODE === 'off') return {}
    if (!token) throw new Error('missing token')
    try {
      // 能以该 token 读到文档即视为有权限（授权关系由 Spring 的 document_access 控制）
      await fetchDocument(documentName, token)
      return { token }
    } catch (e) {
      if (AUTH_MODE === 'strict') {
        log(`[auth] rejected for doc=${documentName}: ${e.message}`)
        throw new Error('unauthorized')
      }
      log(`[auth] WARN spring unreachable, lenient mode allows doc=${documentName}: ${e.message}`)
      return { token }
    }
  },

  /**
   * 只从存下来的 CRDT 状态重建，没有就是空文档。
   *
   * 不在这里从 markdown 灌种：那样每个字符都是全新身份，而 yjs 的更新按身份寻址，
   * 断线期间攒下的更新会永远整合不进来。ydoc 由创建文档时的 /ai/convert 一次性建好，
   * 走到这里还没有的只能是真的空文档。
   */
  async onLoadDocument({ document, documentName, context }) {
    // 已有协同状态（还有其他人在线）就不重复灌入
    if (!document.isEmpty(FRAGMENT)) return document

    try {
      const state = await fetchYdoc(documentName, context?.token)
      if (state) {
        Y.applyUpdate(document, state)
        log(`[load] doc=${documentName} restored from ydoc (${state.length} bytes)`)
      } else {
        log(`[load] doc=${documentName} no ydoc, starting empty`)
      }
    } catch (e) {
      log(`[load] WARN doc=${documentName} ydoc unavailable: ${e.message}`)
    }
    return document
  },

  /**
   * 二进制是真理源，markdown 是派生投影——摘要、版本、非协同读取都消费它，
   * 所以两份一起写。先写二进制：万一 markdown 那次失败，下一次回存会补上。
   */
  async onStoreDocument({ document, documentName, context }) {
    try {
      await saveYdoc(documentName, Y.encodeStateAsUpdate(document), context?.token)
    } catch (e) {
      log(`[store] ERROR ydoc doc=${documentName}: ${e.message}`)
      return
    }

    try {
      const nodes = TiptapTransformer.fromYdoc(document, FRAGMENT).content || []
      const markdown = nodes.map(toMarkdown).join('\n\n')
      // 先取一次带回 title/category，避免整篇 PUT 把标题清空
      let doc = null
      try { doc = await fetchDocument(documentName, context?.token) } catch { /* 用兜底字段 */ }
      await saveDocument(documentName, doc, markdown, context?.token)
      log(`[store] doc=${documentName} persisted (ydoc + ${markdown.length} chars markdown)`)
    } catch (e) {
      log(`[store] ERROR markdown doc=${documentName}: ${e.message}`)
    }
  },

  async onConnect({ documentName }) {
    log(`[conn] + doc=${documentName}`)
  },
  async onDisconnect({ documentName, clientsCount }) {
    log(`[conn] - doc=${documentName}, remaining=${clientsCount}`)
  }
})

if (require.main === module) {
  server.listen().then(() => {
    log(`collab-server listening on :${PORT}, spring=${SPRING_URL}, auth=${AUTH_MODE}`)
    if (!AI_SECRET) {
      log('[ai] WARN COLLAB_AI_SECRET 未设置，/ai/* 一律拒绝，agent 无法读写文档')
    }
  })
}

module.exports = { server, extensions, FRAGMENT, readMarkdown, applyEdit, ydocFromMarkdown }
