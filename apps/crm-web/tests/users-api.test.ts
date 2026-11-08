import type { AxiosAdapter } from 'axios'
import { afterEach, describe, expect, it } from 'vitest'
import { listUsers, listUserTeams } from '../src/api/users'
import { http } from '../src/api/http'

const originalAdapter = http.defaults.adapter

afterEach(() => {
  http.defaults.adapter = originalAdapter
})

describe('用户管理筛选 API', () => {
  it('组合筛选参数完整传递，全部不限制维度', async () => {
    const calls: { url: string; params: Record<string, unknown> }[] = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push({ url: config.url ?? '', params: config.params as Record<string, unknown> })
      return {
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        data: { code: 'OK', message: 'success', data: { items: [], total: 0, page: 1, size: 20 }, requestId: 'req', timestamp: '' },
      }
    }
    http.defaults.adapter = adapter

    await listUsers({
      page: 1,
      size: 20,
      keyword: '王',
      status: 'ACTIVE',
      userType: 'EMPLOYEE',
      teamId: 1,
      roleCode: 'MANAGER',
    })
    await listUsers({ page: 1, size: 20 })

    expect(calls[0]).toEqual({
      url: '/users',
      params: {
        page: 1,
        size: 20,
        keyword: '王',
        status: 'ACTIVE',
        userType: 'EMPLOYEE',
        teamId: 1,
        roleCode: 'MANAGER',
      },
    })
    expect(calls[1]?.url).toBe('/users')
    expect(calls[1]?.params.keyword).toBeUndefined()
    expect(calls[1]?.params.status).toBeUndefined()
    expect(calls[1]?.params.userType).toBeUndefined()
    expect(calls[1]?.params.teamId).toBeUndefined()
    expect(calls[1]?.params.roleCode).toBeUndefined()
  })

  it('团队选项读取当前租户的真实团队接口', async () => {
    const calls: string[] = []
    const adapter: AxiosAdapter = async (config) => {
      calls.push(config.url ?? '')
      return {
        status: 200,
        statusText: 'OK',
        headers: {},
        config,
        data: { code: 'OK', message: 'success', data: [], requestId: 'req', timestamp: '' },
      }
    }
    http.defaults.adapter = adapter

    await listUserTeams()

    expect(calls).toEqual(['/users/teams'])
  })
})
