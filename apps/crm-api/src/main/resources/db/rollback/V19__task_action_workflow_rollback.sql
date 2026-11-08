-- 仅用于开发应急回滚；先备份V19动作日志并停止任务写入，禁止在生产直接执行。
-- 已退回任务池的记录必须先恢复一个负责人，才能回到V17的非空结构；这里使用首次负责人。

DROP TRIGGER IF EXISTS `trg_task_original_assignee_immutable`;
DROP TRIGGER IF EXISTS `trg_task_action_no_update`;
DROP TRIGGER IF EXISTS `trg_task_action_no_delete`;
DROP TABLE IF EXISTS `crm_task_action_log`;

UPDATE `crm_task`
SET `assignee_user_id` = `original_assignee_user_id`
WHERE `assignee_user_id` IS NULL;

ALTER TABLE `crm_task`
  DROP FOREIGN KEY `fk_task_original_assignee`,
  DROP FOREIGN KEY `fk_task_team`;
ALTER TABLE `crm_task`
  DROP KEY `idx_task_team_pool`,
  DROP KEY `idx_task_original_assignee`;
ALTER TABLE `crm_task`
  MODIFY COLUMN `assignee_user_id` BIGINT UNSIGNED NOT NULL COMMENT '负责人' AFTER `customer_id`,
  DROP COLUMN `completed_at`,
  DROP COLUMN `claimed_at`,
  DROP COLUMN `task_team_id`,
  DROP COLUMN `original_assignee_user_id`;
