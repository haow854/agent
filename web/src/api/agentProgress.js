/**
 * Agent 执行进度的接收端。
 *
 * 提交走的是异步任务 + 轮询，轮询只能看到"跑完没有"。中间每一步长什么样
 * 由服务端从 run 里推出来，走这条 WebSocket。服务端在 CONNECT 帧上校验访问令牌，
 * 只把当前用户自己任务的进度推过来。
 */
import { Client } from '@stomp/stompjs'
import { getAccessToken } from '../utils/session'

const PROGRESS_DESTINATION = '/user/queue/agent/progress'

const listeners = new Set()
let client = null
let connectedUserId = null

function brokerURL() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws/agent`
}

/** 换了登录用户要重连：服务端按连接时的令牌认人。 */
function ensureConnected() {
  const userId = localStorage.getItem('userId')
  if (!userId || userId === connectedUserId) return
  if (client) client.deactivate()

  connectedUserId = userId
  client = new Client({
    brokerURL: brokerURL(),
    reconnectDelay: 5000,
    // 每次（重新）连接都取当时的令牌，令牌刷新过也不会拿旧的去连
    beforeConnect: (stomp) => {
      stomp.connectHeaders = { Authorization: `Bearer ${getAccessToken()}` }
    },
    onConnect: () => {
      client.subscribe(PROGRESS_DESTINATION, (frame) => {
        let event
        try {
          event = JSON.parse(frame.body)
        } catch {
          return
        }
        listeners.forEach((fn) => fn(event))
      })
    }
  })
  client.activate()
}

/** @returns 取消订阅 */
export function onAgentProgress(handler) {
  ensureConnected()
  listeners.add(handler)
  return () => listeners.delete(handler)
}
