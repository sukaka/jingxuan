-- 仅用于未投入生产前的显式回滚；已产生操作批次时不得执行破坏性回滚。
DROP TRIGGER IF EXISTS `trg_customer_segment_member_no_delete`;
DROP TRIGGER IF EXISTS `trg_customer_segment_member_no_update`;
DROP TRIGGER IF EXISTS `trg_customer_segment_batch_no_delete`;
DROP TRIGGER IF EXISTS `trg_customer_segment_batch_no_update`;
DROP TABLE IF EXISTS `crm_customer_segment_batch_member`;
DROP TABLE IF EXISTS `crm_customer_segment_batch`;
DROP TABLE IF EXISTS `crm_customer_segment`;
DROP TABLE IF EXISTS `crm_customer_attention_project`;
-- crm_customer.attention_projects 为既有 legacy 列，本迁移未修改，回滚不得删除。
