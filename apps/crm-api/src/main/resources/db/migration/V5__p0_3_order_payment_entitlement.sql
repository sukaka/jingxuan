-- P0-3 订单/可信支付/权益账本。金额 BIGINT 分；支付、权益与 Outbox 在同一 MySQL 事务。
CREATE TABLE IF NOT EXISTS `crm_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_no` VARCHAR(64) NOT NULL COMMENT 'CRM订单编号',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `sold_by_user_id` BIGINT UNSIGNED NOT NULL COMMENT '成交管家',
  `owner_at_sale_id` BIGINT UNSIGNED NOT NULL COMMENT '销售时客户归属快照',
  `external_order_no` VARCHAR(128) NULL COMMENT '第三方订单号',
  `source_system` VARCHAR(48) NOT NULL COMMENT 'CRM/YOUZAN',
  `external_shop_id` VARCHAR(96) NULL COMMENT '第三方店铺标识',
  `order_type_code` VARCHAR(48) NOT NULL COMMENT 'REGULAR/FLASH/PROMOTION',
  `campaign_id` BIGINT UNSIGNED NULL COMMENT '活动',
  `campaign_batch_id` BIGINT UNSIGNED NULL COMMENT '批次',
  `status` VARCHAR(48) NOT NULL COMMENT 'DRAFT/PENDING_PAYMENT/PAID/PARTIALLY_REDEEMED/FULFILLED/CANCELED',
  `receiver_type` VARCHAR(32) NOT NULL COMMENT 'PLATFORM/INSTITUTION',
  `list_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '原价分',
  `discount_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '优惠分',
  `payable_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '应付分',
  `paid_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '已确认实付分',
  `refunded_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '成功退款分',
  `ordered_at` DATETIME(3) NULL COMMENT '订单日期',
  `paid_at` DATETIME(3) NULL COMMENT '支付完成时间',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_1` (`tenant_id`,`order_no`),
  UNIQUE KEY `uk_order_2` (`tenant_id`,`source_system`,`external_shop_id`,`external_order_no`),
  KEY `idx_order_1` (`tenant_id`,`customer_id`,`status`),
  KEY `idx_order_2` (`tenant_id`,`sold_by_user_id`,`paid_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='CRM订单事实';

CREATE TABLE IF NOT EXISTS `crm_order_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `sku_id` BIGINT UNSIGNED NOT NULL COMMENT '购买的SKU',
  `package_version_id` BIGINT UNSIGNED NOT NULL COMMENT '套餐版本',
  `sku_code_snapshot` VARCHAR(96) NULL COMMENT '当时SKU编码',
  `sku_name_snapshot` VARCHAR(160) NOT NULL COMMENT '当时卡项/规格名',
  `purchase_quantity` INT NOT NULL DEFAULT 0 COMMENT '购买销售份数',
  `list_amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '原价',
  `paid_allocated_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '该行分摊实付；Q02未确认时为0并标记待分摊',
  `entitlement_snapshot` JSON NULL COMMENT '选项目/次数/机构/有效期/分摊快照',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_order_item_1` (`tenant_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售时订单行快照';

CREATE TABLE IF NOT EXISTS `crm_payment_txn` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `payment_channel` VARCHAR(48) NOT NULL COMMENT '平台/银行/线下',
  `receiver_type` VARCHAR(32) NOT NULL COMMENT 'PLATFORM/INSTITUTION',
  `external_trade_no` VARCHAR(128) NULL COMMENT '支付平台交易号',
  `amount_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '支付金额',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/CONFIRMED/FAILED/REVERSED',
  `confirmed_at` DATETIME(3) NULL COMMENT '确认时间',
  `confirmed_by` BIGINT UNSIGNED NULL COMMENT '确认人',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT '支付凭证文件引用',
  `evidence_ref` VARCHAR(192) NULL COMMENT '人工复核凭证引用（非真实网关回执）',
  `verification_method` VARCHAR(32) NOT NULL DEFAULT 'MANUAL_REVIEW' COMMENT 'MANUAL_REVIEW/PROVIDER_CALLBACK',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payment_txn_1` (`tenant_id`,`payment_channel`,`external_trade_no`),
  KEY `idx_payment_txn_1` (`tenant_id`,`order_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='实际支付流水';

CREATE TABLE IF NOT EXISTS `crm_entitlement` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `order_item_id` BIGINT UNSIGNED NOT NULL COMMENT '购买订单行',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '项目',
  `granted_count` INT NOT NULL DEFAULT 0 COMMENT '已发放次数累计',
  `available_count` INT NOT NULL DEFAULT 0 COMMENT '可用次数事务快照',
  `version_no` INT NOT NULL DEFAULT 0 COMMENT '余额乐观锁业务版本',
  `valid_from` DATE NULL COMMENT '起始日期',
  `valid_to` DATE NULL COMMENT '到期日期',
  `status` VARCHAR(32) NOT NULL COMMENT 'PENDING/AVAILABLE/EXPIRED/EXHAUSTED/REVOKED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement_1` (`tenant_id`,`order_item_id`,`project_id`),
  KEY `idx_entitlement_1` (`tenant_id`,`customer_id`,`status`),
  KEY `idx_entitlement_2` (`tenant_id`,`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户子项目权益批次';

CREATE TABLE IF NOT EXISTS `crm_entitlement_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '权益批次',
  `txn_type` VARCHAR(48) NOT NULL COMMENT 'GRANT/REDEEM/REFUND_REVOKE/CORRECTION_IN/CORRECTION_OUT',
  `delta_count` INT NOT NULL DEFAULT 0 COMMENT '次数正负增减',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源类型',
  `source_ref` VARCHAR(128) NOT NULL COMMENT '业务来源标识',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等唯一键',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人',
  `reason` TEXT NULL COMMENT '原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_entitlement_ledg_1` (`tenant_id`,`idempotency_key`),
  UNIQUE KEY `uk_entitlement_ledg_2` (`tenant_id`,`source_type`,`source_ref`,`txn_type`,`entitlement_id`),
  KEY `idx_entitlement_ledg_1` (`tenant_id`,`entitlement_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权益变动不可覆盖账本';

CREATE TABLE IF NOT EXISTS `crm_order_status_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '订单',
  `from_status` VARCHAR(48) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人',
  `trigger_type` VARCHAR(48) NOT NULL COMMENT 'CREATE/SUBMIT/CANCEL/PAYMENT_CONFIRMED',
  `trigger_ref` VARCHAR(128) NULL COMMENT '事件ID',
  `reason` TEXT NULL COMMENT '理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  KEY `idx_order_status_h_1` (`tenant_id`,`order_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态只追加历史';

ALTER TABLE `crm_order` ADD CONSTRAINT `fk_order_customer_id` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_order_item` ADD CONSTRAINT `fk_order_item_order_id` FOREIGN KEY (`order_id`) REFERENCES `crm_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_entitlement` ADD CONSTRAINT `fk_entitlement_order_item_id` FOREIGN KEY (`order_item_id`) REFERENCES `crm_order_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE `crm_entitlement_ledger` ADD CONSTRAINT `fk_entitlement_ledg_entitlement_id` FOREIGN KEY (`entitlement_id`) REFERENCES `crm_entitlement` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;
