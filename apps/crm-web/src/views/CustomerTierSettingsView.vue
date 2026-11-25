<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type {
  CustomerTierCode,
  CustomerTierEvaluation,
  CustomerTierRule,
  CustomerTierRuleRequest,
} from '../api/customerTier'
import {
  activateCustomerTierRule,
  createCustomerTierRule,
  listCustomerTierRules,
  retireCustomerTierRule,
  sampleCustomerTierRule,
  trialCustomerTierRule,
  updateCustomerTierRule,
} from '../api/customerTier'
import PageHeader from '../components/prototype/PageHeader.vue'
import { useAuthStore } from '../stores/auth'
import { fenToYuan } from '../utils/money'

interface LevelForm {
  tierCode: CustomerTierCode
  tierName: string
  sortNo: number
  minConsumptionYuan: string
}

type RuleStatus = CustomerTierRule['status']

const codes: CustomerTierCode[] = ['S', 'A', 'B', 'C', 'D']
const defaultLevelNames: Record<CustomerTierCode, string> = {
  S: '核心客户',
  A: '重要客户',
  B: '成长客户',
  C: '普通客户',
  D: '培育客户',
}
const statusLabels: Record<RuleStatus, string> = {
  DRAFT: '草稿',
  ACTIVE: '使用中',
  RETIRED: '已停用',
}
const auth = useAuthStore()
const canManage = computed(() => auth.hasRole('ADMIN') && auth.hasPermission('customer:write'))
const rules = ref<CustomerTierRule[]>([])
const selectedId = ref<number | null>(null)
const editingId = ref<number | null>(null)
const editingVersion = ref(0)
const editingStatus = ref<RuleStatus>('DRAFT')
const loading = ref(false)
const saving = ref(false)
const evaluating = ref(false)
const sampling = ref(false)
const statusChanging = ref<'activate' | 'retire' | null>(null)
const evaluations = ref<CustomerTierEvaluation[]>([])
const trialCustomerId = ref('')
const trialAsOf = ref('')
const form = reactive<{ ruleCode: string; ruleName: string; timezone: string; levels: LevelForm[] }>({
  ruleCode: '', ruleName: '', timezone: 'Asia/Shanghai', levels: [],
})

const currentVersionNo = computed(() => (
  rules.value.find((rule) => rule.id === editingId.value)?.versionNo ?? null
))
const missingConsumptionThresholds = computed(() => (
  editingId.value !== null && form.levels.some((level) => level.minConsumptionYuan === '')
))

function statusLabel(status: RuleStatus): string {
  return statusLabels[status] ?? '未知状态'
}

function statusClass(status: RuleStatus): string {
  return { DRAFT: 'is-draft', ACTIVE: 'is-active', RETIRED: 'is-retired' }[status]
}

function formatMoney(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : `¥${fenToYuan(value)}`
}

function amountToFen(rawValue: string, tierCode: CustomerTierCode): number {
  const raw = rawValue.trim()
  const match = /^(\d+)(?:\.(\d{1,2}))?$/.exec(raw)
  if (!match) {
    throw new Error(`${tierCode} 级最低实际消费金额需为不小于 0 的金额，最多保留两位小数`)
  }
  const centsText = `${match[1]}${(match[2] ?? '').padEnd(2, '0')}`.replace(/^0+(?=\d)/, '')
  const value = Number(centsText)
  if (!Number.isSafeInteger(value)) {
    throw new Error(`${tierCode} 级最低实际消费金额超出可填写范围`)
  }
  return value
}

function newRuleCode(): string {
  const timestamp = Date.now().toString(36).toUpperCase()
  const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 6).toUpperCase()
  return `TIER_${timestamp}_${suffix}`
}

function resetForm() {
  selectedId.value = null
  editingId.value = null
  editingVersion.value = 0
  editingStatus.value = 'DRAFT'
  Object.assign(form, {
    ruleCode: newRuleCode(), ruleName: '', timezone: 'Asia/Shanghai',
    levels: codes.map((tierCode, index) => ({
      tierCode,
      tierName: defaultLevelNames[tierCode],
      sortNo: index + 1,
      minConsumptionYuan: '',
    })),
  })
  evaluations.value = []
}

async function loadRules() {
  loading.value = true
  try {
    rules.value = await listCustomerTierRules()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '规则版本加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  resetForm()
  await loadRules()
})

