-- 仅用于开发应急回滚；先停止资料维护并备份本表及审计记录，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_institution_merchant_resource`;

ALTER TABLE `crm_file_object`
  MODIFY COLUMN `classification` VARCHAR(48) NOT NULL COMMENT 'CONTRACT/QUALIFICATION/CHANGE_EVIDENCE/PAYMENT/REDEMPTION/MATERIAL';
