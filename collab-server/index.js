/**
 * docAgent 协同同步服务（Hocuspocus）。
 *
 * 浏览器里的编辑器和 Agent 都经这里读写文档。协同状态（Y.Doc 二进制）是真理源，存在
 * Spring 那边；每次回存同时写一份 Markdown，给摘要、版本和非协同读取用。
 *
 * 环境变量：
 *   COLLAB_PORT       监听端口，默认 1234
 *   SPRING_URL        Spring 后端地址，默认 http://localhost:8080
 *   COLLAB_AUTH       默认每次连接都向 Spring 校验文档权限；设为 off 不校验，只用于本地调试
 *   COLLAB_AI_SECRET  /ai/* 接口的密钥，必须与 Spring 一致
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
const { extensions } = require('./extensions')
const { toMarkdown, markdownToNodes } = require('./markdown')
const { legacyToMarkdown } = require('./legacy-markdown')

const PORT = Number(process.env.COLLAB_PORT || 1234)
const SPRING_URL = (process.env.SPRING_URL || 'http://localhost:8080').replace(/\/$/, '')
const AUTH_OFF = process.env.COLLAB_AUTH === 'off'
// /ai/* 只允许 Spring 调。这个端口浏览器要连，所以它是暴露的，
// 而那几个接口能改任何调用者有读权限的文档。
const AI_SECRET = process.env.COLLAB_AI_SECRET || ''

const FRAGMENT = 'default' // TipTap Collaboration 扩展的默认片段名

// 协同状态里记录内容按哪套 Markdown 规则建成，见 upgradeLegacyContent
const META = 'docagent'
const CODEC_VERSION = 2

const log = (...args) => console.log(new Date().toISOString(), ...args)

/** 调 Spring 的文档接口。json 和 bytes 二选一作请求体。 */
function spring(method, documentId, suffix, token, { json, bytes } = {}) {
  const headers = token ? { Authorization: `Bearer ${token}` } : {}
  let body
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(json)
  } else if (bytes !== undefined) {
    headers['Content-Type'] = 'application/octet-stream'
    body = bytes
  }
  return fetch(`${SPRING_URL}/api/documents/${encodeURIComponent(documentId)}${suffix}`, { method, headers, body })
}

// Spring 的业务错误（无权访问、文档不存在）是 HTTP 200 加 Result.code，只看 HTTP 状态会当成成功
async function resultData(res, what) {
  const result = res.ok ? await res.json() : null
  if (result?.code !== 200) {
    throw new Error(`${what} -> ${result ? `${result.code} ${result.message}` : res.status}`)
  }
  return result.data
}

async function fetchDocument(documentId, token) {
  return resultData(await spring('GET', documentId, '', token), `GET document ${documentId}`)
}

async function fetchYdoc(documentId, token) {
  const res = await spring('GET', documentId, '/ydoc', token)
  if (res.status === 404) return null      // 还没存过协同状态
  if (!res.ok) throw new Error(`GET ydoc ${documentId} -> ${res.status}`)
  return new Uint8Array(await res.arrayBuffer())
}

async function saveYdoc(documentId, state, token) {
  await resultData(await spring('PUT', documentId, '/ydoc', token, { bytes: state }), `PUT ydoc ${documentId}`)
}

// 整篇 PUT：标题和分类也要带上，否则会被清空
async function saveDocument(documentId, doc, markdown, token) {
  const json = { title: doc.title, content: markdown, category: doc.category, autosave: true }  // autosave 不建版本
  await resultData(await spring('PUT', documentId, '', token, { json }), `PUT document ${documentId}`)
}

/**
 * 二进制是真理源，markdown 是派生投影——摘要、版本、非协同读取都消费它，所以两份一起写。
 * 先写二进制；后面任何一步失败，下一次回存会整个重来。
 */