function selectRule(rule: CustomerTierRule) {
  selectedId.value = rule.id
  editingId.value = rule.id
  editingVersion.value = rule.version
  editingStatus.value = rule.status
  Object.assign(form, {
    ruleCode: rule.ruleCode,
    ruleName: rule.ruleName,
    timezone: rule.timezone,
    levels: rule.levels.map((level) => ({
      tierCode: level.tierCode,
      tierName: level.tierName,
      sortNo: level.sortNo,
      minConsumptionYuan: level.minConsumptionFen === null ? '' : fenToYuan(level.minConsumptionFen),
    })),
  })
  evaluations.value = []
}

function cloneAsDraft() {
  editingId.value = null
  editingVersion.value = 0
  editingStatus.value = 'DRAFT'
  ElMessage.info('已复制当前配置，保存后生成新的草稿版本，原版本保持不变')
}

function buildRequest(): CustomerTierRuleRequest {
  if (!form.ruleCode.trim() || !form.ruleName.trim() || !form.timezone.trim()) {
    throw new Error('请填写规则编码、规则名称和统计时区')
  }
  if (form.levels.length !== 5) {
    throw new Error('请配置 S、A、B、C、D 五个等级')
  }
  return {
    ruleCode: form.ruleCode.trim(),
    ruleName: form.ruleName.trim(),
    timezone: form.timezone.trim(),
    levels: form.levels.map((level) => ({
      tierCode: level.tierCode,
      tierName: level.tierName.trim(),
      sortNo: level.sortNo,
      minScore: '0',
      minConsumptionFen: amountToFen(level.minConsumptionYuan, level.tierCode),
    })),
    metrics: [],
  }
}

async function save() {
  if (saving.value) return
  saving.value = true
  try {
    if (form.levels.some((level) => !level.tierName.trim())) {
      throw new Error('请完整填写各等级名称')
    }
    const request = buildRequest()
    const saved = editingId.value
      ? await updateCustomerTierRule(editingId.value, request, editingVersion.value, crypto.randomUUID())
      : await createCustomerTierRule(request, crypto.randomUUID())
    await loadRules()
    selectRule(saved)
    ElMessage.success('草稿已保存，可继续编辑或启用规则')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '规则保存失败，请检查填写内容后重试')
  } finally {
    saving.value = false
  }
}

async function changeStatus(action: 'activate' | 'retire') {
  if (!selectedId.value || statusChanging.value) return
  statusChanging.value = action
  try {
    const saved = action === 'activate'
      ? await activateCustomerTierRule(selectedId.value, editingVersion.value, crypto.randomUUID())
      : await retireCustomerTierRule(selectedId.value, editingVersion.value, crypto.randomUUID())
    await loadRules()
    selectRule(saved)
    ElMessage.success(action === 'activate' ? '规则已启用，当前版本配置已冻结' : '规则已停用，历史版本仍可查看')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : `${action === 'activate' ? '启用' : '停用'}规则失败，请稍后重试`)
  } finally {
    statusChanging.value = null
  }
}

async function runTrial() {
  if (!selectedId.value || !Number.isSafeInteger(Number(trialCustomerId.value)) || Number(trialCustomerId.value) <= 0) {
    ElMessage.warning('请先选择规则版本，并填写大于 0 的整数客户 ID')
    return
  }
  if (evaluating.value) return
  evaluating.value = true
  try {
    evaluations.value = [await trialCustomerTierRule(
      selectedId.value, Number(trialCustomerId.value), trialAsOf.value || undefined,
    )]
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '单客户试算失败，请核对客户 ID 后重试')
  } finally {
    evaluating.value = false
  }
}

async function runSample() {
  if (!selectedId.value) {
    ElMessage.warning('请先选择要抽样的规则版本')
    return
  }
  if (sampling.value) return
  sampling.value = true
  try {
    evaluations.value = await sampleCustomerTierRule(selectedId.value, 20)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '客户抽样失败，请稍后重试')
  } finally {
    sampling.value = false
  }
}
</script>

