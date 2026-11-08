-- P0-1b 客户域：客户主档/身份/标签/归属历史/合并审批/积分账本/跟进/任务/原23字段扩展。
-- 表结构逐字复制自 docs/07_可执行参考/schema.mysql8.4.reference.sql（行 152-310、1085-1104）。
-- 手机号只允许 mobile_cipher(AES-256-GCM) + mobile_hash(HMAC 盲索引)，无明文列；金额一律整数分。

CREATE TABLE IF NOT EXISTS `crm_customer` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '当前专属管家用户ID',
  `team_id` BIGINT UNSIGNED NULL COMMENT '所属团队',
  `customer_no` VARCHAR(48) NOT NULL COMMENT '公开客户编号',
  `display_name` VARCHAR(96) NULL COMMENT '客户姓名/称呼',
  `nickname` VARCHAR(128) NULL COMMENT '客户昵称',
  `remark_name` VARCHAR(128) NULL COMMENT '备注名',
  `mobile_cipher` VARCHAR(512) NULL COMMENT '加密手机号',
  `mobile_hash` VARCHAR(64) NULL COMMENT '去重专用不可逆哈希',
  `city_code` VARCHAR(64) NULL COMMENT '城市字典',
  `source_code` VARCHAR(64) NULL COMMENT '来源字典',
  `channel_code` VARCHAR(64) NULL COMMENT '渠道字典',
  `customer_status` VARCHAR(64) NULL COMMENT '客户状态',
  `tier_code` VARCHAR(16) NULL COMMENT 'S/A/B/C',
  `intent_level` VARCHAR(16) NULL COMMENT '意向级别',
  `spending_level` VARCHAR(16) NULL COMMENT '消费能力',
  `birthday` DATE NULL COMMENT '生日',
  `points_cached` INT NOT NULL DEFAULT 0 COMMENT '积分缓存，非最终账本',
  `attention_projects` VARCHAR(512) NULL COMMENT '关注项目',
  `blocker_note` TEXT NULL COMMENT '卡点情况',
  `remark` TEXT NULL COMMENT '客户备注',
  `first_added_at` DATETIME(3) NULL COMMENT '客户首次添加时间',
  `first_order_at` DATETIME(3) NULL COMMENT '首次下单时间',
  `last_followup_at` DATETIME(3) NULL COMMENT '最后跟进时间',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '归档/软删除',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_1` (`tenant_id`,`customer_no`),
  KEY `idx_customer_1` (`tenant_id`,`owner_staff_id`,`customer_status`),
  KEY `idx_customer_2` (`tenant_id`,`mobile_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM客户主档案';

CREATE TABLE IF NOT EXISTS `crm_customer_identity` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `identity_type` VARCHAR(48) NOT NULL COMMENT 'WECOM/PHONE/YOUZAN/LEGACY',
  `source_app` VARCHAR(96) NOT NULL COMMENT 'corp/app/shop标识',
  `external_id` VARCHAR(192) NOT NULL COMMENT '外部身份',
  `verified_at` DATETIME(3) NULL COMMENT '核验时间',
  `status` VARCHAR(32) NOT NULL COMMENT '已关联/待核验',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_identit_1` (`tenant_id`,`identity_type`,`source_app`,`external_id`),
  KEY `idx_customer_identit_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户来源身份映射';

CREATE TABLE IF NOT EXISTS `crm_customer_tag` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `tag_code` VARCHAR(64) NOT NULL COMMENT '标签字典代码',
  `created_by` BIGINT UNSIGNED NULL COMMENT '标记者',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_tag_1` (`tenant_id`,`customer_id`,`tag_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户标签';

