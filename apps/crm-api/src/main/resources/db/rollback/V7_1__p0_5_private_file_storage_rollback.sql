-- 仅用于开发应急回滚；先备份文件元数据与机构文档，停止上传、扫描和引用写入。
ALTER TABLE `crm_institution_contract` DROP FOREIGN KEY `fk_institution_contract_file`;
ALTER TABLE `crm_institution_qualification` DROP FOREIGN KEY `fk_institution_qualification_file`;
ALTER TABLE `crm_profile_change_request` DROP FOREIGN KEY `fk_profile_change_evidence_file`;
DROP TABLE IF EXISTS `crm_file_object`;
