-- 仅用于开发应急回滚；先备份有赞原始事件、订单映射与异常队列，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_youzan_sync_cursor`;
DROP TABLE IF EXISTS `crm_youzan_sync_issue`;
DROP TABLE IF EXISTS `crm_youzan_customer_link`;
DROP TABLE IF EXISTS `crm_youzan_sku_map`;
DROP TABLE IF EXISTS `crm_youzan_order_map`;
DROP TABLE IF EXISTS `crm_youzan_event`;
