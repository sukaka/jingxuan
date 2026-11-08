-- P0-4 预约、机构核销与冲正。正式核销仅追加；金额结算保持待核实。
CREATE TABLE IF NOT EXISTS `crm_appointment` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_no` VARCHAR(64) NOT NULL COMMENT '预约编号',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '预约机构',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '创建时客户归属管家快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '创建时客户团队快照',
  `scheduled_at` DATETIME(3) NOT NULL COMMENT '计划到院时间，UTC',
  `remark` VARCHAR(1000) NULL COMMENT '预约备注',
  `status` VARCHAR(48) NOT NULL COMMENT 'PENDING_INSTITUTION/CONFIRMED/CHECKED_IN/SERVICE_DONE/REDEEMED/REJECTED/CANCELED/NO_SHOW',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appointment_1` (`tenant_id`,`appointment_no`),
  KEY `idx_appointment_1` (`tenant_id`,`institution_id`,`status`,`scheduled_at`),
  KEY `idx_appointment_2` (`tenant_id`,`customer_id`,`created_at`),
  KEY `idx_appointment_3` (`tenant_id`,`owner_staff_id`,`team_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户到院预约';

CREATE TABLE IF NOT EXISTS `crm_appointment_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_id` BIGINT UNSIGNED NOT NULL COMMENT '预约',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '逐项目权益批次',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '订单快照子项目',
  `planned_count` INT NOT NULL COMMENT '计划服务次数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_appointment_item_1` (`tenant_id`,`appointment_id`,`entitlement_id`),
  KEY `idx_appointment_item_1` (`tenant_id`,`entitlement_id`),
  CONSTRAINT `fk_appointment_item_appointment` FOREIGN KEY (`appointment_id`) REFERENCES `crm_appointment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约逐项目权益批次与计划次数';

CREATE TABLE IF NOT EXISTS `crm_appointment_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_id` BIGINT UNSIGNED NOT NULL COMMENT '预约',
  `from_status` VARCHAR(48) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `reason` VARCHAR(1000) NULL COMMENT '操作理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_appointment_history_1` (`tenant_id`,`appointment_id`,`id`),
  CONSTRAINT `fk_appointment_history_appointment` FOREIGN KEY (`appointment_id`) REFERENCES `crm_appointment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约状态追加历史';

CREATE TABLE IF NOT EXISTS `crm_redemption` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `appointment_id` BIGINT UNSIGNED NOT NULL COMMENT '预约',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户快照',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '核销机构',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '客户归属快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '客户团队快照',
  `service_at` DATETIME(3) NOT NULL COMMENT '实际服务时间，UTC',
  `evidence_ref` VARCHAR(192) NOT NULL COMMENT '服务凭证引用',
  `voucher_file_id` BIGINT UNSIGNED NULL COMMENT '凭证文件',
  `status` VARCHAR(32) NOT NULL COMMENT 'POSTED；正式事实不可覆盖',
  `settlement_status` VARCHAR(48) NOT NULL DEFAULT 'PENDING_VERIFICATION' COMMENT 'Q02/Q04-Q06未确认，金额待核实',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '核销批次幂等键',
  `posted_by` BIGINT UNSIGNED NOT NULL COMMENT '机构OPERATOR用户',
  `posted_at` DATETIME(3) NOT NULL COMMENT '机构确认时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '事实版本；不提供更新路径',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_1` (`tenant_id`,`idempotency_key`),
  KEY `idx_redemption_1` (`tenant_id`,`institution_id`,`service_at`),
  KEY `idx_redemption_2` (`tenant_id`,`customer_id`,`service_at`),
  KEY `idx_redemption_3` (`tenant_id`,`appointment_id`),
  KEY `idx_redemption_4` (`tenant_id`,`owner_staff_id`,`team_id`),
  CONSTRAINT `fk_redemption_appointment` FOREIGN KEY (`appointment_id`) REFERENCES `crm_appointment` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='机构正式核销批次，仅追加';

CREATE TABLE IF NOT EXISTS `crm_redemption_line` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `redemption_id` BIGINT UNSIGNED NOT NULL COMMENT '核销批次',
  `appointment_item_id` BIGINT UNSIGNED NOT NULL COMMENT '预约项目',
  `entitlement_id` BIGINT UNSIGNED NOT NULL COMMENT '权益批次',
  `project_id` BIGINT UNSIGNED NOT NULL COMMENT '子项目',
  `count` INT NOT NULL COMMENT '正式核销次数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_line_1` (`tenant_id`,`redemption_id`,`entitlement_id`),
  UNIQUE KEY `uk_redemption_line_2` (`tenant_id`,`redemption_id`,`appointment_item_id`),
  KEY `idx_redemption_line_1` (`tenant_id`,`entitlement_id`),
  CONSTRAINT `fk_redemption_line_redemption` FOREIGN KEY (`redemption_id`) REFERENCES `crm_redemption` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_redemption_line_item` FOREIGN KEY (`appointment_item_id`) REFERENCES `crm_appointment_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式核销逐项目明细，仅追加';

CREATE TABLE IF NOT EXISTS `crm_redemption_status_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `redemption_id` BIGINT UNSIGNED NOT NULL COMMENT '核销批次',
  `from_status` VARCHAR(48) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `reason` VARCHAR(1000) NULL COMMENT '操作理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_redemption_status_history_1` (`tenant_id`,`redemption_id`,`id`),
  CONSTRAINT `fk_redemption_status_redemption` FOREIGN KEY (`redemption_id`) REFERENCES `crm_redemption` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='正式核销追加历史';

CREATE TABLE IF NOT EXISTS `crm_redemption_reversal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `redemption_id` BIGINT UNSIGNED NOT NULL COMMENT '原核销批次，不更新原事实',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户快照',
  `institution_id` BIGINT UNSIGNED NOT NULL COMMENT '机构快照',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '客户归属快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '客户团队快照',
  `status` VARCHAR(32) NOT NULL COMMENT 'REQUESTED/APPROVED/REJECTED',
  `reason` VARCHAR(1000) NOT NULL COMMENT '冲正申请理由',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `requested_at` DATETIME(3) NOT NULL COMMENT '申请时间，UTC',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '审核人，不能是申请人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间，UTC',
  `review_reason` VARCHAR(1000) NULL COMMENT '审核理由',
  `request_idempotency_key` VARCHAR(128) NOT NULL COMMENT '申请幂等键',
  `review_idempotency_key` VARCHAR(128) NULL COMMENT '审核幂等键',
  `active_redemption_id` BIGINT UNSIGNED NULL COMMENT 'REQUESTED/APPROVED时等于redemption_id，REJECTED为NULL',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_redemption_reversal_1` (`tenant_id`,`request_idempotency_key`),
  UNIQUE KEY `uk_redemption_reversal_2` (`tenant_id`,`review_idempotency_key`),
  UNIQUE KEY `uk_redemption_reversal_3` (`tenant_id`,`active_redemption_id`),
  KEY `idx_redemption_reversal_1` (`tenant_id`,`institution_id`,`status`),
  KEY `idx_redemption_reversal_2` (`tenant_id`,`customer_id`,`status`),
  CONSTRAINT `fk_redemption_reversal_redemption` FOREIGN KEY (`redemption_id`) REFERENCES `crm_redemption` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='核销全额冲正申请与审核';

CREATE TABLE IF NOT EXISTS `crm_redemption_reversal_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '企业/租户隔离',
  `reversal_id` BIGINT UNSIGNED NOT NULL COMMENT '冲正申请',
  `from_status` VARCHAR(48) NOT NULL COMMENT '变更前状态',
  `to_status` VARCHAR(48) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `reason` VARCHAR(1000) NULL COMMENT '操作理由',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_redemption_reversal_history_1` (`tenant_id`,`reversal_id`,`id`),
  CONSTRAINT `fk_redemption_reversal_history_reversal` FOREIGN KEY (`reversal_id`) REFERENCES `crm_redemption_reversal` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='冲正申请审核追加历史';
