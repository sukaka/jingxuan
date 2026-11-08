CREATE TABLE IF NOT EXISTS `crm_refund` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `refund_no` VARCHAR(64) NOT NULL COMMENT '退款编号',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '原订单',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户快照',
  `requested_fen` BIGINT NOT NULL COMMENT '申请金额分',
  `approved_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '批准金额分',
  `succeeded_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '实际成功退款分',
  `reason_code` VARCHAR(64) NOT NULL COMMENT '退款原因字典',
  `reason_detail` VARCHAR(1000) NULL COMMENT '退款说明',
  `status` VARCHAR(32) NOT NULL COMMENT 'REQUESTED/APPROVED/REJECTED/PROCESSING/SUCCEEDED/FAILED',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `approved_by` BIGINT UNSIGNED NULL COMMENT '异人审批人',
  `executed_by` BIGINT UNSIGNED NULL COMMENT '执行人',
  `succeeded_at` DATETIME(3) NULL COMMENT '渠道确认成功时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_no` (`tenant_id`,`refund_no`),
  KEY `idx_refund_order` (`tenant_id`,`order_id`,`status`),
  KEY `idx_refund_customer` (`tenant_id`,`customer_id`,`status`),
  CONSTRAINT `fk_refund_order` FOREIGN KEY (`order_id`) REFERENCES `crm_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款申请主单，审批通过不代表退款成功';

CREATE TABLE IF NOT EXISTS `crm_refund_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '退款主单',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '拟回收权益批次',
  `revoke_count` INT NOT NULL COMMENT '人工确定的回收次数',
  `allocated_fen` BIGINT NOT NULL COMMENT '人工确定的现金分摊分',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_item_entitlement` (`tenant_id`,`refund_id`,`entitlement_id`),
  CONSTRAINT `fk_refund_item_refund` FOREIGN KEY (`refund_id`) REFERENCES `crm_refund` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_refund_item_entitlement` FOREIGN KEY (`entitlement_id`) REFERENCES `crm_entitlement` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款与权益回收人工决策';

CREATE TABLE IF NOT EXISTS `crm_refund_payment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '退款主单',
  `provider` VARCHAR(48) NOT NULL COMMENT '退款来源渠道',
  `external_refund_no` VARCHAR(128) NOT NULL COMMENT '外部退款/人工核验编号',
  `amount_fen` BIGINT NOT NULL COMMENT '实际退款金额分',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
  `verification_method` VARCHAR(32) NOT NULL COMMENT 'MANUAL_REVIEW/PROVIDER_CALLBACK',
  `evidence_ref` VARCHAR(192) NULL COMMENT '人工证据或适配器引用',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT 'CLEAN 退款凭证文件',
  `succeeded_at` DATETIME(3) NULL COMMENT '成功回执时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_refund_payment_external` (`tenant_id`,`provider`,`external_refund_no`),
  KEY `idx_refund_payment_refund` (`tenant_id`,`refund_id`,`status`),
  CONSTRAINT `fk_refund_payment_refund` FOREIGN KEY (`refund_id`) REFERENCES `crm_refund` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款渠道结果，只有成功才影响资产';

CREATE TABLE IF NOT EXISTS `crm_refund_status_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `refund_id` BIGINT UNSIGNED NOT NULL COMMENT '退款主单',
  `from_status` VARCHAR(32) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(32) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人，回调为空',
  `reason` VARCHAR(1000) NULL COMMENT '理由或证据引用',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_refund_history` (`tenant_id`,`refund_id`,`id`),
  CONSTRAINT `fk_refund_history_refund` FOREIGN KEY (`refund_id`) REFERENCES `crm_refund` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款状态只追加历史';

CREATE TABLE IF NOT EXISTS `crm_settlement_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '结算机构',
  `settlement_type` VARCHAR(32) NOT NULL COMMENT 'MONTHLY/PER_REDEMPTION',
  `period_key` VARCHAR(64) NOT NULL COMMENT 'YYYY-MM或REDEMPTION:{id}',
  `status` VARCHAR(32) NOT NULL COMMENT 'DRAFT/CONFIRMED/PARTIALLY_PAID/PAID',
  `amount_status` VARCHAR(32) NOT NULL COMMENT 'PENDING_VERIFICATION/VERIFIED',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '人工核准核销基数分，未核准为0',
  `commission_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '人工核准佣金分，未核准为0',
  `net_payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '人工核准净额分，未核准为0',
  `paid_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '已登记付款分',
  `rule_version` VARCHAR(96) NULL COMMENT '线下核准规则版本',
  `verification_evidence` VARCHAR(192) NULL COMMENT '计费核准证据',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_batch_period` (`tenant_id`,`institution_id`,`settlement_type`,`period_key`),
  KEY `idx_settlement_batch_status` (`tenant_id`,`institution_id`,`status`),
  CONSTRAINT `fk_settlement_batch_institution` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构月结或次卡次结批次';

CREATE TABLE IF NOT EXISTS `crm_settlement_line` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `settlement_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '结算批次',
  `redemption_id` BIGINT UNSIGNED NOT NULL COMMENT '正式核销，只允许入批一次',
  `amount_status` VARCHAR(32) NOT NULL DEFAULT 'PENDING_VERIFICATION' COMMENT '金额待核实/已核准',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '核准基数分，未核准为0',
  `commission_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '核准佣金分，未核准为0',
  `payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '核准净额分，未核准为0',
  `rate_snapshot` JSON NOT NULL COMMENT '机构当时费率与版本快照，不自动应用',
  `pending_reason` VARCHAR(500) NULL COMMENT '待核实原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_line_redemption` (`tenant_id`,`redemption_id`),
  UNIQUE KEY `uk_settlement_line_batch` (`tenant_id`,`settlement_batch_id`,`redemption_id`),
  CONSTRAINT `fk_settlement_line_batch` FOREIGN KEY (`settlement_batch_id`) REFERENCES `crm_settlement_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_settlement_line_redemption` FOREIGN KEY (`redemption_id`) REFERENCES `crm_redemption` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构结算核销明细与规则快照';

CREATE TABLE IF NOT EXISTS `crm_settlement_payment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `settlement_batch_id` BIGINT UNSIGNED NOT NULL COMMENT '结算批次',
  `amount_fen` BIGINT NOT NULL COMMENT '登记付款金额分',
  `bank_trade_no` VARCHAR(128) NOT NULL COMMENT '真实银行/线下流水号',
  `paid_at` DATETIME(3) NOT NULL COMMENT '实际付款时间',
  `voucher_file_id` BIGINT UNSIGNED NOT NULL COMMENT 'CLEAN 付款凭证文件',
  `evidence_ref` VARCHAR(192) NOT NULL COMMENT '人工核验引用',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '登记人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settlement_payment_trade` (`tenant_id`,`bank_trade_no`),
  KEY `idx_settlement_payment_batch` (`tenant_id`,`settlement_batch_id`),
  CONSTRAINT `fk_settlement_payment_batch` FOREIGN KEY (`settlement_batch_id`) REFERENCES `crm_settlement_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构结算付款凭证，只追加';
