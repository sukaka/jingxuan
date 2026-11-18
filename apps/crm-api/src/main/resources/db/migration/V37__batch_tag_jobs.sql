-- V34 批量重打标。预览与任务成员均为固定快照；执行结果只追加尝试历史，不覆盖原任务定义。
INSERT INTO sys_dictionary_type (tenant_id,dict_code,dict_name,status)
SELECT tenant_id,'customerTags','客户标签','ACTIVE' FROM sys_org GROUP BY tenant_id
ON DUPLICATE KEY UPDATE dict_name=VALUES(dict_name);

CREATE TABLE `crm_batch_tag_preview` (
  `id` VARCHAR(64) NOT NULL COMMENT '预览令牌',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '预览请求人',
  `operation` VARCHAR(16) NOT NULL COMMENT 'ADD/REMOVE',
  `tag_codes_json` JSON NOT NULL COMMENT '请求标签代码快照',
  `tag_snapshot_json` JSON NOT NULL COMMENT '请求时标签代码与字典名称快照',
  `source_type` VARCHAR(32) NOT NULL COMMENT 'FILTER/SEGMENT/OPERATION_BATCH',
  `source_id` BIGINT UNSIGNED NULL COMMENT '分组或操作批次ID',
  `source_snapshot_json` JSON NOT NULL COMMENT '筛选/分组/操作批次来源快照',
  `scope_snapshot_json` JSON NOT NULL COMMENT '请求时数据范围快照，执行仍实时重验',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '预览幂等键',
  `member_count` INT UNSIGNED NOT NULL COMMENT '固定快照成员数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  KEY `idx_batch_tag_preview_requester` (`tenant_id`,`requested_by`,`created_at`),
  CONSTRAINT `fk_batch_tag_preview_requester` FOREIGN KEY (`requested_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量重打标服务端预览定义，不可变';

CREATE TABLE `crm_batch_tag_preview_member` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '预览成员主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `preview_id` VARCHAR(64) NOT NULL COMMENT '所属预览',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '预览时客户唯一ID',
  `customer_no` VARCHAR(48) NOT NULL COMMENT '预览时客户编号快照',
  `customer_name` VARCHAR(192) NULL COMMENT '预览时脱敏称呼快照',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '预览时归属管家快照',
  `team_id` BIGINT UNSIGNED NULL COMMENT '预览时团队快照',
  `tag_codes_json` JSON NULL COMMENT '独立回滚时该成员实际需反向处理的标签',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_tag_preview_member` (`tenant_id`,`preview_id`,`customer_id`),
  KEY `idx_batch_tag_preview_member_customer` (`tenant_id`,`customer_id`,`preview_id`),
  CONSTRAINT `fk_batch_tag_preview_member_preview` FOREIGN KEY (`preview_id`) REFERENCES `crm_batch_tag_preview` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_tag_preview_member_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量重打标预览成员不可变快照';

CREATE TABLE `crm_batch_tag_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `job_no` VARCHAR(64) NOT NULL COMMENT '任务编号',
  `preview_id` VARCHAR(64) NULL COMMENT '来源预览；独立回滚任务为空',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '任务请求人，worker按此身份实时重验',
  `operation` VARCHAR(16) NOT NULL COMMENT 'ADD/REMOVE',
  `tag_codes_json` JSON NOT NULL COMMENT '任务标签代码快照',
  `tag_snapshot_json` JSON NOT NULL COMMENT '任务创建时标签字典快照',
  `source_type` VARCHAR(32) NOT NULL COMMENT 'FILTER/SEGMENT/OPERATION_BATCH/ROLLBACK',
  `source_id` BIGINT UNSIGNED NULL COMMENT '来源ID或原任务ID',
  `source_snapshot_json` JSON NOT NULL COMMENT '不可变来源快照',
  `scope_snapshot_json` JSON NOT NULL COMMENT '请求时数据范围快照，不作为执行授权',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '创建任务幂等键',
  `rollback_of_job_id` BIGINT UNSIGNED NULL COMMENT '独立反向任务对应的原任务',
  `status` VARCHAR(24) NOT NULL COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL_FAILED/FAILED',
  `total_count` INT UNSIGNED NOT NULL COMMENT '固定成员总数',
  `succeeded_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际变更成功数',
  `no_change_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '目标状态已符合数',
  `skipped_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '撤权/归属/合并等跳过数',
  `failed_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '当前失败数',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已完成执行次数',
  `lease_owner` VARCHAR(128) NULL COMMENT '执行租约持有者',
  `lease_expires_at` DATETIME(3) NULL COMMENT '执行租约到期时间UTC',
  `last_error_masked` VARCHAR(512) NULL COMMENT '最近脱敏错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `started_at` DATETIME(3) NULL COMMENT '最近开始时间UTC',
  `finished_at` DATETIME(3) NULL COMMENT '最近完成时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_tag_job_no` (`tenant_id`,`job_no`),
  UNIQUE KEY `uk_batch_tag_job_preview` (`tenant_id`,`preview_id`),
  UNIQUE KEY `uk_batch_tag_job_rollback` (`tenant_id`,`rollback_of_job_id`),
  KEY `idx_batch_tag_job_list` (`tenant_id`,`requested_by`,`status`,`id`),
  KEY `idx_batch_tag_job_lease` (`status`,`lease_expires_at`,`id`),
  CONSTRAINT `fk_batch_tag_job_preview` FOREIGN KEY (`preview_id`) REFERENCES `crm_batch_tag_preview` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_tag_job_requester` FOREIGN KEY (`requested_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_tag_job_rollback` FOREIGN KEY (`rollback_of_job_id`) REFERENCES `crm_batch_tag_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量重打标任务；定义不可变，执行状态带版本和租约';

CREATE TABLE `crm_batch_tag_job_item` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务明细主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `job_id` BIGINT UNSIGNED NOT NULL COMMENT '所属任务',
  `customer_id` BIGINT UNSIGNED NOT NULL COMMENT '固定快照客户唯一ID',
  `customer_no` VARCHAR(48) NOT NULL COMMENT '客户编号快照',
  `customer_name` VARCHAR(192) NULL COMMENT '客户称呼快照',
  `owner_staff_id` BIGINT UNSIGNED NOT NULL COMMENT '归属管家快照，执行重验当前归属',
  `team_id` BIGINT UNSIGNED NULL COMMENT '团队快照',
  `tag_codes_json` JSON NOT NULL COMMENT '该明细实际处理的标签；回滚可为原成功子集',
  `status` VARCHAR(24) NOT NULL COMMENT 'PENDING/SUCCEEDED/NO_CHANGE/SKIPPED/FAILED',
  `failure_code` VARCHAR(64) NULL COMMENT '稳定跳过/失败原因码',
  `reason` VARCHAR(500) NULL COMMENT '脱敏结果说明',
  `before_tag_codes_json` JSON NULL COMMENT '处理前完整标签快照，含未知历史标签',
  `after_tag_codes_json` JSON NULL COMMENT '处理后完整标签快照，含未知历史标签',
  `changed_tag_codes_json` JSON NULL COMMENT '本任务实际改变的标签子集，供独立回滚',
  `attempt_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已记录明细结果次数',
  `processed_at` DATETIME(3) NULL COMMENT '最近结果时间UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_tag_job_customer` (`tenant_id`,`job_id`,`customer_id`),
  KEY `idx_batch_tag_item_work` (`tenant_id`,`job_id`,`status`,`id`),
  KEY `idx_batch_tag_item_customer` (`tenant_id`,`customer_id`,`job_id`),
  CONSTRAINT `fk_batch_tag_item_job` FOREIGN KEY (`job_id`) REFERENCES `crm_batch_tag_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_tag_item_customer` FOREIGN KEY (`customer_id`) REFERENCES `crm_customer` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量重打标逐项当前结果；定义快照不可变';

CREATE TABLE `crm_batch_tag_item_attempt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '明细尝试主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `job_id` BIGINT UNSIGNED NOT NULL COMMENT '所属任务',
  `item_id` BIGINT UNSIGNED NOT NULL COMMENT '所属明细',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '明细内结果序号',
  `status` VARCHAR(24) NOT NULL COMMENT 'SUCCEEDED/NO_CHANGE/SKIPPED/FAILED',
  `failure_code` VARCHAR(64) NULL COMMENT '稳定跳过/失败原因码',
  `reason` VARCHAR(500) NULL COMMENT '脱敏结果说明',
  `before_tag_codes_json` JSON NULL COMMENT '该次处理前完整标签快照',
  `after_tag_codes_json` JSON NULL COMMENT '该次处理后完整标签快照',
  `changed_tag_codes_json` JSON NULL COMMENT '该次实际改变标签',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '原任务请求人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '结果时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_tag_item_attempt` (`tenant_id`,`item_id`,`attempt_no`),
  KEY `idx_batch_tag_item_attempt_job` (`tenant_id`,`job_id`,`id`),
  CONSTRAINT `fk_batch_tag_item_attempt_item` FOREIGN KEY (`item_id`) REFERENCES `crm_batch_tag_job_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_batch_tag_item_attempt_job` FOREIGN KEY (`job_id`) REFERENCES `crm_batch_tag_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='逐项结果追加历史，不覆盖';

CREATE TABLE `crm_batch_tag_job_attempt` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '任务尝试主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '租户隔离键',
  `job_id` BIGINT UNSIGNED NOT NULL COMMENT '所属任务',
  `attempt_no` INT UNSIGNED NOT NULL COMMENT '任务内执行序号',
  `status` VARCHAR(24) NOT NULL COMMENT 'SUCCEEDED/PARTIAL_FAILED/FAILED',
  `trigger_type` VARCHAR(32) NOT NULL COMMENT 'EXECUTE/RETRY/RECOVERY',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '原任务请求人',
  `error_masked` VARCHAR(512) NULL COMMENT '脱敏汇总错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '完成时间UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_tag_job_attempt` (`tenant_id`,`job_id`,`attempt_no`),
  CONSTRAINT `fk_batch_tag_job_attempt_job` FOREIGN KEY (`job_id`) REFERENCES `crm_batch_tag_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='批量重打标任务执行追加历史';

CREATE TRIGGER `trg_batch_tag_preview_no_update` BEFORE UPDATE ON `crm_batch_tag_preview`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag preview is immutable';
CREATE TRIGGER `trg_batch_tag_preview_no_delete` BEFORE DELETE ON `crm_batch_tag_preview`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag preview is append only';
CREATE TRIGGER `trg_batch_tag_preview_member_no_update` BEFORE UPDATE ON `crm_batch_tag_preview_member`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag preview member is immutable';
CREATE TRIGGER `trg_batch_tag_preview_member_no_delete` BEFORE DELETE ON `crm_batch_tag_preview_member`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag preview member is append only';

CREATE TRIGGER `trg_batch_tag_job_snapshot` BEFORE UPDATE ON `crm_batch_tag_job`
FOR EACH ROW BEGIN
  IF NOT (NEW.job_no <=> OLD.job_no AND NEW.preview_id <=> OLD.preview_id
    AND NEW.requested_by <=> OLD.requested_by AND NEW.operation <=> OLD.operation
    AND NEW.tag_codes_json <=> OLD.tag_codes_json AND NEW.tag_snapshot_json <=> OLD.tag_snapshot_json
    AND NEW.source_type <=> OLD.source_type AND NEW.source_id <=> OLD.source_id
    AND NEW.source_snapshot_json <=> OLD.source_snapshot_json AND NEW.scope_snapshot_json <=> OLD.scope_snapshot_json
    AND NEW.idempotency_key <=> OLD.idempotency_key AND NEW.rollback_of_job_id <=> OLD.rollback_of_job_id
    AND NEW.total_count <=> OLD.total_count) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag job request snapshot is immutable';
  END IF;
END;
CREATE TRIGGER `trg_batch_tag_job_no_delete` BEFORE DELETE ON `crm_batch_tag_job`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag job is append only';
CREATE TRIGGER `trg_batch_tag_item_snapshot` BEFORE UPDATE ON `crm_batch_tag_job_item`
FOR EACH ROW BEGIN
  IF NOT (NEW.customer_id <=> OLD.customer_id AND NEW.customer_no <=> OLD.customer_no
    AND NEW.customer_name <=> OLD.customer_name AND NEW.owner_staff_id <=> OLD.owner_staff_id
    AND NEW.team_id <=> OLD.team_id AND NEW.tag_codes_json <=> OLD.tag_codes_json) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag item snapshot is immutable';
  END IF;
END;
CREATE TRIGGER `trg_batch_tag_item_no_delete` BEFORE DELETE ON `crm_batch_tag_job_item`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag item is append only';
CREATE TRIGGER `trg_batch_tag_item_attempt_no_update` BEFORE UPDATE ON `crm_batch_tag_item_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag item attempt is immutable';
CREATE TRIGGER `trg_batch_tag_item_attempt_no_delete` BEFORE DELETE ON `crm_batch_tag_item_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag item attempt is append only';
CREATE TRIGGER `trg_batch_tag_job_attempt_no_update` BEFORE UPDATE ON `crm_batch_tag_job_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag job attempt is immutable';
CREATE TRIGGER `trg_batch_tag_job_attempt_no_delete` BEFORE DELETE ON `crm_batch_tag_job_attempt`
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch tag job attempt is append only';