<template>
  <div class="proto-page tier-settings">
    <PageHeader
      title="客户自动分级"
      description="按滚动近 12 个月的实际消费金额建议等级；每个版本独立保存，启用后配置冻结。"
    >
      <template #actions>
        <el-button v-if="canManage" type="primary" data-testid="tier-new-rule" @click="resetForm">
          新建规则
        </el-button>
      </template>
    </PageHeader>

    <el-alert
      v-if="!canManage"
      type="info"
      :closable="false"
      title="当前账号不能维护分级规则，请联系管理员开通相应权限"
    />

    <div v-else class="tier-workspace">
      <aside class="version-panel" aria-labelledby="version-panel-title">
        <header class="version-heading">
          <div>
            <h2 id="version-panel-title">规则版本</h2>
            <p>选择版本查看或继续编辑</p>
          </div>
          <span class="version-count">{{ rules.length }} 个</span>
        </header>
        <div v-loading="loading" class="version-table">
          <el-table :data="rules" row-key="id" highlight-current-row @current-change="selectRule">
            <el-table-column label="规则" min-width="100">
              <template #default="{ row }">
                <div class="version-name">
                  <strong>{{ row.ruleCode }}</strong>
                  <span>{{ row.ruleName }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="versionNo" label="版本" width="58" align="center" />
            <el-table-column label="状态" width="82" align="center">
              <template #default="{ row }">
                <span class="status-pill" :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span>
              </template>
            </el-table-column>
            <template #empty>
              <div class="version-empty">
                <strong>还没有规则版本</strong>
                <span>点击右上角“新建规则”，填写各等级最低实际消费金额。</span>
              </div>
            </template>
          </el-table>
        </div>
        <p class="version-note">草稿可编辑；使用中与已停用版本仅可查看。</p>
      </aside>

      <main class="rule-main">
        <section class="editor-panel" aria-labelledby="editor-title">
          <header class="editor-heading">
            <div class="editor-heading-copy">
              <div class="editor-title-line">
                <h2 id="editor-title">{{ editingId ? '编辑规则' : '新建分级规则' }}</h2>
                <span class="status-pill" :class="statusClass(editingStatus)">{{ statusLabel(editingStatus) }}</span>
              </div>
              <p v-if="editingId" class="rule-identity">
                {{ form.ruleCode }}<span v-if="currentVersionNo !== null"> · 第 {{ currentVersionNo }} 版</span>
              </p>
              <p v-else>填写基本信息和各等级最低实际消费金额后保存草稿。</p>
            </div>
            <div class="editor-actions">
              <el-button v-if="selectedId" :disabled="statusChanging !== null" @click="cloneAsDraft">
                复制为新版本
              </el-button>
              <el-button
                v-if="editingStatus === 'DRAFT'"
                type="primary"
                :loading="saving"
                :disabled="statusChanging !== null"
                data-testid="tier-save-rule"
                @click="save"
              >
                保存草稿
              </el-button>
              <el-button
                v-if="editingStatus === 'DRAFT' && selectedId"
                :loading="statusChanging === 'activate'"
                :disabled="saving"
                data-testid="tier-activate-rule"
                @click="changeStatus('activate')"
              >
                启用规则
              </el-button>
              <el-button
                v-if="editingStatus === 'ACTIVE' && selectedId"
                :loading="statusChanging === 'retire'"
                :disabled="saving"
                data-testid="tier-retire-rule"
                @click="changeStatus('retire')"
              >
                停用规则
              </el-button>
            </div>
          </header>

          <div v-if="editingStatus !== 'DRAFT'" class="freeze-note" role="note">
            当前版本仅可查看。需要调整时，请点击“复制为新版本”，不会覆盖历史配置。
          </div>
          <el-alert
            v-if="missingConsumptionThresholds"
            class="legacy-note"
            type="warning"
            :closable="false"
            title="该历史版本尚未配置消费金额门槛，复制为新版本并补齐金额后才能使用金额分级"
          />

          <el-form label-position="top" class="rule-form" @submit.prevent="save">
            <section class="form-section" aria-labelledby="basic-info-title">
              <header class="section-heading">
                <div>
                  <h3 id="basic-info-title">基本信息</h3>
                  <p>规则编码用于区分配置，统计时区用于确定滚动近 12 个月的统计窗口。</p>
                </div>
              </header>
              <div class="base-grid">
                <el-form-item label="规则编码" required>
                  <el-input
                    v-model="form.ruleCode"
                    :disabled="editingId !== null"
                    maxlength="64"
                    autocomplete="off"
                    data-testid="tier-rule-code"
                  />
                  <span class="field-help">系统自动生成，可按需修改；保存后不可修改。</span>
                </el-form-item>
                <el-form-item label="规则名称" required>
                  <el-input
                    v-model="form.ruleName"
                    :disabled="editingStatus !== 'DRAFT'"
                    maxlength="128"
                    autocomplete="off"
                  />
                </el-form-item>
                <el-form-item label="统计时区" required>
                  <el-select v-model="form.timezone" :disabled="editingStatus !== 'DRAFT'">
                    <el-option label="北京时间（UTC+8）" value="Asia/Shanghai" />
                  </el-select>
                </el-form-item>
              </div>
            </section>

            <section class="form-section" aria-labelledby="level-settings-title">
              <header class="section-heading">
                <div>
                  <h3 id="level-settings-title">等级与消费门槛</h3>
                  <p>名称仅用于页面显示，可按业务习惯修改，不影响分级计算；金额以元填写，最多两位小数。</p>
                </div>
              </header>
              <div class="consumption-note">
                <strong>实际消费金额 ＝ 有效订单实付金额 － 成功退款金额，负数按 0 计算。</strong>
                <span>从 S 到 D 逐级比较，取第一个达到最低消费金额的等级；高等级门槛不得更低，D 级门槛须为 0 元。</span>
              </div>
              <div class="level-list">
                <div v-for="level in form.levels" :key="level.tierCode" class="level-row">
                  <div class="level-code" aria-label="等级">{{ level.tierCode }}<span>级</span></div>
                  <el-form-item :label="`${level.tierCode} 级名称`" required>
                    <el-input
                      v-model="level.tierName"
                      :disabled="editingStatus !== 'DRAFT'"
                      maxlength="64"
                      :data-testid="`tier-level-name-${level.tierCode}`"
                    />
                  </el-form-item>
                  <el-form-item :label="`${level.tierCode} 级顺序`" required>
                    <el-input
                      v-model.number="level.sortNo"
                      type="number"
                      min="1"
                      max="5"
                      :disabled="editingStatus !== 'DRAFT'"
                    />
                  </el-form-item>
                  <el-form-item :label="`${level.tierCode} 级最低实际消费金额`" required>
                    <el-input
                      v-model="level.minConsumptionYuan"
                      :disabled="editingStatus !== 'DRAFT'"
                      inputmode="decimal"
                      placeholder="请输入金额"
                      :data-testid="`tier-min-consumption-${level.tierCode}`"
                    >
                      <template #append>元</template>
                    </el-input>
                  </el-form-item>
                </div>
              </div>
            </section>
          </el-form>
        </section>

        <section class="trial-panel" aria-labelledby="trial-title">
          <header class="section-heading trial-heading">
            <div>
              <h2 id="trial-title">试算结果</h2>
              <p>试算与抽样仅用于核对规则效果，不修改客户等级或历史结果；实际消费金额按所选时间向前滚动 12 个月计算。</p>
            </div>
          </header>
          <el-form label-position="top" class="trial-form" @submit.prevent="runTrial">
            <div class="trial-form-grid">
              <el-form-item label="客户 ID" required>
                <el-input
                  v-model="trialCustomerId"
                  inputmode="numeric"
                  autocomplete="off"
                  data-testid="tier-trial-customer"
                />
              </el-form-item>
              <el-form-item label="计算时间">
                <el-date-picker
                  v-model="trialAsOf"
                  type="datetime"
                  format="YYYY-MM-DD HH:mm"
                  value-format="YYYY-MM-DD[T]HH:mm:ssZ"
                  placeholder="不选择则按当前时间计算"
                  class="trial-date-picker"
                  data-testid="tier-trial-time"
                />
              </el-form-item>
            </div>
            <div class="trial-actions">
              <span>请先选择规则版本；留空计算时间即按当前时间试算。</span>
              <div>
                <el-button
                  type="primary"
                  native-type="submit"
                  :disabled="!selectedId || sampling"
                  :loading="evaluating"
                  data-testid="tier-run-trial"
                >
                  单客户试算
                </el-button>
                <el-button
                  :disabled="!selectedId || evaluating"
                  :loading="sampling"
                  data-testid="tier-sample-rule"
                  @click="runSample"
                >
                  抽样 20 位客户
                </el-button>
              </div>
            </div>
          </el-form>

          <div v-loading="evaluating || sampling" class="evaluation-results">
            <template v-if="evaluations.length">
              <div class="evaluation-table">
                <el-table :data="evaluations">
                  <el-table-column prop="customerId" label="客户 ID" min-width="100" />
                  <el-table-column label="实际消费金额" min-width="144">
                    <template #default="{ row }">{{ formatMoney(row.actualConsumptionFen) }}</template>
                  </el-table-column>
                  <el-table-column label="订单实付金额" min-width="136">
                    <template #default="{ row }">{{ formatMoney(row.paidAmountFen) }}</template>
                  </el-table-column>
                  <el-table-column label="成功退款金额" min-width="136">
                    <template #default="{ row }">{{ formatMoney(row.refundAmountFen) }}</template>
                  </el-table-column>
                  <el-table-column label="建议等级" min-width="96" fixed="right">
                    <template #default="{ row }">{{ row.suggestedTierCode ? `${row.suggestedTierCode} 级` : '—' }}</template>
                  </el-table-column>
                </el-table>
              </div>
              <div class="evaluation-cards">
                <article v-for="row in evaluations" :key="`${row.customerId}-${row.asOf}`" class="evaluation-card">
                  <header>
                    <strong>客户 {{ row.customerId }}</strong>
                    <span>建议 {{ row.suggestedTierCode ? `${row.suggestedTierCode} 级` : '暂无等级' }}</span>
                  </header>
                  <dl>
                    <div><dt>实际消费金额</dt><dd>{{ formatMoney(row.actualConsumptionFen) }}</dd></div>
                    <div><dt>订单实付金额</dt><dd>{{ formatMoney(row.paidAmountFen) }}</dd></div>
                    <div><dt>成功退款金额</dt><dd>{{ formatMoney(row.refundAmountFen) }}</dd></div>
                  </dl>
                </article>
              </div>
            </template>
            <div v-else class="evaluation-empty">
              <strong>{{ selectedId ? '等待试算或抽样' : '先选择一个规则版本' }}</strong>
              <p>{{ selectedId ? '填写客户 ID 进行单客户试算，或抽样 20 位客户查看结果。' : '从规则版本列表中选择后，即可核对实际客户的分级结果。' }}</p>
            </div>
          </div>
        </section>
      </main>
    </div>
  </div>
</template>

<style scoped>
.tier-settings,
.tier-workspace,
.rule-main {
  min-width: 0;
}

.tier-settings {
  color: #203f3c;
}

.tier-workspace {
  display: grid;
  grid-template-columns: minmax(230px, 264px) minmax(0, 1fr);
  gap: 20px;
  align-items: start;
}

.version-panel,
.editor-panel,
.trial-panel {
  min-width: 0;
  border: 1px solid #dfeae8;
  border-radius: 13px;
  background: #fdfefe;
  box-shadow: 0 5px 22px rgb(22 67 62 / 4%);
}

.version-panel {
  overflow: hidden;
}

.version-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 17px 16px 13px;
  border-bottom: 1px solid #e8f0ee;
}

