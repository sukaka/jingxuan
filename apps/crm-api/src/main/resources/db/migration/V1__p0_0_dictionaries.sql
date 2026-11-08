-- P0-0 字典样表（sys_dictionary_type / sys_dictionary_item）。
-- 来源：docs/07_可执行参考/schema.mysql8.4.reference.sql 字典段，按 Flyway 基线拆分。
-- 字典生命周期 DRAFT → ACTIVE → INACTIVE → ARCHIVED；有历史引用的值不物理删除。

CREATE TABLE IF NOT EXISTS `sys_dictionary_type` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `dict_code` VARCHAR(64) NOT NULL COMMENT '字典编码',
  `dict_name` VARCHAR(96) NOT NULL COMMENT '字典名称',
  `status` VARCHAR(32) NOT NULL COMMENT '状态',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dictionary_type_1` (`tenant_id`,`dict_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可维护字典类型';

CREATE TABLE IF NOT EXISTS `sys_dictionary_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `dict_type_id` BIGINT UNSIGNED NOT NULL COMMENT '字典类型',
  `option_code` VARCHAR(96) NOT NULL COMMENT '不可变业务代码',
  `option_label` VARCHAR(128) NOT NULL COMMENT '中文展示名称',
  `sort_no` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE/ARCHIVED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dictionary_item_1` (`tenant_id`,`dict_type_id`,`option_code`),
  KEY `idx_dictionary_item_1` (`tenant_id`,`dict_type_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典选项';
