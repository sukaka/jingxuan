-- 仅用于开发应急回滚；先备份产品配置与机构资料，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_sku_institution`;
DROP TABLE IF EXISTS `crm_sku`;
DROP TABLE IF EXISTS `crm_package_project`;
DROP TABLE IF EXISTS `crm_package_version`;
DROP TABLE IF EXISTS `crm_package`;
DROP TABLE IF EXISTS `crm_project`;
DROP TABLE IF EXISTS `crm_campaign_batch`;
DROP TABLE IF EXISTS `crm_campaign`;
DROP TABLE IF EXISTS `crm_partner_channel`;
DROP TABLE IF EXISTS `crm_institution`;
