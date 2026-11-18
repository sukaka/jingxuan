-- 客户自动分级：规则版本、结构化等级/指标配置、不可覆盖快照与等级历史、降级人工复核。
-- 所有时间戳按 UTC 存储；滚动窗口由规则 zone_id 明确解释，金额均为整数分。

ALTER TABLE `crm_customer`
  ADD COLUMN `tier_locked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '人工锁定后禁止自动升降级' AFTER `tier_code`,
  ADD COLUMN `tier_lock_reason` VARCHAR(1000) NULL COMMENT '人工等级锁定/调整理由' AFTER `tier_locked`;

CREATE TABLE `crm_customer_tier_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '规则版本主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `rule_code` VARCHAR(64) NOT NULL COMMENT '规则组代码',
  `version_no` INT UNSIGNED NOT NULL COMMENT '规则组内版本号',
  `rule_name` VARCHAR(128) NOT NULL COMMENT '规则版本名称',
  `zone_id` VARCHAR(64) NOT NULL COMMENT '滚动窗口明确时区，如 Asia/Shanghai',
  `status` VARCHAR(16) NOT NULL COMMENT 'DRAFT/ACTIVE/RETIRED',
  `metric_config_json` JSON NOT NULL COMMENT '经应用严格校验的指标方向、权重与阈值，不含表达式',
  `active_tenant_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'ACTIVE' THEN `tenant_id` ELSE NULL END) STORED COMMENT '每租户至多一个 ACTIVE 版本',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `activated_at` DATETIME(3) NULL COMMENT '激活时间UTC',
  `retired_at` DATETIME(3) NULL COMMENT '退役时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tier_rule_version` (`tenant_id`,`rule_code`,`version_no`),
  UNIQUE KEY `uk_customer_tier_rule_active` (`active_tenant_id`),
  KEY `idx_customer_tier_rule_status` (`tenant_id`,`status`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户分级规则版本，已激活配置冻结';

CREATE TABLE `crm_customer_tier_level` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '等级项主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '规则版本',
  `tier_code` VARCHAR(16) NOT NULL COMMENT 'S/A/B/C/D',
  `tier_name` VARCHAR(64) NOT NULL COMMENT '等级显示名称',
  `sort_no` INT UNSIGNED NOT NULL COMMENT '等级顺序，数值越小等级越高',
  `min_score` DECIMAL(10,2) NOT NULL COMMENT '进入该等级所需最低加权分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tier_level_code` (`tenant_id`,`rule_id`,`tier_code`),
  UNIQUE KEY `uk_customer_tier_level_sort` (`tenant_id`,`rule_id`,`sort_no`),
  CONSTRAINT `fk_customer_tier_level_rule` FOREIGN KEY (`rule_id`) REFERENCES `crm_customer_tier_rule` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='分级规则 S/A/B/C/D 等级项';

CREATE TABLE `crm_customer_tier_metric_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '指标快照主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '计算所用规则版本',
  `recalculation_key` VARCHAR(192) NOT NULL COMMENT '每日/事件/API 重算幂等键',
  `source_trigger` VARCHAR(64) NOT NULL COMMENT 'DAILY/ORDER_PAID/REFUND_SUCCEEDED/FOLLOWUP/MANUAL',
  `as_of` DATETIME(3) NOT NULL COMMENT '滚动窗口右端点（不含）UTC',
  `window_start_inclusive` DATETIME(3) NOT NULL COMMENT 'asOf 在规则时区减 12 个月（含）UTC',
  `window_end_exclusive` DATETIME(3) NOT NULL COMMENT 'asOf（不含）UTC',
  `zone_id` VARCHAR(64) NOT NULL COMMENT '本次窗口时区快照',
  `payment_amount_fen` BIGINT NOT NULL COMMENT 'CONFIRMED 支付流水金额合计，独立不冲减',
  `paid_amount_fen` BIGINT NOT NULL COMMENT '有效订单 paid_fen 合计，独立不冲减',
  `refund_amount_fen` BIGINT NOT NULL COMMENT 'SUCCEEDED 退款支付金额合计，独立不冲减',
  `order_count` BIGINT NOT NULL COMMENT '有效订单下单频次，排除 DRAFT/CANCELED',
  `manual_followup_count` BIGINT NOT NULL COMMENT '窗口内人工跟进数',
  `archived_message_count` BIGINT NOT NULL COMMENT '窗口内合法企微归档消息数',
  `communication_count` BIGINT NULL COMMENT '人工跟进+合法归档；存在采集缺口时为 NULL',
  `communication_complete` TINYINT(1) NOT NULL COMMENT '归档覆盖是否完整，缺采时不得断言沟通为 0',
  `score` DECIMAL(10,2) NULL COMMENT '加权分；关键指标未知时为 NULL',
  `suggested_tier_code` VARCHAR(16) NULL COMMENT '建议等级；指标不足时为 NULL',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tier_snapshot_key` (`tenant_id`,`recalculation_key`),
  KEY `idx_customer_tier_snapshot_customer` (`tenant_id`,`customer_id`,`id`),
  KEY `idx_customer_tier_snapshot_rule` (`tenant_id`,`rule_id`,`id`),
  CONSTRAINT `fk_customer_tier_snapshot_rule` FOREIGN KEY (`rule_id`) REFERENCES `crm_customer_tier_rule` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户滚动 12 个月指标不可覆盖快照';

CREATE TABLE `crm_customer_tier_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '等级历史主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `rule_id` BIGINT UNSIGNED NULL COMMENT '自动计算规则版本，人工调整为空',
  `snapshot_id` BIGINT UNSIGNED NULL COMMENT '依据指标快照，人工调整为空',
  `review_id` BIGINT UNSIGNED NULL COMMENT '降级复核单，非降级审批为空',
  `from_tier_code` VARCHAR(16) NULL COMMENT '变更前等级',
  `to_tier_code` VARCHAR(16) NOT NULL COMMENT '变更后等级',
  `change_type` VARCHAR(32) NOT NULL COMMENT 'AUTO_UPGRADE/DOWNGRADE_APPROVED/MANUAL_ADJUST',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人，系统重算为空',
  `reason` VARCHAR(1000) NULL COMMENT '变更或审批理由',
  `effective_at` DATETIME(3) NOT NULL COMMENT '实际生效时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_customer_tier_history_customer` (`tenant_id`,`customer_id`,`id`),
  KEY `idx_customer_tier_history_rule` (`tenant_id`,`rule_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户等级生效事实，只追加不覆盖';

CREATE TABLE `crm_customer_tier_downgrade_review` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '降级复核主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '申请时规则版本',
  `snapshot_id` BIGINT UNSIGNED NOT NULL COMMENT '申请时指标快照',
  `from_tier_code` VARCHAR(16) NOT NULL COMMENT '申请时当前等级',
  `to_tier_code` VARCHAR(16) NOT NULL COMMENT '拟降级等级',
  `base_customer_version` BIGINT UNSIGNED NOT NULL COMMENT '申请时客户乐观锁版本',
  `status` VARCHAR(24) NOT NULL COMMENT 'PENDING_REVIEW/APPROVED/REJECTED/STALE',
  `requested_by` BIGINT UNSIGNED NULL COMMENT '人工触发人，系统生成为空',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '复核人',
  `review_reason` VARCHAR(1000) NULL COMMENT '批准或驳回理由',
  `reviewed_at` DATETIME(3) NULL COMMENT '复核时间UTC',
  `effective_at` DATETIME(3) NULL COMMENT '批准生效时间UTC，驳回为空',
  `pending_customer_id` BIGINT UNSIGNED GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING_REVIEW' THEN `customer_id` ELSE NULL END) STORED COMMENT '每客户至多一个待复核单',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '复核单乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tier_review_pending` (`tenant_id`,`pending_customer_id`),
  KEY `idx_customer_tier_review_status` (`tenant_id`,`status`,`id`),
  KEY `idx_customer_tier_review_customer` (`tenant_id`,`customer_id`,`id`),
  CONSTRAINT `fk_customer_tier_review_rule` FOREIGN KEY (`rule_id`) REFERENCES `crm_customer_tier_rule` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_customer_tier_review_snapshot` FOREIGN KEY (`snapshot_id`) REFERENCES `crm_customer_tier_metric_snapshot` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户降级复核，批准前不得改等级';
