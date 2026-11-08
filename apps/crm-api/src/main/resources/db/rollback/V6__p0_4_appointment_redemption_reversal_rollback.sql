-- 仅用于开发应急回滚；先备份预约、核销、权益账本与审计，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_redemption_reversal_history`;
DROP TABLE IF EXISTS `crm_redemption_reversal`;
DROP TABLE IF EXISTS `crm_redemption_status_history`;
DROP TABLE IF EXISTS `crm_redemption_line`;
DROP TABLE IF EXISTS `crm_redemption`;
DROP TABLE IF EXISTS `crm_appointment_history`;
DROP TABLE IF EXISTS `crm_appointment_item`;
DROP TABLE IF EXISTS `crm_appointment`;