.version-heading h2,
.editor-heading h2,
.trial-heading h2 {
  margin: 0;
  color: #173d39;
  font-size: 16px;
  font-weight: 750;
  line-height: 1.4;
}

.version-heading p,
.editor-heading p,
.section-heading p {
  margin: 4px 0 0;
  color: #7b918e;
  font-size: 12px;
  line-height: 1.65;
}

.version-count {
  flex: none;
  color: #69847f;
  font-size: 11px;
}

.version-table {
  min-height: 88px;
}

.version-table :deep(.el-table) {
  --el-table-header-bg-color: #f7faf9;
  --el-table-current-row-bg-color: #eaf6f2;
  --el-table-row-hover-bg-color: #f0f8f5;
  font-size: 12px;
}

.version-table :deep(th.el-table__cell) {
  height: 38px;
}

.version-table :deep(td.el-table__cell) {
  padding: 10px 0;
  cursor: pointer;
}

.version-table :deep(.el-table .cell) {
  padding: 0 8px;
}

.version-name {
  min-width: 0;
}

.version-name strong,
.version-name span {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-name strong {
  color: #294c47;
  font-size: 12px;
  font-weight: 700;
}

.version-name span {
  margin-top: 3px;
  color: #8a9c99;
  font-size: 11px;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 24px;
  padding: 3px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 650;
  line-height: 1.4;
  white-space: nowrap;
}

.status-pill.is-draft {
  color: #876127;
  background: #fff5df;
}

.status-pill.is-active {
  color: #176e5c;
  background: #e4f4ed;
}

.status-pill.is-retired {
  color: #6d7d7a;
  background: #edf1f0;
}

.version-note {
  margin: 0;
  padding: 12px 16px 14px;
  border-top: 1px solid #e8f0ee;
  color: #809590;
  font-size: 11px;
  line-height: 1.65;
}

.version-empty {
  display: grid;
  gap: 6px;
  padding: 20px 12px;
  color: #829590;
  line-height: 1.6;
}

.version-empty strong {
  color: #46645f;
  font-size: 12px;
}

.rule-main {
  display: grid;
  gap: 18px;
}

.editor-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 20px 22px;
}

