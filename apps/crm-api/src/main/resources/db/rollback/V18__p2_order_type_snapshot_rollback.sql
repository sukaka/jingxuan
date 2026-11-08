-- 仅用于开发应急回滚；先备份并停止订单写入，禁止在生产直接执行。
-- 仅移除V18订单类型标签快照及保护触发器，不影响V17及更早历史事实。
DROP TRIGGER IF EXISTS `trg_order_type_label_snapshot_no_update`;
ALTER TABLE `crm_order`
  DROP COLUMN `order_type_label_snapshot`;
