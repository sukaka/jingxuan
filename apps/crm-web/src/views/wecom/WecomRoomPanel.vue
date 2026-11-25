<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { listWecomRooms, saveWecomRoom } from '../../api/wecomRoom'
import type { WecomRoom } from '../../api/wecomRoom'
import { tradingErrorText } from '../trading/tradingUi'
import { createWecomKey } from './wecomUi'

const props = defineProps<{ corpId: string; canWrite: boolean }>()
const emit = defineEmits<{ saved: [] }>()
const rooms = ref<WecomRoom[]>([])
const loading = ref(false)
const saving = ref(false)
const errorMessage = ref('')
const form = reactive({ roomId: '', displayName: '', members: '', sourceEvidenceRef: '', reason: '', enabled: true })
const selectedRoomId = ref<string | null>(null)
const editing = ref(false)
let requestSequence = 0
let attempt: { signature: string; key: string } | null = null
const selected = computed(() => rooms.value.find((room) => room.roomId === selectedRoomId.value) || null)
const activeMembers = computed(() => selected.value?.participants.filter((item) => item.status === 'ACTIVE') || [])
const removedMembers = computed(() => selected.value?.participants.filter((item) => item.status === 'REMOVED') || [])

async function load(preferredRoom?: string) {
  const sequence = ++requestSequence
  if (!props.corpId.trim()) {
    rooms.value = []
    selectedRoomId.value = null
    return
  }
  loading.value = true
  errorMessage.value = ''
  try {
    const result = await listWecomRooms(props.corpId.trim())
    if (sequence !== requestSequence) return
    rooms.value = result
    selectedRoomId.value = result.some((room) => room.roomId === preferredRoom)
      ? preferredRoom!
      : result[0]?.roomId || null
  } catch (error) {
    if (sequence !== requestSequence) return
    rooms.value = []
    selectedRoomId.value = null
    errorMessage.value = tradingErrorText(error)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

function create() {
  selectedRoomId.value = null
  form.roomId = ''
  form.displayName = ''
  form.members = ''
  form.sourceEvidenceRef = ''
  form.reason = ''
  form.enabled = true
  attempt = null
  editing.value = true
  errorMessage.value = ''
}

function edit(room: WecomRoom) {
  selectedRoomId.value = room.roomId
  form.roomId = room.roomId
  form.displayName = room.displayName || ''
  form.members = room.participants.filter((item) => item.status === 'ACTIVE').map((item) => item.wecomUserid).join('\n')
  form.sourceEvidenceRef = ''
  form.reason = ''
  form.enabled = room.enabled
  attempt = null
  editing.value = true
  errorMessage.value = ''
}

async function save() {
  if (!props.canWrite || saving.value) return
  const members = [...new Set(form.members.split(/[\s，,]+/).map((item) => item.trim()).filter(Boolean))]
  if (!form.roomId.trim() || !members.length || !form.sourceEvidenceRef.trim() || !form.reason.trim()) {
    errorMessage.value = '请填写群聊编号（RoomID）、完整成员清单、清单来源证据和操作理由'
    return
  }
  if (members.length > 500) {
    errorMessage.value = '单个群聊最多确认 500 个成员'
    return
  }
  const body = {
    corpId: props.corpId.trim(), displayName: form.displayName.trim() || undefined, enabled: form.enabled,
    memberUserids: members, sourceEvidenceRef: form.sourceEvidenceRef.trim(),
    version: selected.value?.version || 0, reason: form.reason.trim(),
  }
  const signature = `${form.roomId.trim()}:${JSON.stringify(body)}`
  if (attempt?.signature !== signature) attempt = { signature, key: createWecomKey('wecom.room.save') }
  saving.value = true
  errorMessage.value = ''
  try {
    const result = await saveWecomRoom(form.roomId.trim(), body, attempt.key)
    attempt = null
    editing.value = false
    await load(result.roomId)
    emit('saved')
    ElMessage.success('群聊与完整成员清单已保存；不代表供应商线上权限已验收')
  } catch (error) {
    errorMessage.value = `${tradingErrorText(error)}；重新提交不会重复保存`
  } finally {
    saving.value = false
  }
}

watch(() => props.corpId, () => {
  editing.value = false
  attempt = null
  void load()
}, { immediate: true })
</script>

<template>
  <section class="room-settings">
    <div class="room-intro">
      <div><span class="room-eyebrow">群聊归档</span><h3>群聊归档授权</h3></div>
      <p>以群聊编号独立保存群会话，不任选成员充当客户。仅登记管理员已核对的完整成员清单；外部参与人的同意和员工归档覆盖由系统逐条校验。</p>
      <el-button v-if="canWrite" type="primary" data-testid="wecom-room-create" @click="create">登记群聊</el-button>
    </div>
    <el-alert type="info" :closable="false" title="这里没有自动读取群成员名单；清单来源应可追溯到企业授权核对记录。群聊停用只影响新归档，历史事实不删除。" />

    <div v-if="errorMessage" class="room-feedback" role="alert">{{ errorMessage }}</div>
    <div v-loading="loading" class="room-layout">
      <div class="room-list">
        <div class="room-list-head"><span>已登记群聊</span><span>{{ rooms.length }} 个</span></div>
        <button v-for="room in rooms" :key="room.roomId" type="button" class="room-item" :class="{ active: selectedRoomId === room.roomId }" @click="selectedRoomId = room.roomId; editing = false">
          <span class="room-item-title">{{ room.displayName || '未命名群聊' }}<i :class="{ ready: room.archivable }" /></span>
          <span class="room-id">{{ room.roomId }}</span>
          <span class="room-meta">{{ room.participants.filter((item) => item.status === 'ACTIVE').length }} 位当前成员 · 清单版本 {{ room.version }}</span>
          <span class="room-state">{{ !room.enabled ? '已停用新归档' : room.archivable ? '授权条件已满足' : '授权条件待补齐' }}</span>
        </button>
        <div v-if="!rooms.length && !loading" class="room-empty"><strong>尚未登记群聊</strong><span>先完成员工映射、成员覆盖与外部联系人同意，再确认完整成员清单。</span></div>
      </div>

      <div class="room-detail">
        <template v-if="editing">
          <div class="room-detail-head"><div><h3>{{ selected ? '更新完整成员清单' : '登记群聊与成员' }}</h3><p>{{ selected ? `当前清单版本 ${selected.version}，保存需通过版本校验` : '新群聊从初始版本 0 开始' }}</p></div><el-button text @click="editing = false">取消</el-button></div>
          <form class="room-form" data-testid="wecom-room-form" @submit.prevent="save">
            <div class="room-form-grid">
              <label>企业标识（CorpID）<input :value="corpId" disabled></label>
              <label>群聊编号（RoomID）<input v-model="form.roomId" :disabled="!!selected" maxlength="192" required placeholder="企微官方群聊编号，不是群名称" data-testid="wecom-room-id"></label>
              <label>显示名称（选填）<input v-model="form.displayName" maxlength="128" placeholder="仅用于 CRM 展示，不冒充官方群名"></label>
              <label class="room-switch">允许新消息归档<input v-model="form.enabled" type="checkbox" role="switch"><small>停用后仍按权限保留历史记录</small></label>
            </div>
            <label class="room-roster">完整成员企微标识（每行一个）<textarea v-model="form.members" rows="7" required placeholder="企微员工账号或外部联系人标识；请勿填写昵称、手机号" data-testid="wecom-room-members" /><small>保存即确认清单完整；未列出的旧成员将标记为已退出，不删除历史消息。</small></label>
            <div class="room-form-grid">
              <label>清单来源证据引用<input v-model="form.sourceEvidenceRef" maxlength="255" required placeholder="内部核对记录或授权凭证引用" data-testid="wecom-room-evidence"></label>
              <label>本次操作理由<input v-model="form.reason" maxlength="1000" required placeholder="说明成员核对或调整原因" data-testid="wecom-room-reason"></label>
            </div>
            <div class="room-form-actions"><span>成员身份、同意与员工覆盖由系统复核</span><el-button type="primary" native-type="submit" :loading="saving" data-testid="wecom-room-save">保存完整清单</el-button></div>
          </form>
        </template>
        <template v-else-if="selected">
          <div class="room-detail-head"><div><h3>{{ selected.displayName || '未命名群聊' }}</h3><p class="room-id">{{ selected.roomId }}</p></div><el-button v-if="canWrite" type="primary" plain data-testid="wecom-room-edit" @click="edit(selected)">核对并更新清单</el-button></div>
          <dl class="room-facts"><div><dt>当前状态</dt><dd>{{ selected.enabled ? '允许新归档' : '已停用新归档' }}</dd></div><div><dt>归档资格</dt><dd>{{ selected.archivable ? '成员、同意与覆盖均已满足' : '仍有条件未满足' }}</dd></div><div><dt>完整清单版本</dt><dd>版本 {{ selected.version }}</dd></div><div><dt>最近确认</dt><dd>{{ new Date(selected.membershipConfirmedAt).toLocaleString('zh-CN', { hour12: false }) }}</dd></div></dl>
          <div class="member-head"><h4>当前参与人</h4><span>{{ activeMembers.length }} 人</span></div>
          <div class="member-list"><div v-for="member in activeMembers" :key="member.wecomUserid" class="member-row"><span class="member-type" :class="member.participantType.toLowerCase()">{{ member.participantType === 'STAFF' ? '企微员工' : '外部参与人' }}</span><div><strong>{{ member.wecomUserid }}</strong><small v-if="member.staffUserId !== null">已映射 CRM 员工 #{{ member.staffUserId }}</small><small v-else>不代表已关联某个 CRM 客户</small></div><span class="member-status">在群</span></div></div>
          <details v-if="removedMembers.length" class="removed-members"><summary>已退出成员（{{ removedMembers.length }}）</summary><div v-for="member in removedMembers" :key="member.wecomUserid" class="removed-row">{{ member.wecomUserid }}<span>保留历史身份记录</span></div></details>
        </template>
        <div v-else class="room-empty"><strong>选择群聊查看授权状态</strong><span>群消息将在单独的会话列表展示，不归入某一个客户。</span></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.room-settings { color: #284942; }
.room-intro { display: grid; grid-template-columns: minmax(190px,.7fr) minmax(280px,1.5fr) auto; align-items: center; gap: 24px; margin-bottom: 16px; }
.room-eyebrow { color: #719187; font-size: 10px; font-weight: 700; letter-spacing: .12em; }
.room-intro h3, .room-detail-head h3 { margin: 5px 0 0; color: #1f433a; font-size: 17px; font-weight: 680; }
.room-intro p { margin: 0; color: #6d827b; font-size: 12px; line-height: 1.8; }
.room-feedback { margin-top: 14px; padding: 11px 14px; border: 1px solid #efd8cc; border-radius: 8px; color: #a1513a; background: #fdf7f2; font-size: 12px; line-height: 1.7; }
.room-layout { display: grid; grid-template-columns: minmax(245px,.65fr) minmax(0,1.6fr); min-height: 400px; margin-top: 20px; border-top: 1px solid #e1eae6; }
.room-list { padding: 16px 18px 16px 0; border-right: 1px solid #e1eae6; }
.room-list-head, .member-head { display: flex; align-items: center; justify-content: space-between; padding: 0 3px 10px; color: #7b8e87; font-size: 11px; }
.room-item { display: grid; width: 100%; gap: 5px; margin-bottom: 4px; padding: 13px 14px; border: 1px solid transparent; border-radius: 8px; color: inherit; background: transparent; font: inherit; text-align: left; cursor: pointer; transition: background .2s ease-out, border-color .2s ease-out; }
.room-item:hover { background: #f5f9f7; }
.room-item.active { border-color: #d2e5da; background: #eef6f1; }
.room-item-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #36574c; font-size: 13px; font-weight: 650; }
.room-item-title i { width: 6px; height: 6px; border-radius: 50%; background: #bb9871; }
.room-item-title i.ready { background: #5c9475; }
.room-id { overflow-wrap: anywhere; color: #7b8e86; font-size: 11px; }
.room-meta, .room-state { color: #82958d; font-size: 10px; }
.room-state { margin-top: 3px; color: #4c7e65; }
.room-detail { min-width: 0; padding: 18px 0 16px 24px; }
.room-detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 20px; }
.room-detail-head p { margin: 5px 0 0; }
.room-facts { display: grid; grid-template-columns: repeat(4,minmax(0,1fr)); gap: 14px; margin: 0 0 26px; padding: 0 0 20px; border-bottom: 1px solid #e8eeeb; }
.room-facts dt { margin-bottom: 6px; color: #83968e; font-size: 10px; }
.room-facts dd { margin: 0; color: #395d4f; font-size: 12px; line-height: 1.7; }
.member-head h4 { margin: 0; color: #476357; font-size: 12px; font-weight: 650; }
.member-row { display: flex; align-items: center; gap: 12px; min-height: 56px; border-bottom: 1px solid #edf2ef; }
.member-type { flex: 0 0 auto; padding: 3px 6px; border-radius: 4px; color: #88704d; background: #f6f1e8; font-size: 10px; }
.member-type.staff { color: #3e7961; background: #eef6f0; }
.member-row strong { display: block; overflow-wrap: anywhere; color: #3b584d; font-size: 12px; font-weight: 600; }
.member-row small { display: block; margin-top: 3px; color: #8c9e96; font-size: 10px; }
.member-status { margin-left: auto; color: #6a8c79; font-size: 10px; white-space: nowrap; }
.removed-members { margin-top: 20px; color: #83938c; font-size: 11px; }
.removed-members summary { cursor: pointer; }
.removed-row { display: flex; justify-content: space-between; gap: 12px; padding: 9px 0; border-bottom: 1px solid #edf2ef; overflow-wrap: anywhere; }
.room-form { display: grid; gap: 18px; }
.room-form-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; align-items: start; }
.room-form label { display: grid; min-width: 0; gap: 7px; color: #5b776a; font-size: 11px; font-weight: 600; }
.room-form input:not([type="checkbox"]), .room-form textarea { box-sizing: border-box; width: 100%; min-width: 0; padding: 10px 12px; border: 1px solid #d5e3dc; border-radius: 6px; outline: none; color: #304e40; background: #fcfdfc; font: inherit; font-size: 12px; line-height: 1.65; transition: border-color .18s ease-out, box-shadow .18s ease-out; }
.room-form input:not([type="checkbox"]) { height: 40px; }
.room-form textarea { min-height: 148px; resize: vertical; }
.room-form input:focus, .room-form textarea:focus { border-color: #7cab93; box-shadow: 0 0 0 3px rgb(80 138 106 / 9%); }
.room-form input:disabled { color: #83968b; background: #f3f6f4; }
.room-form label small { color: #85988f; font-size: 10px; font-weight: 400; line-height: 1.7; }
.room-switch { align-content: start; }
.room-switch input { width: 17px; height: 17px; margin: 3px 0 2px; accent-color: #36775b; }
.room-form-actions { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding-top: 4px; }
.room-form-actions > span { color: #82968a; font-size: 10px; }
.room-empty { display: flex; min-height: 170px; align-items: center; justify-content: center; flex-direction: column; gap: 8px; padding: 25px; color: #82938b; font-size: 12px; line-height: 1.8; text-align: center; }
.room-empty strong { color: #4b6758; font-size: 13px; }
@media (max-width: 1000px) { .room-intro { grid-template-columns: 1fr auto; } .room-intro p { grid-column: 1 / -1; grid-row: 2; } .room-facts { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (max-width: 720px) { .room-layout { grid-template-columns: 1fr; } .room-list { padding-right: 0; border-right: 0; } .room-detail { padding: 20px 0 0; border-top: 1px solid #e1eae6; } .room-form-grid { grid-template-columns: 1fr; } .room-form-actions { align-items: stretch; flex-direction: column; } .room-form-actions .el-button { width: 100%; } }
</style>