CREATE TABLE IF NOT EXISTS `crm_customer_owner_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `old_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '原管家',
  `new_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '新管家',
  `changed_by` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `effective_at` DATETIME(3) NULL COMMENT '实际生效时间',
  `reason` TEXT NULL COMMENT '变更原因',
  `approval_ref` VARCHAR(96) NULL COMMENT '审批单',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_customer_owner_h_1` (`tenant_id`,`customer_id`,`effective_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='归属变更不可覆盖日志';

CREATE TABLE IF NOT EXISTS `crm_merge_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `primary_customer_id` BIGINT UNSIGNED NOT NULL COMMENT '主客户',
  `secondary_customer_id` BIGINT UNSIGNED NOT NULL COMMENT '被合并客户',
  `status` VARCHAR(32) NOT NULL COMMENT 'SUGGESTED/CONFIRMED/REJECTED',
  `match_score` DECIMAL(10,6) NULL COMMENT '相似度0-1',
  `match_evidence` JSON NULL COMMENT '相似证据',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merge_case_1` (`tenant_id`,`primary_customer_id`,`secondary_customer_id`),
  KEY `idx_merge_case_1` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='疑似重复客户合并审批';

CREATE TABLE IF NOT EXISTS `crm_points_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `delta` INT NOT NULL DEFAULT 0 COMMENT '积分正负变动',
  `reason_code` VARCHAR(48) NOT NULL COMMENT '积分调整类型',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(96) NOT NULL COMMENT '来源ID',
  `operator_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_points_ledger_1` (`tenant_id`,`source_type`,`source_ref`),
  KEY `idx_points_ledger_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员积分账本';

CREATE TABLE IF NOT EXISTS `crm_followup` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '管家',
  `channel_code` VARCHAR(64) NOT NULL COMMENT '沟通方式',
  `contact_at` DATETIME(3) NULL COMMENT '沟通时间',
  `summary` TEXT NULL COMMENT '沟通内容',
  `intent_project` VARCHAR(192) NULL COMMENT '意向项目',
  `estimated_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '预计金额',
  `next_followup_at` DATETIME(3) NULL COMMENT '下次跟进',
  `blocker_note` TEXT NULL COMMENT '卡点',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '登记人',
  `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_followup_1` (`tenant_id`,`customer_id`,`contact_at`),
  KEY `idx_followup_2` (`tenant_id`,`owner_staff_id`,`next_followup_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='每日沟通流水';

CREATE TABLE IF NOT EXISTS `crm_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NULL COMMENT '可空客户',
  `assignee_user_id` BIGINT UNSIGNED NOT NULL COMMENT '负责人',
  `task_type` VARCHAR(48) NOT NULL COMMENT '回访/预约/退款',
  `priority` VARCHAR(16) NOT NULL COMMENT 'LOW/MEDIUM/HIGH',
  `status` VARCHAR(32) NOT NULL COMMENT 'OPEN/DONE/CANCELED',
  `title` VARCHAR(255) NOT NULL COMMENT '任务标题',
  `description` TEXT NULL COMMENT '任务描述',
  `due_at` DATETIME(3) NULL COMMENT '截止日期',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(128) NULL COMMENT '来源 ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_task_1` (`tenant_id`,`assignee_user_id`,`due_at`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工与 AI 待办';

CREATE TABLE IF NOT EXISTS `crm_customer_legacy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `legacy_table` VARCHAR(128) NULL COMMENT '原表名称',
  `legacy_row_id` VARCHAR(128) NULL COMMENT '原表记录ID',
  `legacy_submitted_at` DATETIME(3) NULL COMMENT '原提交时间',
  `legacy_first_import_at` DATETIME(3) NULL COMMENT '原首次入库时间',
  `legacy_account` VARCHAR(128) NULL COMMENT '原所属账号',
  `legacy_writer` VARCHAR(128) NULL COMMENT '原填写账号',
  `legacy_new_old` VARCHAR(32) NULL COMMENT '原新客老客',
  `legacy_formulas_json` JSON NULL COMMENT '原公式值及计算来源',
  `legacy_full_json` JSON NULL COMMENT '原23字段原值',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_customer_legacy_1` (`tenant_id`,`legacy_table`,`legacy_row_id`),
  KEY `idx_customer_legacy_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='原23字段/公式/来源数据保留扩展';

-- 客户转交审批单（docs/11 第 7 节：转交 PENDING → APPROVED → EFFECTIVE，不允许直接覆盖 owner）。
-- 参考 schema 未含此表，按 V2 sys_login_session 同款约定新增；base_version 为申请时客户乐观锁快照，
-- 审批时与客户当前 version 不一致即 409 暂停执行并要求复核。
CREATE TABLE IF NOT EXISTS `crm_customer_transfer` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `from_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '原管家(申请快照)',
  `to_owner_id` BIGINT UNSIGNED NOT NULL COMMENT '新管家',
  `from_team_id` BIGINT UNSIGNED NULL COMMENT '原团队(申请快照)',
  `to_team_id` BIGINT UNSIGNED NULL COMMENT '新团队(申请快照)',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/EFFECTIVE/REJECTED/CANCELED',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `reason` TEXT NULL COMMENT '申请原因',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '审批人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审批时间',
  `review_reason` TEXT NULL COMMENT '审批意见/拒绝原因',
  `base_version` BIGINT UNSIGNED NOT NULL COMMENT '申请时客户version快照',
  `effective_at` DATETIME(3) NULL COMMENT '生效时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_customer_transfer_1` (`tenant_id`,`customer_id`,`status`),
  KEY `idx_customer_transfer_2` (`tenant_id`,`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户转交审批单(批准才改归属)';
