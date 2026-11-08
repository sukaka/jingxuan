-- 任务动作工作流：任务池团队快照、首次负责人、接手/完成时间与只追加动作日志。
-- 历史任务仅回填已有负责人和团队快照，不回填接手时间，也不生成虚构的历史动作。

ALTER TABLE `crm_task`
  ADD COLUMN `original_assignee_user_id` BIGINT UNSIGNED NULL COMMENT '首次负责人；退回任务池后仍保留' AFTER `assignee_user_id`,
  ADD COLUMN `task_team_id` BIGINT UNSIGNED NULL COMMENT '任务池团队；不随成员后续换组改变' AFTER `original_assignee_user_id`,
  ADD COLUMN `claimed_at` DATETIME(3) NULL COMMENT '当前负责人接手时间，UTC' AFTER `source_ref`,
  ADD COLUMN `completed_at` DATETIME(3) NULL COMMENT '完成时间，UTC' AFTER `claimed_at`,
  ADD KEY `idx_task_team_pool` (`tenant_id`,`task_team_id`,`status`,`assignee_user_id`,`due_at`),
  ADD KEY `idx_task_original_assignee` (`tenant_id`,`original_assignee_user_id`),
  ADD CONSTRAINT `fk_task_original_assignee` FOREIGN KEY (`original_assignee_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_task_team` FOREIGN KEY (`task_team_id`) REFERENCES `sys_team` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT;

UPDATE `crm_task` t
LEFT JOIN `sys_user` u ON u.`tenant_id` = t.`tenant_id` AND u.`id` = t.`assignee_user_id`
SET t.`original_assignee_user_id` = t.`assignee_user_id`,
    t.`task_team_id` = u.`team_id`
WHERE t.`original_assignee_user_id` IS NULL;

ALTER TABLE `crm_task`
  MODIFY COLUMN `assignee_user_id` BIGINT UNSIGNED NULL COMMENT '当前负责人；OPEN任务池可为空' AFTER `customer_id`;

CREATE TABLE IF NOT EXISTS `crm_task_action_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `tenant_id` BIGINT UNSIGNED NOT NULL COMMENT '企业/租户隔离',
  `task_id` BIGINT UNSIGNED NOT NULL COMMENT '任务',
  `action` VARCHAR(32) NOT NULL COMMENT 'CLAIM/REASSIGN/RETURN/COMPLETE',
  `from_status` VARCHAR(32) NOT NULL COMMENT '动作前状态',
  `to_status` VARCHAR(32) NOT NULL COMMENT '动作后状态',
  `from_assignee_user_id` BIGINT UNSIGNED NULL COMMENT '动作前负责人',
  `to_assignee_user_id` BIGINT UNSIGNED NULL COMMENT '动作后负责人',
  `actor_user_id` BIGINT UNSIGNED NOT NULL COMMENT '操作人',
  `reason` VARCHAR(500) NULL COMMENT '转派/退回必填原因',
  `idempotency_key` VARCHAR(128) NOT NULL COMMENT '动作幂等键',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '发生时间，UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_action_idempotency` (`tenant_id`,`task_id`,`action`,`idempotency_key`),
  KEY `idx_task_action_task` (`tenant_id`,`task_id`,`id`),
  KEY `idx_task_action_actor` (`tenant_id`,`actor_user_id`,`created_at`),
  CONSTRAINT `fk_task_action_task` FOREIGN KEY (`task_id`) REFERENCES `crm_task` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_task_action_from_assignee` FOREIGN KEY (`from_assignee_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_task_action_to_assignee` FOREIGN KEY (`to_assignee_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_task_action_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='任务接手/转派/退回/完成只追加日志';

CREATE TRIGGER `trg_task_action_no_update` BEFORE UPDATE ON `crm_task_action_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task action log is append only';

CREATE TRIGGER `trg_task_action_no_delete` BEFORE DELETE ON `crm_task_action_log`
FOR EACH ROW
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task action log is append only';

CREATE TRIGGER `trg_task_original_assignee_immutable` BEFORE UPDATE ON `crm_task`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`original_assignee_user_id` <=> NEW.`original_assignee_user_id`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Task original assignee is immutable';
  END IF;
END;
