/**
 * 访问令牌 30 分钟过期，刷新令牌 7 天过期。
 *
 * 快到期时自动用刷新令牌换一个新的访问令牌；请求碰到 401 时也先换令牌再重试
 * （request.js）。只有刷新令牌也失效了才退出登录。协同连接会收到新令牌
 * （useCollaboration.js），协同服务回存时用的也是它。
 */
import axios from 'axios'
import { watch } from 'vue'
import { decodeJwt } from './jwt'
import { getAccessToken, getRefreshToken, setAccessToken } from './session'

// 提前这么久刷新，留出请求往返和本机时钟误差
const REFRESH_AHEAD_MS = 2 * 60 * 1000

let inFlight = null
let timer = null

/** 用刷新令牌换新的访问令牌。同时有多处要换时共用一次请求；换不到时 reject。 */
export function refreshAccessToken() {
  if (!inFlight) {
    inFlight = requestNewToken().finally(() => { inFlight = null })
  }
  return inFlight
}

async function requestNewToken() {
  const refreshToken = getRefreshToken()
  if (!refreshToken) throw new Error('No refresh token')
  // 不走 request.js：它碰到 401 会来这里刷新，绕一圈就成了死循环。
  // 令牌放在表单里而不是 URL 上，不会进访问日志。
  const res = await axios.post('/api/users/refresh', new URLSearchParams({ refreshToken }))
  if (res.data?.code !== 200 || !res.data.data) {
    throw new Error(res.data?.message || 'Could not refresh the access token')
  }
  setAccessToken(res.data.data)
  return res.data.data
}

// 每拿到一个新的访问令牌（登录、自动刷新、401 后刷新）就重新定下次刷新的时间
watch(getAccessToken, (token) => {
  clearTimeout(timer)
  const exp = token ? decodeJwt(token)?.exp : null
  if (!exp) return
  const delay = Math.max(0, exp * 1000 - REFRESH_AHEAD_MS - Date.now())
  // 失败不用管：下一次请求碰到 401 会再试一次，还不行才退出登录
  timer = setTimeout(() => refreshAccessToken().catch(() => {}), delay)
}, { immediate: true })
