/**
 * Agent 执行进度的接收端。
 *
 * 提交走的是异步任务 + 轮询，轮询只能看到"跑完没有"。中间每一步长什么样
 * 由服务端从 run 里推出来，走这条 WebSocket。
 */
import { Client } from '@stomp/stompjs'

const listeners = new Set()
let client = null
let connectedUserId = null

function brokerURL() {
  const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${location.host}/ws/agent`
}

/** 换了Sign in用户要重连：订阅的是 per-user 主题，连接认的是旧的那个 id。 */
function ensureConnected() {
  const userId = localStorage.getItem('userId')
  if (!userId || userId === connectedUserId) return
  if (client) client.deactivate()

  connectedUserId = userId
  client = new Client({
    brokerURL: brokerURL(),
    reconnectDelay: 5000,
    onConnect: () => {
      client.subscribe(`/topic/agent/users/${userId}`, (frame) => {
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
