-- 仅用于开发应急回滚；先备份机构、合同、资质与审批历史，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_profile_change_step`;
DROP TABLE IF EXISTS `crm_profile_change_request`;
DROP TABLE IF EXISTS `crm_institution_qualification`;
DROP TABLE IF EXISTS `crm_institution_contract`;
