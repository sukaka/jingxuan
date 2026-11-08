-- P1-1 有赞 Mock/适配器原始事件、映射、补偿同步与异常队列。供应商原始内容仅脱敏JSON和AES密文保存。
CREATE TABLE IF NOT EXISTS `crm_youzan_event` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID，开发虚构店铺',
  `event_id` VARCHAR(128) NOT NULL COMMENT '供应商事件唯一ID',
  `event_type` VARCHAR(48) NOT NULL COMMENT 'ORDER_PAID/ORDER_PENDING/ORDER_CLOSED等',
  `external_order_no` VARCHAR(128) NOT NULL COMMENT '外部订单号',
  `payload_masked_json` LONGTEXT NOT NULL COMMENT '脱敏后的原始JSON',
  `payload_cipher` LONGTEXT NOT NULL COMMENT 'AES加密的完整原始JSON引用',
  `signature_valid` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '仅验签通过事件可入库',
  `source_system` VARCHAR(16) NOT NULL COMMENT 'WEBHOOK/PULL',
  `process_status` VARCHAR(32) NOT NULL COMMENT 'RECEIVED/PROCESSED/MAPPING_FAILED/CUSTOMER_UNRESOLVED/PAYMENT_MISMATCH/RETRY/DEAD_LETTER',
  `process_attempts` INT NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏错误信息',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次重试时间，UTC',
  `received_at` DATETIME(3) NOT NULL COMMENT '接收时间，UTC',
  `processed_at` DATETIME(3) NULL COMMENT '处理完成时间，UTC',
  `order_id` BIGINT UNSIGNED NULL COMMENT '内部订单ID',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_event_1` (`tenant_id`,`shop_id`,`event_id`),
  KEY `idx_youzan_event_1` (`tenant_id`,`shop_id`,`external_order_no`),
  KEY `idx_youzan_event_2` (`tenant_id`,`process_status`,`next_retry_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞验签后原始事件与处理状态';

CREATE TABLE IF NOT EXISTS `crm_youzan_order_map` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID',
  `external_order_no` VARCHAR(128) NOT NULL COMMENT '外部订单号',
  `crm_order_id` BIGINT UNSIGNED NOT NULL COMMENT '内部订单ID',
  `last_external_updated_at` DATETIME(3) NOT NULL COMMENT '已处理的外部更新时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_order_map_1` (`tenant_id`,`shop_id`,`external_order_no`),
  UNIQUE KEY `uk_youzan_order_map_2` (`tenant_id`,`crm_order_id`),
  KEY `idx_youzan_order_map_1` (`tenant_id`,`shop_id`,`last_external_updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞外部订单与内部订单一对一映射';

CREATE TABLE IF NOT EXISTS `crm_youzan_sku_map` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID',
  `external_sku_id` VARCHAR(128) NOT NULL COMMENT '外部商品SKU ID',
  `crm_sku_id` BIGINT UNSIGNED NOT NULL COMMENT 'CRM标准SKU ID',
  `status` VARCHAR(32) NOT NULL COMMENT 'ACTIVE/INACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_sku_map_1` (`tenant_id`,`shop_id`,`external_sku_id`),
  KEY `idx_youzan_sku_map_1` (`tenant_id`,`crm_sku_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞商品SKU与CRM标准SKU映射';

CREATE TABLE IF NOT EXISTS `crm_youzan_customer_link` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID',
  `external_customer_id` VARCHAR(128) NOT NULL COMMENT '外部客户标识，不是手机号或昵称',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '人工或强证据确认的CRM客户ID',
  `link_type` VARCHAR(32) NOT NULL COMMENT 'STRONG/MANUAL',
  `confidence` VARCHAR(16) NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
  `reason` VARCHAR(1000) NULL COMMENT '人工匹配证据与理由',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '确认人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_customer_link_1` (`tenant_id`,`shop_id`,`external_customer_id`),
  KEY `idx_youzan_customer_link_1` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞外部客户与CRM客户人工/强证据映射';

CREATE TABLE IF NOT EXISTS `crm_youzan_sync_issue` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `event_id` BIGINT UNSIGNED NOT NULL COMMENT '原始事件ID',
  `issue_type` VARCHAR(48) NOT NULL COMMENT 'MAPPING_FAILED/CUSTOMER_UNRESOLVED/PAYMENT_MISMATCH/RETRY/DEAD_LETTER',
  `status` VARCHAR(32) NOT NULL COMMENT 'OPEN/RETRYING/RESOLVED/DEAD',
  `detail_masked` VARCHAR(1000) NULL COMMENT '脱敏异常说明',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  `last_error` VARCHAR(1000) NULL COMMENT '脱敏错误信息',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次重试时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `resolved_at` DATETIME(3) NULL COMMENT '解决时间，UTC',
  `resolved_by` BIGINT UNSIGNED NULL COMMENT '解决人',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_sync_issue_1` (`tenant_id`,`event_id`,`issue_type`),
  KEY `idx_youzan_sync_issue_1` (`tenant_id`,`status`,`next_retry_at`),
  CONSTRAINT `fk_youzan_sync_issue_event` FOREIGN KEY (`event_id`) REFERENCES `crm_youzan_event` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞映射、客户、支付及同步异常队列';

CREATE TABLE IF NOT EXISTS `crm_youzan_sync_cursor` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `shop_id` VARCHAR(64) NOT NULL COMMENT '店铺ID',
  `cursor_value` VARCHAR(128) NULL COMMENT '补偿同步游标',
  `last_sync_at` DATETIME(3) NULL COMMENT '最近成功拉取时间，UTC',
  `last_external_updated_at` DATETIME(3) NULL COMMENT '最近外部订单更新时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_youzan_sync_cursor_1` (`tenant_id`,`shop_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='有赞补偿同步游标与对账状态';
