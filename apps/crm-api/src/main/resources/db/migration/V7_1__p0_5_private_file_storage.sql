-- P0-5 私有文件元数据、扫描状态与机构文档外键。对象内容只在私有桶，数据库不保存明文文件。
CREATE TABLE IF NOT EXISTS `crm_file_object` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `object_key` VARCHAR(255) NOT NULL COMMENT '私有桶唯一对象Key',
  `original_name` VARCHAR(255) NULL COMMENT '原文件名',
  `mime_type` VARCHAR(128) NOT NULL COMMENT '实际校验后的MIME',
  `size_bytes` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际字节数',
  `sha256` CHAR(64) NOT NULL COMMENT '真实内容SHA-256',
  `classification` VARCHAR(48) NOT NULL COMMENT 'CONTRACT/QUALIFICATION/CHANGE_EVIDENCE/PAYMENT/REDEMPTION/MATERIAL',
  `institution_id` BIGINT UNSIGNED NULL COMMENT '机构数据范围；平台文件可为空',
  `scan_status` VARCHAR(32) NOT NULL COMMENT 'PENDING/SCANNING/CLEAN/BLOCKED/ERROR',
  `uploaded_by` BIGINT UNSIGNED NOT NULL COMMENT '上传人',
  `etag` VARCHAR(128) NULL COMMENT '对象存储ETag',
  `last_error` VARCHAR(128) NULL COMMENT '脱敏失败类别，不记录内容或凭证',
  `expires_at` DATETIME(3) NULL COMMENT '保留截止时间，空表示按长期策略',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '元数据乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_file_object_1` (`tenant_id`,`object_key`),
  KEY `idx_file_object_1` (`tenant_id`,`sha256`),
  KEY `idx_file_object_2` (`tenant_id`,`institution_id`,`scan_status`),
  CONSTRAINT `fk_file_institution` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_file_uploaded_by` FOREIGN KEY (`uploaded_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='私有文件元数据与扫描状态，历史对象不可覆盖';

ALTER TABLE `crm_institution_contract`
  ADD CONSTRAINT `fk_institution_contract_file` FOREIGN KEY (`file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_institution_qualification`
  ADD CONSTRAINT `fk_institution_qualification_file` FOREIGN KEY (`file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_profile_change_request`
  ADD CONSTRAINT `fk_profile_change_evidence_file` FOREIGN KEY (`evidence_file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