.editor-heading-copy {
  min-width: 0;
}

.editor-title-line {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.rule-identity {
  overflow-wrap: anywhere;
}

.editor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.editor-actions .el-button + .el-button,
.trial-actions .el-button + .el-button {
  margin-left: 0;
}

.freeze-note {
  margin: 0 22px;
  padding: 11px 13px;
  border: 1px solid #eee2c8;
  border-radius: 8px;
  color: #826536;
  background: #fffaf0;
  font-size: 12px;
  line-height: 1.65;
}

.legacy-note {
  margin: 14px 22px 0;
  width: auto;
}

.rule-form {
  padding: 0 22px;
}

.form-section {
  padding: 20px 0 22px;
  border-top: 1px solid #e5eeeb;
}

.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 17px;
}

.section-heading h3 {
  margin: 0;
  color: #244944;
  font-size: 14px;
  font-weight: 720;
  line-height: 1.45;
}

.base-grid {
  display: grid;
  grid-template-columns: minmax(150px, .85fr) minmax(190px, 1.25fr) minmax(180px, 1fr);
  gap: 14px;
}

.rule-form :deep(.el-form-item),
.trial-form :deep(.el-form-item) {
  min-width: 0;
  margin-bottom: 0;
}

.rule-form :deep(.el-form-item__label),
.trial-form :deep(.el-form-item__label) {
  height: auto;
  margin-bottom: 7px;
  padding: 0;
  color: #526f6a;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.5;
}

