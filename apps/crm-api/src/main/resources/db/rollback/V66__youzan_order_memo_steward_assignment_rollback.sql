-- V66 回滚脚本：仅移除有赞订单备注解析与管家分配辅助表，不影响已存在的 CRM 订单、客户与权益账本。
DROP TABLE IF EXISTS `crm_youzan_order_assignment`;
DROP TABLE IF EXISTS `crm_youzan_steward_alias`;
