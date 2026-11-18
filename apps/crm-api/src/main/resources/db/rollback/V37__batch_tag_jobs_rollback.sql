-- 仅用于未投入生产前的显式回滚；已有任务或预览历史时不得执行破坏性回滚。
DROP TRIGGER IF EXISTS `trg_batch_tag_job_attempt_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_job_attempt_no_update`;
DROP TRIGGER IF EXISTS `trg_batch_tag_item_attempt_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_item_attempt_no_update`;
DROP TRIGGER IF EXISTS `trg_batch_tag_item_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_item_snapshot`;
DROP TRIGGER IF EXISTS `trg_batch_tag_job_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_job_snapshot`;
DROP TRIGGER IF EXISTS `trg_batch_tag_preview_member_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_preview_member_no_update`;
DROP TRIGGER IF EXISTS `trg_batch_tag_preview_no_delete`;
DROP TRIGGER IF EXISTS `trg_batch_tag_preview_no_update`;
DROP TABLE IF EXISTS `crm_batch_tag_job_attempt`;
DROP TABLE IF EXISTS `crm_batch_tag_item_attempt`;
DROP TABLE IF EXISTS `crm_batch_tag_job_item`;
DROP TABLE IF EXISTS `crm_batch_tag_job`;
DROP TABLE IF EXISTS `crm_batch_tag_preview_member`;
DROP TABLE IF EXISTS `crm_batch_tag_preview`;
-- customerTags 字典及停用历史属于独立治理数据，回滚任务表时不删除。
