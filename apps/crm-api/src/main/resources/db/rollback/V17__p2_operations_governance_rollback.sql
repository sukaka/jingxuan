-- 仅用于开发应急回滚；先备份P2新增数据并停止相关写入，禁止在生产直接执行。
-- 不回滚或重建V4活动/渠道、V5订单及其他版本的历史事实，仅移除V17增量。

DROP TRIGGER IF EXISTS `trg_order_sale_snapshot_no_update`;
ALTER TABLE `crm_order`
  DROP FOREIGN KEY `fk_order_channel`,
  DROP FOREIGN KEY `fk_order_business_owner`;
ALTER TABLE `crm_order`
  DROP KEY `idx_order_channel_sale`,
  DROP KEY `idx_order_business_sale`;
ALTER TABLE `crm_order`
  DROP COLUMN `campaign_code_snapshot`,
  DROP COLUMN `campaign_name_snapshot`,
  DROP COLUMN `batch_code_snapshot`,
  DROP COLUMN `batch_name_snapshot`,
  DROP COLUMN `channel_id`,
  DROP COLUMN `business_owner_at_sale_id`;

ALTER TABLE `crm_campaign_batch`
  DROP KEY `idx_campaign_batch_status`,
  DROP COLUMN `status`;

DROP TRIGGER IF EXISTS `trg_material_revision_no_update`;
DROP TRIGGER IF EXISTS `trg_material_revision_no_delete`;
ALTER TABLE `crm_material`
  DROP FOREIGN KEY `fk_material_current_revision`;
DROP TABLE IF EXISTS `crm_material_revision`;
DROP TABLE IF EXISTS `crm_material`;

DROP TRIGGER IF EXISTS `trg_export_access_no_update`;
DROP TRIGGER IF EXISTS `trg_export_access_no_delete`;
DROP TABLE IF EXISTS `crm_export_access_log`;
DROP TABLE IF EXISTS `crm_export_job`;

DROP TRIGGER IF EXISTS `trg_risk_history_no_update`;
DROP TRIGGER IF EXISTS `trg_risk_history_no_delete`;
DROP TABLE IF EXISTS `crm_risk_case_history`;
DROP TABLE IF EXISTS `crm_risk_case`;

DROP TABLE IF EXISTS `crm_performance_snapshot`;
DROP TABLE IF EXISTS `crm_daily_analytics`;