async function persist(document, documentName, token) {
  try {
    await saveYdoc(documentName, Y.encodeStateAsUpdate(document), token)
    const markdown = documentMarkdown(document)
    await saveDocument(documentName, await fetchDocument(documentName, token), markdown, token)
    log(`[store] doc=${documentName} persisted (ydoc + ${markdown.length} chars markdown)`)
  } catch (e) {
    log(`[store] ERROR doc=${documentName}: ${e.message}`)
  }
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

function documentMarkdown(doc) {
  return (TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []).map(toMarkdown).join('\n\n')
}

// 编辑器的文档至少要有一个块，空 markdown 给一个空段落
function blocksFrom(markdown) {
  const nodes = markdownToNodes(markdown)
  return nodes.length ? nodes : [{ type: 'paragraph' }]
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
  const doc = TiptapTransformer.toYdoc({ type: 'doc', content: blocksFrom(markdown) }, FRAGMENT, extensions)
  try {
    doc.getMap(META).set('markdownCodec', CODEC_VERSION)
    return Y.encodeStateAsUpdate(doc)
  } finally {
    doc.destroy()
  }
}

// 把从 idx 起的 count 个顶层块换成 newNodes：删除再插入，其余块的 CRDT 身份不动，并发编辑照常合并。
function replaceBlocks(doc, idx, count, newNodes) {
  const staged = TiptapTransformer.toYdoc({ type: 'doc', content: newNodes }, FRAGMENT, extensions)
  const clones = staged.getXmlFragment(FRAGMENT).toArray().map(n => n.clone())
  const frag = doc.getXmlFragment(FRAGMENT)
  frag.delete(idx, count)
  frag.insert(idx, clones)
  return clones.length
}

// 只比内容和结构（节点类型、文字、标记类型），不比对齐方式这类属性
const contentOf = (nodes) => JSON.stringify(nodes, (key, value) => (key === 'attrs' ? undefined : value))

// 只重建命中的那一个顶层块。不成对的标记按 CommonMark 规则当作普通文字保留，内容不会丢。
function applyEdit(doc, old, replacement) {
  const nodes = TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []
  const mds = nodes.map(toMarkdown)
  const hits = occurrences(mds.join('\n\n'), old)
  if (hits === 0) return { error: '找不到这段文本，文档可能已被修改，请重新读取' }
  if (hits > 1) return { error: `这段文本出现了 ${hits} 次，请带上更多上下文使其唯一` }

  const idx = mds.findIndex(md => md.includes(old))
  if (idx < 0) return { error: '这段文本跨越了多个段落，请分两次调用' }

  // 用函数做替换值：替换文本里的 $& $1 之类不能被当成替换模式
  const nextMd = mds[idx].replace(old, () => replacement)
  const newNodes = nextMd.trim() ? markdownToNodes(nextMd) : []
  if (!newNodes.length) {
    doc.getXmlFragment(FRAGMENT).delete(idx, 1)
    return { ok: true, removed: true }
  }
  if (nodes[idx].type === 'image') return { error: '图片节点只支持整块删除' }

  if (newNodes[0].type === nodes[idx].type && nodes[idx].attrs) {
    newNodes[0].attrs = { ...nodes[idx].attrs, ...newNodes[0].attrs }
  }
  return { ok: true, blocks: replaceBlocks(doc, idx, 1, newNodes) }
}

/**
 * 整篇换成 markdown 的内容（版本恢复），返回换掉之前的 markdown。在一个事务里删除再插入：
 * 打开着的编辑器一次收到完整的新内容，带着旧状态重连的客户端也不会把旧内容带回来。
 */
function overwrite(doc, markdown) {
  const previous = documentMarkdown(doc)
  doc.transact(() => {
    replaceBlocks(doc, 0, doc.getXmlFragment(FRAGMENT).length, blocksFrom(markdown))
    doc.getMap(META).set('markdownCodec', CODEC_VERSION)
  })
  return { ok: true, previous }
}

/**
 * 把段落里存着的 Markdown 原文解析成对应的元素（表格、代码块、四级以下标题等）。
 *
 * 协同状态里带着 CODEC_VERSION 标记的文档已经按现行规则建成，直接跳过。没有标记的，
 * 把连续的、能还原成原文的块合成一段重新解析（被空行拆成几段的代码块要合起来才拼得回），
 * 结果和当前内容不同才替换这一段。还原不了原文的块（比如编辑器里做的嵌套列表）原样保留，
 * 同时把前后隔成两段。替换走删除再插入，旧内容在协同历史里留作删除记录，带着旧状态
 * 重连的客户端不会把它带回来。
 *
 * @returns {{ marked: boolean, upgraded: number }} marked 表示这次新打上了标记，upgraded 是替换了几段
 */
function upgradeLegacyContent(doc) {
  const meta = doc.getMap(META)
  if (meta.get('markdownCodec') === CODEC_VERSION) return { marked: false, upgraded: 0 }

  const nodes = TiptapTransformer.fromYdoc(doc, FRAGMENT).content || []
  const sources = nodes.map(legacyToMarkdown)
  const runs = []
  let start = -1
  sources.forEach((source, i) => {
    if (source !== null && start < 0) start = i
    if (source === null && start >= 0) { runs.push([start, i]); start = -1 }
  })
  if (start >= 0) runs.push([start, nodes.length])

  let upgraded = 0
  doc.transact(() => {
    // 从后往前换，前面段落的下标不受影响
    for (const [from, to] of runs.reverse()) {
      const newNodes = markdownToNodes(sources.slice(from, to).join('\n\n'))
      if (!newNodes.length || contentOf(newNodes) === contentOf(nodes.slice(from, to))) continue
      replaceBlocks(doc, from, to - from, newNodes)
      upgraded++
    }
    meta.set('markdownCodec', CODEC_VERSION)
  })
  return { marked: true, upgraded }
}

/**
 * 前端刷新访问令牌后经 provider.sendToken() 发来新令牌。回存用的是连接上下文里的令牌，
 * 连接时的那个 30 分钟后就过期，之后的回存会被 Spring 拒绝，所以校验通过就换掉。
 * 校验不过时抛出，hocuspocus 会断开这条连接。
 */
async function syncToken({ token, documentName, context }) {
  if (AUTH_OFF) return
  await fetchDocument(documentName, token)
  context.token = token
  log(`[auth] token refreshed doc=${documentName}`)
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

    // 这几个接口能改任何调用者有读权限的文档，写权限是 Spring 在调用前查的
    // ——所以只有 Spring 能调，别人拿着合法的读 token 也不行。
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
        if (!AUTH_OFF) {
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
        if (url.pathname === '/ai/overwrite') {
          const markdown = String(params.markdown ?? '')
          return send(200, await withDocument(documentId, token, d => overwrite(d, markdown)))
        }
        send(404, { error: 'unknown endpoint' })
      })
      .catch(e => {
        log(`[ai] ERROR ${url.pathname}: ${e.message}`)
        send(500, { error: e.message })
      })
      .then(() => Promise.reject())  // 已自行响应，阻止 hocuspocus 继续处理
  },

  onTokenSync: syncToken,

  async onAuthenticate({ token, documentName }) {
    if (AUTH_OFF) return {}
    try {
      // 能以该 token 读到文档即视为有权限（授权关系由 Spring 的 document_access 控制）
      await fetchDocument(documentName, token)
    } catch (e) {
      log(`[auth] rejected doc=${documentName}: ${e.message}`)
      throw new Error('unauthorized')
    }
    return { token }
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

    let state = null
    try {
      state = await fetchYdoc(documentName, context?.token)
      if (state) {
        Y.applyUpdate(document, state)
        log(`[load] doc=${documentName} restored from ydoc (${state.length} bytes)`)
      } else {
        log(`[load] doc=${documentName} no ydoc, starting empty`)
      }
    } catch (e) {
      log(`[load] WARN doc=${documentName} ydoc unavailable: ${e.message}`)
      return document
    }

    const { marked, upgraded } = upgradeLegacyContent(document)
    if (upgraded) log(`[load] doc=${documentName} parsed ${upgraded} markdown block(s)`)
    // 加载阶段的改动不会触发回存，要自己存，否则下次加载还会再做一遍。
    // 空文档不存：标记留在内存里，随第一次编辑的回存一起写下去
    if (marked && state && context?.token) await persist(document, documentName, context.token)
    return document
  },

  async onStoreDocument({ document, documentName, context }) {
    await persist(document, documentName, context?.token)
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
    log(`collab-server listening on :${PORT}, spring=${SPRING_URL}, auth=${AUTH_OFF ? 'off' : 'on'}`)
    if (!AI_SECRET) {
      log('[ai] WARN COLLAB_AI_SECRET 未设置，/ai/* 一律拒绝，agent 无法读写文档')
    }
  })
}

module.exports = {
  server, extensions, FRAGMENT, readMarkdown, applyEdit, overwrite, ydocFromMarkdown, upgradeLegacyContent, syncToken
}
