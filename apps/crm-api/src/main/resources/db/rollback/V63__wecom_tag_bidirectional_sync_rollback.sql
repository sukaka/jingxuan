-- V63 回滚：删除企微标签双向同步三张表（不影响 CRM 客户历史标签与字典项）。

DROP TABLE IF EXISTS `crm_wecom_customer_tag_push_task`;
DROP TABLE IF EXISTS `crm_wecom_tag_sync_run`;
DROP TABLE IF EXISTS `crm_wecom_tag_mapping`;
