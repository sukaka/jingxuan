-- V64 回滚：删除有赞客户拉取同步三张表（不影响已创建的 CRM 正式客户、身份与链接记录）。

DROP TABLE IF EXISTS `crm_youzan_customer_sync_item`;
DROP TABLE IF EXISTS `crm_youzan_customer_sync_run`;
DROP TABLE IF EXISTS `crm_youzan_customer_sync_cursor`;
