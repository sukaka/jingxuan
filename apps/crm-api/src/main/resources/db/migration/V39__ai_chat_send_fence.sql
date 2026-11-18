CREATE TABLE `crm_ai_chat_send_fence` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `run_id` BIGINT UNSIGNED NOT NULL COMMENT 'AI托管处理流水',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户隔离与停用等待维度',
  `blocked` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '停用后永久禁止该次发送授权',
  `in_flight` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '已授权且不可撤销的外部调用是否未结束',
  `started_at` DATETIME(3) NULL COMMENT '发送授权生效时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '外部调用结束时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ai_chat_send_fence_run` (`tenant_id`,`run_id`),
  KEY `idx_ai_chat_send_fence_tenant_flight` (`tenant_id`,`in_flight`,`id`),
  KEY `idx_ai_chat_send_fence_customer_flight` (`tenant_id`,`customer_id`,`in_flight`,`id`),
  CONSTRAINT `fk_ai_chat_send_fence_run` FOREIGN KEY (`run_id`) REFERENCES `crm_ai_chat_delegation_run` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_ai_chat_send_fence_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI外部发送启动栅栏；HTTP不持有数据库事务';