.rule-form :deep(.el-input),
.rule-form :deep(.el-select),
.trial-form :deep(.el-input),
.trial-form :deep(.el-select) {
  width: 100%;
}

.tier-settings :deep(.el-button) {
  min-height: 44px;
}

.tier-settings :deep(.el-input__wrapper),
.tier-settings :deep(.el-select__wrapper) {
  min-height: 44px;
}

.field-help {
  display: block;
  margin-top: 6px;
  color: #8a9c98;
  font-size: 11px;
  font-weight: 400;
  line-height: 1.55;
}

.consumption-note {
  display: grid;
  gap: 5px;
  margin-bottom: 16px;
  padding: 13px 15px;
  border-radius: 9px;
  color: #42645e;
  background: #f0f7f4;
  font-size: 12px;
  line-height: 1.7;
}

.consumption-note strong {
  color: #176e5c;
  font-size: 13px;
}

.level-list {
  display: grid;
  gap: 12px;
}

.level-row {
  display: grid;
  grid-template-columns: 54px minmax(170px, 1.1fr) minmax(95px, .5fr) minmax(190px, 1fr);
  gap: 12px;
  align-items: start;
  padding: 14px;
  border: 1px solid #e7efed;
  border-radius: 10px;
  background: #fafcfb;
}

.level-code {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 3px;
  min-height: 44px;
  margin-top: 26px;
  border-radius: 8px;
  color: #126f64;
  background: #e6f3ef;
  font-size: 19px;
  font-weight: 760;
  line-height: 44px;
}

.level-code span {
  font-size: 10px;
  font-weight: 650;
}

.rule-form :deep(.el-input-group__append) {
  padding: 0 12px;
  color: #526f6a;
  background: #f0f5f3;
}

.trial-panel {
  padding: 20px 22px 22px;
}

