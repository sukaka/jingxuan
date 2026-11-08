-- 客户合并前资源快照与合并更正审批；不修改原合并历史记录。
CREATE TABLE IF NOT EXISTS `crm_merge_resource_snapshot` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `merge_case_id` BIGINT UNSIGNED NOT NULL COMMENT '合并单ID',
  `resource_type` VARCHAR(32) NOT NULL COMMENT 'IDENTITY/TAG/FOLLOWUP/TASK/POINTS/ORDER/ENTITLEMENT',
  `resource_key` VARCHAR(128) NOT NULL COMMENT '被迁移资源ID或标签编码',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merge_resource_snapshot_1` (`tenant_id`,`merge_case_id`,`resource_type`,`resource_key`),
  KEY `idx_merge_resource_snapshot_1` (`tenant_id`,`merge_case_id`,`resource_type`),
  CONSTRAINT `fk_merge_resource_snapshot_case` FOREIGN KEY (`merge_case_id`) REFERENCES `crm_merge_case` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='确认合并时实际迁移资源快照';

CREATE TABLE IF NOT EXISTS `crm_merge_reversal` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `merge_case_id` BIGINT UNSIGNED NOT NULL COMMENT '原合并单ID',
  `status` VARCHAR(32) NOT NULL COMMENT 'REQUESTED/MANUAL_REVIEW/EXECUTED/REJECTED/ERROR',
  `reason` VARCHAR(1000) NOT NULL COMMENT '更正申请理由',
  `review_reason` VARCHAR(1000) NULL COMMENT '审批或人工完成说明',
  `primary_version` BIGINT UNSIGNED NOT NULL COMMENT '申请时主客户版本',
  `secondary_version` BIGINT UNSIGNED NOT NULL COMMENT '申请时从客户版本',
  `requested_by` BIGINT UNSIGNED NOT NULL COMMENT '申请人',
  `requested_at` DATETIME(3) NOT NULL COMMENT '申请时间，UTC',
  `reviewed_by` BIGINT UNSIGNED NULL COMMENT '审批人',
  `reviewed_at` DATETIME(3) NULL COMMENT '审批时间，UTC',
  `executed_at` DATETIME(3) NULL COMMENT '完成时间，UTC',
  `warning_message` VARCHAR(1000) NULL COMMENT '恢复限制或需人工处理警示',
  `error_masked` VARCHAR(1000) NULL COMMENT '脱敏执行错误',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间，UTC',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间，UTC',
  `version` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_merge_reversal_1` (`tenant_id`,`merge_case_id`),
  KEY `idx_merge_reversal_1` (`tenant_id`,`status`),
  CONSTRAINT `fk_merge_reversal_case` FOREIGN KEY (`merge_case_id`) REFERENCES `crm_merge_case` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户合并更正申请与审批';

INSERT INTO `sys_permission` (`tenant_id`,`permission_code`,`description`)
SELECT DISTINCT r.`tenant_id`,'customer:merge_reverse','客户合并更正审批与执行' FROM `sys_role` r
ON DUPLICATE KEY UPDATE `description`=VALUES(`description`);
