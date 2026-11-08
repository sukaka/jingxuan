-- P0-2 产品/套餐/版本/SKU/适用机构。机构账号映射 crm_institution_user 已在 V2 建立。
CREATE TABLE IF NOT EXISTS `crm_institution` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `institution_no` VARCHAR(48) NOT NULL COMMENT '机构编码',
  `full_name` VARCHAR(160) NOT NULL COMMENT '机构全称',
  `short_name` VARCHAR(96) NULL COMMENT '简称',
  `city_code` VARCHAR(64) NULL COMMENT '城市',
  `business_type_code` VARCHAR(64) NULL COMMENT '医美/口腔/私密/综合',
  `ownership_code` VARCHAR(64) NULL COMMENT '公立/民营',
  `grade_code` VARCHAR(16) NULL COMMENT 'S/A/B/C',
  `address` VARCHAR(255) NULL COMMENT '地址',
  `contact_name` VARCHAR(128) NULL COMMENT '负责人',
  `contact_phone_cipher` VARCHAR(512) NULL COMMENT '加密联系电话',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/ACTIVE/SUSPENDED',
  `settlement_type` VARCHAR(48) NOT NULL COMMENT 'MONTHLY/PER_REDEMPTION',
  `online_rate` DECIMAL(10,6) NULL COMMENT '线上佣金率',
  `offline_rate` DECIMAL(10,6) NULL COMMENT '线下佣金率',
  `platform_rate` DECIMAL(10,6) NULL COMMENT '点评等平台佣金率',
  `other_rate_rule` TEXT NULL COMMENT '其他佣金',
  `charges_fee` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否收费',
  `deposit_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '保证金分',
  `guarantee_roi` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否保投产',
  `roi_terms` TEXT NULL COMMENT '保投产约定',
  `settlement_name` VARCHAR(160) NULL COMMENT '机构结款名称',
  `bank_name_cipher` VARCHAR(512) NULL COMMENT '加密开户行',
  `bank_account_cipher` VARCHAR(512) NULL COMMENT '加密银行账户',
  `tax_id_cipher` VARCHAR(512) NULL COMMENT '加密税号',
  `introduction` TEXT NULL COMMENT '机构简介',
  `archived` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '归档',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_institution_1` (`tenant_id`,`institution_no`),
  KEY `idx_institution_1` (`tenant_id`,`city_code`,`status`), KEY `idx_institution_2` (`tenant_id`,`grade_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='合作机构主档案';

CREATE TABLE IF NOT EXISTS `crm_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `project_code` VARCHAR(64) NOT NULL COMMENT '项目编码',
  `project_name` VARCHAR(160) NOT NULL COMMENT '项目标准名',
  `category_code` VARCHAR(64) NULL COMMENT '项目分类',
  `list_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '参考单价分',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_project_1` (`tenant_id`,`project_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品子项目定义';

CREATE TABLE IF NOT EXISTS `crm_package` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_code` VARCHAR(64) NOT NULL COMMENT '套餐编码',
  `package_name` VARCHAR(160) NOT NULL COMMENT '套餐标准名',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_package_1` (`tenant_id`,`package_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='卡项/套餐主定义';

CREATE TABLE IF NOT EXISTS `crm_package_version` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐',
  `version_no` INT NOT NULL DEFAULT 0 COMMENT '版本号',
  `selection_mode` VARCHAR(32) NOT NULL COMMENT 'FIXED/CHOOSE_N/USE_N',
  `choice_count` INT NOT NULL DEFAULT 0 COMMENT '可选N项/使用N次',
  `selection_rules` TEXT NULL COMMENT '选项规则和重复规则；未经确认不得发权益',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/ACTIVE/ARCHIVED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_package_version_1` (`tenant_id`,`package_id`,`version_no`),
  KEY `idx_package_version_1` (`tenant_id`,`package_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售版本快照模板';

CREATE TABLE IF NOT EXISTS `crm_package_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '子项目',
  `grant_count` INT NOT NULL DEFAULT 0 COMMENT '购买时发放次数或可选上限',
  `selection_weight` INT NOT NULL DEFAULT 0 COMMENT '选择权重或配额',
  `allocation_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '金额分摊基准；未配置阻断结算',
  `option_group` VARCHAR(48) NOT NULL COMMENT '选项组编码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_package_project_1` (`tenant_id`,`package_version_id`,`project_id`,`option_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐版本包含的项目与权益';

CREATE TABLE IF NOT EXISTS `crm_sku` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `sku_code` VARCHAR(96) NOT NULL COMMENT '稳定不可重复SKU',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `spec_name` VARCHAR(128) NOT NULL COMMENT '3次/6次/选3项等',
  `sale_price_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '售价分',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `on_sale_at` DATETIME(3) NULL COMMENT '上架时间',
  `off_sale_at` DATETIME(3) NULL COMMENT '下架时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_sku_1` (`tenant_id`,`sku_code`),
  KEY `idx_sku_1` (`tenant_id`,`package_version_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='具体可售 SKU（规格）';

CREATE TABLE IF NOT EXISTS `crm_sku_institution` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT 'SKU',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '医院',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_sku_institution_1` (`tenant_id`,`sku_id`,`institution_id`),
  KEY `idx_sku_institution_1` (`tenant_id`,`institution_id`,`sku_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU适用机构';

CREATE TABLE IF NOT EXISTS `crm_campaign` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `campaign_code` VARCHAR(64) NOT NULL COMMENT '活动编码',
  `campaign_name` VARCHAR(160) NOT NULL COMMENT '活动名称',
  `campaign_type` VARCHAR(32) NOT NULL COMMENT 'REGULAR/FLASH/PROMOTION',
  `starts_at` DATETIME(3) NULL COMMENT '活动开始', `ends_at` DATETIME(3) NULL COMMENT '活动结束',
  `status` VARCHAR(32) NOT NULL COMMENT '活动状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_campaign_1` (`tenant_id`,`campaign_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动/快闪/大促';

CREATE TABLE IF NOT EXISTS `crm_campaign_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `campaign_id` BIGINT UNSIGNED NOT NULL COMMENT '活动',
  `batch_code` VARCHAR(64) NOT NULL COMMENT '批次编码', `batch_name` VARCHAR(96) NOT NULL COMMENT '批次名称',
  `starts_at` DATETIME(3) NULL COMMENT '开始', `ends_at` DATETIME(3) NULL COMMENT '结束',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_campaign_batch_1` (`tenant_id`,`campaign_id`,`batch_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='活动分波次';

CREATE TABLE IF NOT EXISTS `crm_partner_channel` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `channel_no` VARCHAR(48) NOT NULL COMMENT '渠道编号', `name` VARCHAR(160) NOT NULL COMMENT '达人/渠道名称',
  `platform_code` VARCHAR(64) NULL COMMENT '微博/公众号等', `business_owner_user_id` BIGINT UNSIGNED NULL COMMENT '对接商务',
  `status` VARCHAR(32) NOT NULL COMMENT '合作/暂停/归档', `remarks` TEXT NULL COMMENT '备注',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`), UNIQUE KEY `uk_partner_channel_1` (`tenant_id`,`channel_no`),
  KEY `idx_partner_channel_1` (`tenant_id`,`business_owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='达人/合作渠道';
