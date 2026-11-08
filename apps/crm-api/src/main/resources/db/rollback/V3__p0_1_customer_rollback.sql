-- 仅用于开发应急回滚；先执行 V3.1 回滚并备份客户数据，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_customer_legacy`;
DROP TABLE IF EXISTS `crm_followup`;
DROP TABLE IF EXISTS `crm_task`;
DROP TABLE IF EXISTS `crm_points_ledger`;
DROP TABLE IF EXISTS `crm_merge_case`;
DROP TABLE IF EXISTS `crm_customer_owner_history`;
DROP TABLE IF EXISTS `crm_customer_transfer`;
DROP TABLE IF EXISTS `crm_customer_tag`;
DROP TABLE IF EXISTS `crm_customer_identity`;
DROP TABLE IF EXISTS `crm_customer`;
