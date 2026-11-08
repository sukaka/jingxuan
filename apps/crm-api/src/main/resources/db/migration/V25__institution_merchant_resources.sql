CREATE TABLE IF NOT EXISTS `crm_institution_merchant_resource` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `resource_no` CHAR(26) NOT NULL COMMENT '资料唯一编号',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '所属机构',
  `resource_type` VARCHAR(32) NOT NULL COMMENT 'DOCTOR医生/EQUIPMENT仪器/CASE案例',
  `title` VARCHAR(120) NOT NULL COMMENT '医生姓名、仪器名称或案例标题',
  `subtitle` VARCHAR(200) NULL COMMENT '职称、型号或关联项目说明',
  `description` VARCHAR(2000) NULL COMMENT '擅长方向、仪器说明或案例说明',
  `file_id` BIGINT UNSIGNED NULL COMMENT '可选附件引用，必须为同机构已扫描文件',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED' COMMENT 'PUBLISHED展示/OFFLINE下架',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `updated_by` BIGINT UNSIGNED NULL COMMENT '最近修改人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_institution_merchant_resource_no` (`tenant_id`,`resource_no`),
  KEY `idx_institution_merchant_resource_page` (`tenant_id`,`institution_id`,`status`,`resource_type`,`id`),
  KEY `idx_institution_merchant_resource_file` (`tenant_id`,`file_id`),
  CONSTRAINT `fk_institution_merchant_resource_institution` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_institution_merchant_resource_file` FOREIGN KEY (`file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_institution_merchant_resource_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_institution_merchant_resource_updater` FOREIGN KEY (`updated_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `chk_institution_merchant_resource_type` CHECK (`resource_type` IN ('DOCTOR','EQUIPMENT','CASE')),
  CONSTRAINT `chk_institution_merchant_resource_status` CHECK (`status` IN ('PUBLISHED','OFFLINE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构医生、仪器与案例资料；下架保留历史';

ALTER TABLE `crm_file_object`
  MODIFY COLUMN `classification` VARCHAR(48) NOT NULL COMMENT 'CONTRACT/QUALIFICATION/CHANGE_EVIDENCE/PAYMENT/REDEMPTION/MATERIAL/MERCHANT_RESOURCE';
