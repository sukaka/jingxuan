-- 跟进策略、订单三个月回访批次、执行事实与到期通知调度租约。
CREATE TABLE `crm_followup_policy` (
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `default_interval_days` INT UNSIGNED NOT NULL DEFAULT 14 COMMENT '未匹配意向时的跟进间隔天数',
  `updated_by` BIGINT UNSIGNED NOT NULL COMMENT '最近修改人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`tenant_id`),
  CONSTRAINT `fk_followup_policy_updated_by` FOREIGN KEY (`updated_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户级跟进间隔策略';

CREATE TABLE `crm_followup_intent_policy` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `intent_level` VARCHAR(32) NOT NULL COMMENT '客户意向级别快照匹配码',
  `interval_days` INT UNSIGNED NOT NULL COMMENT '该意向级别的新跟进间隔天数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_intent_policy` (`tenant_id`,`intent_level`),
  CONSTRAINT `fk_followup_intent_policy_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `crm_followup_policy` (`tenant_id`) ON DELETE CASCADE ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='意向级别跟进间隔覆盖；不影响历史跟进';

CREATE TABLE `crm_followup_batch` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `order_month` CHAR(7) NOT NULL COMMENT '订单自然月YYYY-MM',
  `status` VARCHAR(24) NOT NULL COMMENT 'READY/RUNNING/COMPLETED/FAILED',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人与自动重试审计人',
  `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已失败执行次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '自动重试最早时间UTC',
  `lease_owner` VARCHAR(128) NULL COMMENT '当前执行租约持有者',
  `lease_expires_at` DATETIME(3) NULL COMMENT '执行租约到期时间UTC',
  `last_error_masked` VARCHAR(512) NULL COMMENT '最近脱敏错误',
  `started_at` DATETIME(3) NULL COMMENT '最近开始执行时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '最近完成时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_batch_month` (`tenant_id`,`order_month`),
  KEY `idx_followup_batch_retry` (`tenant_id`,`status`,`next_retry_at`,`id`),
  KEY `idx_followup_batch_lease` (`status`,`lease_expires_at`,`id`),
  CONSTRAINT `fk_followup_batch_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单加三个自然月的月度回访批次';

CREATE TABLE `crm_followup_batch_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '回访批次',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '客户；专属归属不随批次改变',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '预览时专属管家快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '预览时客户团队快照',
  `due_at` DATETIME(3) NOT NULL COMMENT '最早关联订单加三个自然月',
  `task_id` BIGINT UNSIGNED NULL COMMENT '已创建或已去重的任务',
  `status` VARCHAR(24) NOT NULL COMMENT 'PENDING/CREATED/SKIPPED/FAILED',
  `reason` VARCHAR(500) NULL COMMENT '跳过原因或脱敏失败原因',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_batch_customer` (`tenant_id`,`batch_id`,`customer_id`),
  KEY `idx_followup_batch_item_work` (`tenant_id`,`batch_id`,`status`,`id`),
  KEY `idx_followup_batch_item_task` (`tenant_id`,`task_id`),
  CONSTRAINT `fk_followup_batch_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `crm_followup_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_followup_batch_item_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_followup_batch_item_task` FOREIGN KEY (`task_id`) REFERENCES `crm_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='月度批次客户明细；同客户同批次唯一';

CREATE TABLE `crm_followup_batch_order` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '回访批次',
  `item_id` BIGINT UNSIGNED NOT NULL COMMENT '批次客户明细',
  `order_id` BIGINT UNSIGNED NOT NULL COMMENT '关联订单事实',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_batch_order` (`tenant_id`,`batch_id`,`order_id`),
  UNIQUE KEY `uk_followup_batch_item_order` (`tenant_id`,`item_id`,`order_id`),
  KEY `idx_followup_batch_order_item` (`tenant_id`,`item_id`,`order_id`),
  CONSTRAINT `fk_followup_batch_order_batch` FOREIGN KEY (`batch_id`) REFERENCES `crm_followup_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_followup_batch_order_item` FOREIGN KEY (`item_id`) REFERENCES `crm_followup_batch_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_followup_batch_order_order` FOREIGN KEY (`order_id`) REFERENCES `crm_order` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一个批次客户任务关联的多订单快照';

CREATE TABLE `crm_followup_batch_attempt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `batch_id` BIGINT UNSIGNED NOT NULL COMMENT '回访批次',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '批次内执行序号',
  `status` VARCHAR(24) NOT NULL COMMENT 'COMPLETED/FAILED',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '触发人或原创建人',
  `error_masked` VARCHAR(512) NULL COMMENT '脱敏执行错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最终记录时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_batch_attempt` (`tenant_id`,`batch_id`,`attempt_no`),
  CONSTRAINT `fk_followup_batch_attempt_batch` FOREIGN KEY (`batch_id`) REFERENCES `crm_followup_batch` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_followup_batch_attempt_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批次执行最终事实，只追加不覆盖';

CREATE TABLE `crm_followup_schedule_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `job_type` VARCHAR(48) NOT NULL COMMENT 'TASK_DUE_NOTIFICATION等',
  `job_key` VARCHAR(192) NOT NULL COMMENT '稳定业务去重键',
  `status` VARCHAR(24) NOT NULL COMMENT 'READY/RUNNING/DONE/FAILED',
  `retry_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '失败次数',
  `next_retry_at` DATETIME(3) NULL COMMENT '下次可执行时间UTC',
  `lease_owner` VARCHAR(128) NULL COMMENT '调度租约持有者',
  `lease_expires_at` DATETIME(3) NULL COMMENT '调度租约到期时间UTC',
  `last_error_masked` VARCHAR(512) NULL COMMENT '脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_followup_schedule_job` (`tenant_id`,`job_type`,`job_key`),
  KEY `idx_followup_schedule_work` (`status`,`next_retry_at`,`lease_expires_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跟进到期调度唯一键、租约与重试';

ALTER TABLE `crm_order`
  ADD KEY `idx_order_followup_candidate` (`tenant_id`,`status`,`ordered_at`,`customer_id`);
ALTER TABLE `crm_task`
  ADD KEY `idx_task_followup_due` (`tenant_id`,`status`,`source_type`,`due_at`,`assignee_user_id`);

CREATE TRIGGER `trg_followup_attempt_no_update` BEFORE UPDATE ON `crm_followup_batch_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Followup batch attempt is append only';
CREATE TRIGGER `trg_followup_attempt_no_delete` BEFORE DELETE ON `crm_followup_batch_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Followup batch attempt is append only';