.trial-heading {
  margin-bottom: 16px;
}

.trial-form-grid {
  display: grid;
  grid-template-columns: minmax(170px, .7fr) minmax(240px, 1fr);
  gap: 14px;
  max-width: 650px;
}

.trial-date-picker {
  width: 100%;
}

.trial-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 16px;
}

.trial-actions > span {
  color: #819691;
  font-size: 11px;
  line-height: 1.6;
}

.trial-actions > div {
  display: flex;
  gap: 8px;
  flex: none;
}

.evaluation-results {
  min-height: 86px;
  margin-top: 20px;
  padding-top: 18px;
  border-top: 1px solid #e5eeeb;
}

.evaluation-table {
  width: 100%;
  overflow-x: auto;
}

.evaluation-table :deep(.el-table) {
  min-width: 680px;
}

.evaluation-empty {
  padding: 18px 0 8px;
  color: #80958f;
  text-align: center;
}

.evaluation-empty strong {
  color: #46665f;
  font-size: 13px;
}

.evaluation-empty p {
  margin: 7px 0 0;
  font-size: 12px;
  line-height: 1.65;
}

.evaluation-cards {
  display: none;
}

@media (max-width: 1120px) {
  .tier-workspace {
    grid-template-columns: 1fr;
  }

  .version-name strong,
  .version-name span {
    white-space: normal;
  }

  .version-table :deep(.el-table .cell) {
    padding: 0 16px;
  }
}

@media (max-width: 900px) {
  .base-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .base-grid > :last-child {
    grid-column: 1 / -1;
  }

  .level-row {
    grid-template-columns: 48px minmax(150px, 1fr) minmax(95px, .5fr);
  }

  .level-row > :last-child {
    grid-column: 2 / -1;
  }

  .trial-actions {
    align-items: flex-start;
    flex-direction: column;
  }
}

@media (max-width: 640px) {
  .tier-workspace,
  .rule-main {
    gap: 14px;
  }

  .editor-heading {
    flex-direction: column;
    padding: 17px 16px;
  }

  .editor-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .freeze-note,
  .legacy-note {
    margin-right: 16px;
    margin-left: 16px;
  }

  .rule-form {
    padding: 0 16px;
  }

  .form-section {
    padding: 18px 0;
  }

  .base-grid,
  .trial-form-grid {
    grid-template-columns: 1fr;
  }

  .base-grid > :last-child {
    grid-column: auto;
  }

  .level-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    padding: 13px;
  }

  .level-code {
    grid-column: 1 / -1;
    justify-content: flex-start;
    min-height: 36px;
    margin-top: 0;
    padding: 0 12px;
    line-height: 36px;
  }

  .level-row > :nth-child(2) {
    grid-column: 1 / -1;
  }

  .level-row > :last-child {
    grid-column: auto;
  }

  .trial-panel {
    padding: 18px 16px;
  }

  .trial-actions,
  .trial-actions > div {
    width: 100%;
  }

  .trial-actions > div {
    flex-direction: column;
  }

  .trial-actions .el-button {
    width: 100%;
  }

  .evaluation-table {
    display: none;
  }

  .evaluation-cards {
    display: grid;
    gap: 12px;
  }

  .evaluation-card {
    padding: 14px;
    border: 1px solid #e2ece8;
    border-radius: 10px;
    background: #fafcfb;
  }

  .evaluation-card header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    padding-bottom: 11px;
    border-bottom: 1px solid #e6eeeb;
  }

  .evaluation-card header strong {
    color: #294d46;
    font-size: 13px;
  }

  .evaluation-card header span {
    color: #0f766a;
    font-size: 12px;
    font-weight: 650;
  }

  .evaluation-card dl {
    display: grid;
    gap: 0;
    margin: 5px 0 0;
  }

  .evaluation-card dl > div {
    display: flex;
    justify-content: space-between;
    gap: 14px;
    padding: 8px 0;
  }

  .evaluation-card dt {
    color: #7c918c;
    font-size: 12px;
  }

  .evaluation-card dd {
    margin: 0;
    color: #304f49;
    font-size: 12px;
    font-weight: 650;
    text-align: right;
  }
}

@media (max-width: 400px) {
  .editor-actions {
    flex-direction: column;
  }

  .editor-actions .el-button {
    width: 100%;
  }
}
</style>
