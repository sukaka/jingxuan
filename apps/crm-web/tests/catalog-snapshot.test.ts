import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import PackageSnapshotDrawer from '../src/views/catalog/PackageSnapshotDrawer.vue'
import type { PackageVersionSnapshot } from '../src/api/catalog'

describe('PackageSnapshotDrawer 规则提示', () => {
  it('明确展示 Q01/Q02 待确认，不把选N项显示成可发次数', async () => {
    const snapshot: PackageVersionSnapshot = {
      id: 311,
      packageId: 202,
      packageCode: 'PKG-SY',
      packageName: '素颜卡',
      versionNo: 1,
      selectionMode: 'CHOOSE_N',
      choiceCount: 3,
      selectionRules: null,
      status: 'DRAFT',
      version: 0,
      rulesStatus: 'PENDING_Q01',
      allocationStatus: 'PENDING_Q02',
      entitlementIssuable: false,
      projects: [{
        id: 401,
        packageVersionId: 311,
        projectId: 101,
        projectCode: 'PRJ-TEST',
        projectName: '测试子项目',
        categoryCode: '水光',
        listPriceFen: 19900,
        projectStatus: 'ACTIVE',
        grantCount: 1,
        selectionWeight: 1,
        allocationPriceFen: 0,
        optionGroup: 'MAIN',
        version: 0,
      }],
      skus: [{
        id: 301,
        skuCode: 'SKU-TEST-301',
        packageVersionId: 311,
        packageId: 202,
        packageName: '素颜卡',
        versionNo: 1,
        selectionMode: 'CHOOSE_N',
        specName: '选3项',
        salePriceFen: 19900,
        status: 'INACTIVE',
        onSaleAt: null,
        offSaleAt: null,
        createdAt: '2026-10-24T01:00:00Z',
        version: 0,
      }],
      institutions: [],
    }
    const wrapper = mount(PackageSnapshotDrawer, {
      props: { modelValue: true, snapshot },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    })

    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(wrapper.text()).toContain('素颜卡')
    expect(document.body.textContent).toContain('可从候选子项目中选择 3 项')
    expect(document.body.textContent).toContain('不是使用次数')
    expect(document.body.textContent).toContain('Q01 选择规则未确认')
    expect(document.body.textContent).toContain('Q02 多项目金额分摊未配置')
    expect(document.body.textContent).toContain('不可发放')
    expect(document.body.textContent).toContain('主选项组')
    expect(document.body.textContent).toContain('已停用')
    expect(document.body.textContent).not.toContain('MAIN')
    expect(document.body.textContent).not.toContain('INACTIVE')
    expect(document.body.textContent).toContain('创建模板 SKU')
  })
})
