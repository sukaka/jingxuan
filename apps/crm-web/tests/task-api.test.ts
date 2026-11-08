import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import {
  claimTask,
  completeTask,
  createTask,
  listTaskActions,
  listTasks,
  reassignTask,
  returnTask,
  updateTask,
} from '../src/api/task'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

interface CapturedCall {
  method: string
  url: string
  params: Record<string, unknown>
  data: unknown
  headers: Record<string, unknown>
}

function captureAdapter(responseData: unknown): { adapter: AxiosAdapter; calls: CapturedCall[] } {
  const calls: CapturedCall[] = []
  const adapter: AxiosAdapter = async (config) => {
    calls.push({
      method: (config.method ?? 'get').toLowerCase(),
      url: config.url ?? '',
      params: (config.params ?? {}) as Record<string, unknown>,
      data: config.data ? JSON.parse(config.data as string) : undefined,
      headers: config.headers.toJSON() as Record<string, unknown>,
    })
    return {
      status: 200,
      statusText: 'OK',
      headers: {},
      config,
      data: { code: 'OK', message: 'success', data: responseData, requestId: 'req-t', timestamp: '' },
    }
  }
  return { adapter, calls }
}

describe('task api 契约', () => {
  it('listTasks 透传 assigneeId/status 筛选', async () => {
    const { adapter, calls } = captureAdapter({ items: [], total: 0, page: 1, size: 20 })
    http.defaults.adapter = adapter

    await listTasks({ page: 1, size: 20, assigneeId: 2, status: 'OPEN' })

    expect(calls[0]?.url).toBe('/tasks')
    expect(calls[0]?.params).toEqual({ page: 1, size: 20, assigneeId: 2, status: 'OPEN' })
  })

  it('createTask 不传空字符串字段;通用 updateTask 只提交资料字段和 version', async () => {
    const { adapter, calls } = captureAdapter({ id: 3 })
    http.defaults.adapter = adapter

    await createTask({ taskType: 'FOLLOWUP', priority: 'HIGH', title: '回访陈女士' })
    await updateTask(3, { title: '修改后的标题', version: 1 })

    expect(calls[0]?.method).toBe('post')
    expect(calls[0]?.data).toEqual({ taskType: 'FOLLOWUP', priority: 'HIGH', title: '回访陈女士' })
    expect(calls[1]?.method).toBe('patch')
    expect(calls[1]?.url).toBe('/tasks/3')
    expect(calls[1]?.data).toEqual({ title: '修改后的标题', version: 1 })
  })

  it('接手、完成分别调用独立动作接口并携带 version 和幂等键', async () => {
    const { adapter, calls } = captureAdapter({ id: 3 })
    http.defaults.adapter = adapter

    await claimTask(3, 4, 'claim-key')
    await completeTask(3, 5, 'complete-key')

    expect(calls.map((call) => [call.method, call.url, call.data])).toEqual([
      ['post', '/tasks/3/claim', { version: 4 }],
      ['post', '/tasks/3/complete', { version: 5 }],
    ])
    expect(calls[0]?.headers['Idempotency-Key']).toBe('claim-key')
    expect(calls[1]?.headers['Idempotency-Key']).toBe('complete-key')
  })

  it('转派只提交目标处理人，退回提交原因，流转记录使用 GET /actions', async () => {
    const { adapter, calls } = captureAdapter([])
    http.defaults.adapter = adapter

    await reassignTask(3, { assigneeUserId: 8, reason: '请假转派', version: 4 }, 'reassign-key')
    await returnTask(3, { reason: '无法继续跟进', version: 5 }, 'return-key')
    await listTaskActions(3)

    expect(calls.map((call) => [call.method, call.url, call.data])).toEqual([
      ['post', '/tasks/3/reassign', { assigneeUserId: 8, reason: '请假转派', version: 4 }],
      ['post', '/tasks/3/return', { reason: '无法继续跟进', version: 5 }],
      ['get', '/tasks/3/actions', undefined],
    ])
    expect(calls[0]?.headers['Idempotency-Key']).toBe('reassign-key')
    expect(calls[1]?.headers['Idempotency-Key']).toBe('return-key')
  })
})
