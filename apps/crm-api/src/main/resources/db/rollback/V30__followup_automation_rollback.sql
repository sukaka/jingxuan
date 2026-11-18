-- V30 回滚：仅删除本模块新增结构，不改写订单、任务或跟进历史事实。
DROP TRIGGER IF EXISTS `trg_followup_attempt_no_update`;
DROP TRIGGER IF EXISTS `trg_followup_attempt_no_delete`;
ALTER TABLE `crm_task` DROP KEY `idx_task_followup_due`;
ALTER TABLE `crm_order` DROP KEY `idx_order_followup_candidate`;
DROP TABLE IF EXISTS `crm_followup_schedule_job`;
DROP TABLE IF EXISTS `crm_followup_batch_attempt`;
DROP TABLE IF EXISTS `crm_followup_batch_order`;
DROP TABLE IF EXISTS `crm_followup_batch_item`;
DROP TABLE IF EXISTS `crm_followup_batch`;
DROP TABLE IF EXISTS `crm_followup_intent_policy`;
DROP TABLE IF EXISTS `crm_followup_policy`;
