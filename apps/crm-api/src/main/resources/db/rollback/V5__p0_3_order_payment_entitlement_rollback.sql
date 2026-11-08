-- 仅用于开发应急回滚；先备份订单、支付和权益账本，禁止在生产直接执行。
DROP TABLE IF EXISTS `crm_order_status_history`;
DROP TABLE IF EXISTS `crm_entitlement_ledger`;
DROP TABLE IF EXISTS `crm_entitlement`;
DROP TABLE IF EXISTS `crm_payment_txn`;
DROP TABLE IF EXISTS `crm_order_item`;
DROP TABLE IF EXISTS `crm_order`;
