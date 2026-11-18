-- 动态分组与正规关注项目。分组成员实时计算，只有显式操作批次保存不可变成员快照。
CREATE TABLE `crm_customer_attention_project` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关系主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户唯一ID',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '正规产品子项目ID',
  `created_by` BIGINT UNSIGNED NULL COMMENT '关系维护人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_attention_project` (`tenant_id`,`customer_id`,`project_id`),
  KEY `idx_customer_attention_project_filter` (`tenant_id`,`project_id`,`customer_id`),
  CONSTRAINT `fk_customer_attention_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_customer_attention_project` FOREIGN KEY (`project_id`) REFERENCES `crm_project` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户当前关注子项目关系；旧attention_projects文本不自动匹配';

CREATE TABLE `crm_customer_segment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '分组定义主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `name` VARCHAR(128) NOT NULL COMMENT '分组名称',
  `filter_json` JSON NOT NULL COMMENT '城市/当前等级/关注/已购/净核销组合条件',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除，保留操作批次引用',
  `active_name` VARCHAR(128) GENERATED ALWAYS AS (CASE WHEN `deleted`=0 THEN `name` ELSE NULL END) STORED COMMENT '未删除分组名称唯一',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_segment_active_name` (`tenant_id`,`active_name`),
  KEY `idx_customer_segment_active` (`tenant_id`,`deleted`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户动态分组定义，不保存普通预览成员';

CREATE TABLE `crm_customer_segment_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '操作批次主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `segment_id` BIGINT UNSIGNED NOT NULL COMMENT '动态分组定义',
  `batch_no` VARCHAR(64) NOT NULL COMMENT '操作批次编号',
  `operation_type` VARCHAR(32) NOT NULL COMMENT '后续操作类型，仅记录批次不自动执行',
  `filter_json` JSON NOT NULL COMMENT '创建批次时的分组条件快照',
  `member_count` INT UNSIGNED NOT NULL COMMENT '当前数据范围内快照成员数',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '批次创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_segment_batch_no` (`tenant_id`,`batch_no`),
  KEY `idx_customer_segment_batch_segment` (`tenant_id`,`segment_id`,`id`),
  CONSTRAINT `fk_customer_segment_batch_segment` FOREIGN KEY (`segment_id`) REFERENCES `crm_customer_segment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='动态分组操作批次不可变快照，仅显式批次生成';

CREATE TABLE `crm_customer_segment_batch_member` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '快照成员主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '操作批次',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '批次创建时可见客户ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_segment_batch_member` (`tenant_id`,`batch_id`,`customer_id`),
  KEY `idx_customer_segment_batch_member_customer` (`tenant_id`,`customer_id`,`batch_id`),
  CONSTRAINT `fk_customer_segment_member_batch` FOREIGN KEY (`batch_id`) REFERENCES `crm_customer_segment_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_customer_segment_member_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作批次客户成员不可变快照';

CREATE TRIGGER `trg_customer_segment_batch_no_update` BEFORE UPDATE ON `crm_customer_segment_batch`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer segment operation batch is immutable';
CREATE TRIGGER `trg_customer_segment_batch_no_delete` BEFORE DELETE ON `crm_customer_segment_batch`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer segment operation batch is append only';
CREATE TRIGGER `trg_customer_segment_member_no_update` BEFORE UPDATE ON `crm_customer_segment_batch_member`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer segment batch member is immutable';
CREATE TRIGGER `trg_customer_segment_member_no_delete` BEFORE DELETE ON `crm_customer_segment_batch_member`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Customer segment batch member is append only';
