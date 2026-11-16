const POLL_INTERVAL_MS = 1200

const delayMs = (ms) => new Promise(resolve => setTimeout(resolve, ms))

/**
 * 提交一个异步任务，然后轮询到它结束。
 *
 * 不设等待上限：任务一定会以 success 或 failed 结束，跑多久由后端决定，子 Agent、大文档
 * 常常要好几分钟。任务记录过期或查询出错时，请求层直接抛错，轮询随之结束。
 */
export function createAsyncJobRunner({ submit, getJob, delay = delayMs, pollIntervalMs = POLL_INTERVAL_MS }) {
  return {
    async runAsyncJob() {
      const { jobId } = (await submit()).data
      for (;;) {
        const job = (await getJob(jobId)).data
        if (job.status === 'success') return job.result
        if (job.status === 'failed') throw new Error(job.error || 'Async job failed')
        await delay(pollIntervalMs)
      }
    }
  }
}
