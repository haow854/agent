const assert = require('assert')

// 校验要真的去问 Spring，不受 .env 里调试开关的影响
process.env.COLLAB_AUTH = 'on'

// Spring 只认 good 这个令牌；业务错误是 HTTP 200 加 code
global.fetch = async (url, options) => ({
  ok: true,
  status: 200,
  json: async () => options.headers.Authorization === 'Bearer good'
    ? { code: 200, data: { title: 't' } }
    : { code: 600, message: '无权访问' }
})

const { syncToken } = require('./index')

;(async () => {
  // 前端刷新后发来的新令牌有效：换掉这条连接回存时用的令牌
  {
    const context = { token: 'expired' }
    await syncToken({ token: 'good', documentName: 'doc-1', context })
    assert.strictEqual(context.token, 'good')
  }

  // 新令牌无效：抛出（hocuspocus 会断开连接），原令牌不动
  {
    const context = { token: 'expired' }
    await assert.rejects(syncToken({ token: 'forged', documentName: 'doc-1', context }))
    assert.strictEqual(context.token, 'expired')
  }

  console.log('令牌同步: 全部断言通过')
})().catch(error => {
  console.error(error)
  process.exit(1)
})
