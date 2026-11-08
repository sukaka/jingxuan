-- P2 公共运营治理：素材版本、导出审计、风控历史、可追溯分析快照。
-- V4 活动/批次/渠道与 V5 订单已存在；此处只允许增量变更，不重建历史事实表。

ALTER TABLE `crm_campaign_batch`
  ADD COLUMN `status` VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'DRAFT/ACTIVE/ARCHIVED；既有默认批次保持可售' AFTER `ends_at`,
  ADD KEY `idx_campaign_batch_status` (`tenant_id`,`campaign_id`,`status`);

ALTER TABLE `crm_order`
  ADD COLUMN `campaign_code_snapshot` VARCHAR(64) NULL COMMENT '销售时活动编码快照，不在后续覆盖' AFTER `campaign_batch_id`,
  ADD COLUMN `campaign_name_snapshot` VARCHAR(160) NULL COMMENT '销售时活动名称快照，不在后续覆盖' AFTER `campaign_code_snapshot`,
  ADD COLUMN `batch_code_snapshot` VARCHAR(64) NULL COMMENT '销售时批次编码快照，不在后续覆盖' AFTER `campaign_name_snapshot`,
  ADD COLUMN `batch_name_snapshot` VARCHAR(96) NULL COMMENT '销售时批次名称快照，不在后续覆盖' AFTER `batch_code_snapshot`,
  ADD COLUMN `channel_id` BIGINT UNSIGNED NULL COMMENT '销售时明确选择的渠道' AFTER `batch_name_snapshot`,
  ADD COLUMN `business_owner_at_sale_id` BIGINT UNSIGNED NULL COMMENT '成交时渠道商务归属快照，不从客户或管家推断' AFTER `channel_id`,
  ADD KEY `idx_order_channel_sale` (`tenant_id`,`channel_id`,`paid_at`),
  ADD KEY `idx_order_business_sale` (`tenant_id`,`business_owner_at_sale_id`,`paid_at`),
  ADD CONSTRAINT `fk_order_channel` FOREIGN KEY (`channel_id`) REFERENCES `crm_partner_channel` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_order_business_owner` FOREIGN KEY (`business_owner_at_sale_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE IF NOT EXISTS `crm_material` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `material_no` VARCHAR(64) NOT NULL COMMENT '素材编号',
  `title` VARCHAR(160) NOT NULL COMMENT '素材标题',
  `category_code` VARCHAR(48) NOT NULL COMMENT '素材分类字典',
  `institution_id` BIGINT UNSIGNED NULL COMMENT '机构范围；NULL仅表示受控全局素材，不豁免权限',
  `project_id` BIGINT UNSIGNED NULL COMMENT '关联项目',
  `city_code` VARCHAR(64) NULL COMMENT '城市字典',
  `summary` TEXT NULL COMMENT '素材摘要',
  `tags_json` JSON NULL COMMENT '标签快照JSON',
  `current_revision_id` BIGINT UNSIGNED NULL COMMENT '当前审核通过版本，草稿不覆盖该指针',
  `review_status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '最近审核人',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_no` (`tenant_id`,`material_no`),
  KEY `idx_material_category` (`tenant_id`,`category_code`),
  KEY `idx_material_institution` (`tenant_id`,`institution_id`,`review_status`),
  KEY `idx_material_city` (`tenant_id`,`city_code`),
  KEY `idx_material_project` (`tenant_id`,`project_id`),
  KEY `idx_material_review_status` (`tenant_id`,`review_status`),
  KEY `idx_material_current_revision` (`tenant_id`,`current_revision_id`),
  CONSTRAINT `fk_material_institution` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_project` FOREIGN KEY (`project_id`) REFERENCES `crm_project` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='素材主档；审核通过版本与草稿版本分离';

CREATE TABLE IF NOT EXISTS `crm_material_revision` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `material_id` BIGINT UNSIGNED NOT NULL COMMENT '所属素材',
  `revision_no` INT UNSIGNED NOT NULL COMMENT '递增版本号，不复用',
  `title` VARCHAR(160) NOT NULL COMMENT '该版本标题',
  `summary` TEXT NULL COMMENT '该版本摘要',
  `category_code` VARCHAR(48) NOT NULL COMMENT '该版本素材分类',
  `institution_id` BIGINT UNSIGNED NULL COMMENT '该版本机构范围；NULL为受控全局素材',
  `project_id` BIGINT UNSIGNED NULL COMMENT '该版本关联项目',
  `city_code` VARCHAR(64) NULL COMMENT '该版本城市',
  `tags_json` JSON NULL COMMENT '该版本标签快照JSON',
  `file_id` BIGINT UNSIGNED NOT NULL COMMENT '私有且ClamAV CLEAN的MATERIAL文件',
  `change_note` TEXT NULL COMMENT '版本变更说明',
  `status` VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/REJECTED',
  `reviewer_id` BIGINT UNSIGNED NULL COMMENT '审核人，不得为提交人',
  `review_note` TEXT NULL COMMENT '审核意见',
  `reviewed_at` DATETIME(3) NULL COMMENT '审核时间，UTC',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '版本创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_revision_no` (`tenant_id`,`material_id`,`revision_no`),
  KEY `idx_material_revision_file` (`tenant_id`,`file_id`),
  KEY `idx_material_revision_status` (`tenant_id`,`material_id`,`status`),
  CONSTRAINT `fk_material_revision_material` FOREIGN KEY (`material_id`) REFERENCES `crm_material` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_revision_institution` FOREIGN KEY (`institution_id`) REFERENCES `crm_institution` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_revision_project` FOREIGN KEY (`project_id`) REFERENCES `crm_project` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_revision_file` FOREIGN KEY (`file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_revision_reviewer` FOREIGN KEY (`reviewer_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_material_revision_created_by` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='素材不可复用版本；终审版本禁止覆盖';

ALTER TABLE `crm_material`
  ADD CONSTRAINT `fk_material_current_revision` FOREIGN KEY (`current_revision_id`) REFERENCES `crm_material_revision` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE TABLE IF NOT EXISTS `crm_export_job` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `job_no` VARCHAR(64) NOT NULL COMMENT '导出任务编号',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请账号',
  `export_type` VARCHAR(48) NOT NULL COMMENT 'CUSTOMER/ORDER/REDEMPTION/FOLLOWUP',
  `status` VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/FAILED',
  `filters_json` JSON NULL COMMENT '服务端校验后的筛选条件，不接受客户端放大范围',
  `columns_json` JSON NULL COMMENT '服务端校验后的字段白名单',
  `scope_json` JSON NOT NULL COMMENT '服务端校验并保存的数据范围快照',
  `reason` VARCHAR(500) NOT NULL COMMENT '导出理由',
  `row_count` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '实际导出条数',
  `file_id` BIGINT UNSIGNED NULL COMMENT '私有且ClamAV CLEAN的EXPORT文件',
  `expires_at` DATETIME(3) NULL COMMENT '下载截止时间，UTC',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '申请人幂等键',
  `request_hash` CHAR(64) NOT NULL COMMENT '规范化请求SHA-256',
  `lease_owner` VARCHAR(128) NULL COMMENT '执行实例租约 owner',
  `lease_expires_at` DATETIME(3) NULL COMMENT '执行租约到期时间，UTC',
  `started_at` DATETIME(3) NULL COMMENT '开始执行时间，UTC',
  `finished_at` DATETIME(3) NULL COMMENT '完成时间，UTC',
  `last_error` VARCHAR(500) NULL COMMENT '脱敏失败类别，不保存导出内容或凭证',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_export_job_no` (`tenant_id`,`job_no`),
  UNIQUE KEY `uk_export_job_idempotency` (`tenant_id`,`requested_by`,`idempotency_key`),
  KEY `idx_export_job_requester` (`tenant_id`,`requested_by`,`status`,`created_at`),
  KEY `idx_export_job_lease` (`tenant_id`,`status`,`lease_expires_at`,`id`),
  KEY `idx_export_job_file` (`tenant_id`,`file_id`),
  CONSTRAINT `fk_export_job_requester` FOREIGN KEY (`requested_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_export_job_file` FOREIGN KEY (`file_id`) REFERENCES `crm_file_object` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='授权异步导出任务；字段与数据范围使用服务端校验快照';

CREATE TABLE IF NOT EXISTS `crm_export_access_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `job_id` BIGINT UNSIGNED NULL COMMENT '导出任务；创建权限拒绝时可为空',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作账号',
  `action` VARCHAR(32) NOT NULL COMMENT 'REQUEST/DOWNLOAD',
  `result` VARCHAR(32) NOT NULL COMMENT 'ALLOWED/DENIED',
  `reason` VARCHAR(500) NULL COMMENT '操作理由或脱敏结果说明',
  `denial_code` VARCHAR(96) NULL COMMENT '稳定拒绝原因码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间，UTC',
  PRIMARY KEY (`id`),
  KEY `idx_export_access_job` (`tenant_id`,`job_id`,`id`),
  KEY `idx_export_access_actor` (`tenant_id`,`actor_user_id`,`created_at`),
  CONSTRAINT `fk_export_access_job` FOREIGN KEY (`job_id`) REFERENCES `crm_export_job` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_export_access_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='导出申请与下载只追加审计，不记录敏感导出内容';

CREATE TABLE IF NOT EXISTS `crm_risk_case` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `risk_case_no` VARCHAR(64) NOT NULL COMMENT '风险台账编号',
  `risk_type` VARCHAR(64) NOT NULL COMMENT '风险类型',
  `severity` VARCHAR(24) NOT NULL COMMENT 'HIGH/MEDIUM/LOW',
  `source_type` VARCHAR(48) NOT NULL COMMENT '来源业务类型',
  `source_ref` VARCHAR(128) NOT NULL COMMENT '稳定来源业务标识',
  `status` VARCHAR(32) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ASSIGNED/RESOLVED/CLOSED',
  `owner_user_id` BIGINT UNSIGNED NULL COMMENT '认领人',
  `evidence` TEXT NULL COMMENT '脱敏风险证据摘要',
  `resolution` TEXT NULL COMMENT '处理结论',
  `resolved_at` DATETIME(3) NULL COMMENT '解决时间，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_risk_case_no` (`tenant_id`,`risk_case_no`),
  UNIQUE KEY `uk_risk_case_source` (`tenant_id`,`source_type`,`source_ref`,`risk_type`),
  KEY `idx_risk_case_status` (`tenant_id`,`status`,`severity`),
  KEY `idx_risk_case_severity` (`tenant_id`,`severity`,`status`),
  KEY `idx_risk_case_owner` (`tenant_id`,`owner_user_id`,`status`),
  CONSTRAINT `fk_risk_case_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控台账；无物理删除接口';

CREATE TABLE IF NOT EXISTS `crm_risk_case_history` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `risk_case_id` BIGINT UNSIGNED NOT NULL COMMENT '风险台账',
  `action` VARCHAR(48) NOT NULL COMMENT 'CREATE/CLAIM/RESOLVE/CLOSE等动作',
  `from_status` VARCHAR(32) NULL COMMENT '变更前状态',
  `to_status` VARCHAR(32) NOT NULL COMMENT '变更后状态',
  `actor_user_id` BIGINT UNSIGNED NULL COMMENT '操作人；系统来源可为空',
  `note` TEXT NULL COMMENT '脱敏处理说明',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间，UTC',
  PRIMARY KEY (`id`),
  KEY `idx_risk_history_case` (`tenant_id`,`risk_case_id`,`id`),
  CONSTRAINT `fk_risk_history_case` FOREIGN KEY (`risk_case_id`) REFERENCES `crm_risk_case` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_risk_history_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风控状态与处理只追加历史';

CREATE TABLE IF NOT EXISTS `crm_daily_analytics` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `stat_date` DATE NOT NULL COMMENT '统计日期，口径时区由报表显式返回',
  `dimension_type` VARCHAR(48) NOT NULL COMMENT 'STAFF/INSTITUTION/CHANNEL/CAMPAIGN等',
  `dimension_ref` VARCHAR(96) NOT NULL COMMENT '稳定维度标识',
  `metric_code` VARCHAR(64) NOT NULL COMMENT '指标编码',
  `metric_value` BIGINT NOT NULL DEFAULT 0 COMMENT '整数值；金额指标固定为分',
  `metric_version` VARCHAR(32) NOT NULL COMMENT '指标口径版本',
  `source_watermark` DATETIME(3) NULL COMMENT '来源事实数据水位，UTC',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '刷新时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_daily_analytics_metric` (`tenant_id`,`stat_date`,`dimension_type`,`dimension_ref`,`metric_code`,`metric_version`),
  KEY `idx_daily_analytics_dimension` (`tenant_id`,`dimension_type`,`dimension_ref`,`stat_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可追溯分析快照，不是业务事实源；无真实事实不得生成演示值';

CREATE TABLE IF NOT EXISTS `crm_performance_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `period_key` VARCHAR(32) NOT NULL COMMENT '业绩期间YYYY-MM',
  `staff_user_id` BIGINT UNSIGNED NOT NULL COMMENT '管家或商务员工',
  `performance_role` VARCHAR(32) NOT NULL COMMENT 'MANAGER/BUSINESS',
  `gross_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '汇总实付金额分',
  `refund_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '成功退款冲减金额分',
  `net_fen` BIGINT NOT NULL DEFAULT 0 COMMENT '按明确口径计算的净业绩分',
  `target_fen` BIGINT NULL COMMENT '批准后的目标分；未配置或规则未批准时为空',
  `rule_version` VARCHAR(32) NOT NULL COMMENT '归属与计算规则版本',
  `status` VARCHAR(32) NOT NULL COMMENT 'PREVIEW/APPROVED；PREVIEW不是工资或提成事实',
  `approved_by` BIGINT UNSIGNED NULL COMMENT '批准人',
  `source_watermark` DATETIME(3) NULL COMMENT '来源事实数据水位，UTC',
  `created_by` BIGINT UNSIGNED NOT NULL COMMENT '快照创建人',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_performance_snapshot_period` (`tenant_id`,`period_key`,`staff_user_id`,`performance_role`),
  KEY `idx_performance_snapshot_staff` (`tenant_id`,`staff_user_id`,`period_key`,`status`),
  CONSTRAINT `fk_performance_staff` FOREIGN KEY (`staff_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_performance_approver` FOREIGN KEY (`approved_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_performance_creator` FOREIGN KEY (`created_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='可追溯业绩快照；PREVIEW不构成工资或提成事实';

CREATE TRIGGER `trg_material_revision_no_update` BEFORE UPDATE ON `crm_material_revision`
FOR EACH ROW
BEGIN
  IF OLD.`status` IN ('APPROVED','REJECTED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Final material revision is immutable';
  END IF;
END;

CREATE TRIGGER `trg_material_revision_no_delete` BEFORE DELETE ON `crm_material_revision`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Material revision history is append only';

CREATE TRIGGER `trg_export_access_no_update` BEFORE UPDATE ON `crm_export_access_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Export access log is append only';

CREATE TRIGGER `trg_export_access_no_delete` BEFORE DELETE ON `crm_export_access_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Export access log is append only';

CREATE TRIGGER `trg_risk_history_no_update` BEFORE UPDATE ON `crm_risk_case_history`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Risk history is append only';

CREATE TRIGGER `trg_risk_history_no_delete` BEFORE DELETE ON `crm_risk_case_history`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Risk history is append only';

CREATE TRIGGER `trg_order_sale_snapshot_no_update` BEFORE UPDATE ON `crm_order`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`campaign_code_snapshot` <=> NEW.`campaign_code_snapshot`)
      OR NOT (OLD.`campaign_name_snapshot` <=> NEW.`campaign_name_snapshot`)
      OR NOT (OLD.`batch_code_snapshot` <=> NEW.`batch_code_snapshot`)
      OR NOT (OLD.`batch_name_snapshot` <=> NEW.`batch_name_snapshot`)
      OR NOT (OLD.`channel_id` <=> NEW.`channel_id`)
      OR NOT (OLD.`business_owner_at_sale_id` <=> NEW.`business_owner_at_sale_id`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Order sale snapshots are immutable';
  END IF;
END;
