-- 回滚 V57__phase2_high_yield_schema.sql
-- 与 V57 严格互逆：按逆依赖顺序移除索引、生成列、约束、投影表与新增列，恢复至 V56 状态。

-- ============================================================================
-- 5. 回滚 P1-A7 / P1-P7 低风险约束与列表索引
-- ============================================================================
ALTER TABLE `crm_profile_change_request`
  DROP INDEX `idx_profile_change_status_id`;

ALTER TABLE `crm_institution`
  DROP INDEX `idx_institution_archived_status_id`;

ALTER TABLE `crm_order`
  DROP INDEX `uk_order_external_dedupe`,
  DROP COLUMN `external_order_dedupe_key`;

ALTER TABLE `crm_wecom_data_gap`
  MODIFY COLUMN `from_seq` BIGINT UNSIGNED NULL COMMENT '缺口起始seq';

-- ============================================================================
-- 4. 回滚 crm_material_tag 与 crm_material.latest_revision_id
-- ============================================================================
DROP TABLE IF EXISTS `crm_material_tag`;

ALTER TABLE `crm_material`
  DROP FOREIGN KEY `fk_material_latest_revision`,
  DROP INDEX `idx_material_latest_revision`,
  DROP COLUMN `latest_revision_id`;

-- ============================================================================
-- 3. 回滚 crm_wecom_contact.is_unlinked
-- ============================================================================
ALTER TABLE `crm_wecom_contact`
  DROP INDEX `idx_wecom_contact_unlinked`,
  DROP COLUMN `is_unlinked`;

-- ============================================================================
-- 2. 回滚 crm_youzan_order_latest 投影表
-- ============================================================================
DROP TABLE IF EXISTS `crm_youzan_order_latest`;

-- ============================================================================
-- 1. 回滚 crm_appointment 里程碑时间戳与索引
-- ============================================================================
ALTER TABLE `crm_appointment`
  DROP INDEX `idx_appointment_service_done_at`,
  DROP INDEX `idx_appointment_checked_in_at`,
  DROP INDEX `idx_appointment_confirmed_at`,
  DROP COLUMN `service_done_at`,
  DROP COLUMN `checked_in_at`,
  DROP COLUMN `confirmed_at`;
