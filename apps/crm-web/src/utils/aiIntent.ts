export const AI_INTENT_LABELS: Record<string, string> = {
  PACKAGE_QUERY: '套餐与权益查询',
  APPOINTMENT_INTENT: '预约意向',
  VISIT_INTENT: '到院 / 面诊意向',
  AFTERCARE_FEEDBACK: '术后与服务后反馈',
  PRICE_INQUIRY: '价格咨询',
  REFUND_INTENT: '退款意向（待核实）',
  COMPLAINT: '投诉 / 不满（待核实）',
  REPURCHASE_INTENT: '复购意向',
  FOLLOWUP_REQUIRED: '需人工回访',
  RISK_EVENT: '高风险线索（待人工复核）',
  SERVICE_EXPERIENCE: '服务体验反馈',
  SERVICE_COMPLETION_CLAIM: '已完成服务陈述（待核实）',
  GENERAL: '普通咨询 / 未明确意图',
}

export function aiIntentLabel(type: string | null | undefined): string {
  return type ? AI_INTENT_LABELS[type] || '未知意图，需人工复核' : '—'
}
