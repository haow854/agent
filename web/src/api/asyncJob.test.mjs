import test from 'node:test'
import assert from 'node:assert/strict'
import { createAsyncJobRunner } from './asyncJob.js'

test('runAsyncJob submits once and polls until success', async () => {
  const calls = []
  const runner = createAsyncJobRunner({
    submit: async () => {
      calls.push('submit')
      return { data: { jobId: 'job-1' } }
    },
    getJob: async (jobId) => {
      calls.push(`poll:${jobId}`)
      return calls.filter((item) => item.startsWith('poll')).length === 1
        ? { data: { jobId, status: 'running' } }
        : { data: { jobId, status: 'success', result: { answer: 'done' } } }
    },
    delay: async () => {}
  })

  const result = await runner.runAsyncJob()

  assert.deepEqual(calls, ['submit', 'poll:job-1', 'poll:job-1'])
  assert.deepEqual(result, { answer: 'done' })
})

test('runAsyncJob keeps polling a long-running job until it finishes', async () => {
  let polls = 0
  const runner = createAsyncJobRunner({
    submit: async () => ({ data: { jobId: 'job-3' } }),
    getJob: async (jobId) => {
      polls++
      return polls < 1000
        ? { data: { jobId, status: polls < 5 ? 'queued' : 'running' } }
        : { data: { jobId, status: 'success', result: { answer: 'late' } } }
    },
    delay: async () => {}
  })

  assert.deepEqual(await runner.runAsyncJob(), { answer: 'late' })
  assert.equal(polls, 1000)
})

test('runAsyncJob throws failed job error', async () => {
  const runner = createAsyncJobRunner({
    submit: async () => ({ data: { jobId: 'job-2' } }),
    getJob: async () => ({ data: { status: 'failed', error: 'model unavailable' } }),
    delay: async () => {}
  })

  await assert.rejects(() => runner.runAsyncJob(), /model unavailable/)
})
