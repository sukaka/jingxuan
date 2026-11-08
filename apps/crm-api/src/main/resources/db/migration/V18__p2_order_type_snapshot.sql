-- P2 D01：仅保存新订单创建时的订单类型标签；V17及更早订单不回填、不猜测历史标签。
ALTER TABLE `crm_order`
  ADD COLUMN `order_type_label_snapshot` VARCHAR(128) NULL COMMENT '创建时订单类型标签快照，不回填历史，不随字典改名覆盖' AFTER `order_type_code`;

CREATE TRIGGER `trg_order_type_label_snapshot_no_update` BEFORE UPDATE ON `crm_order`
FOR EACH ROW
BEGIN
  IF NOT (OLD.`order_type_label_snapshot` <=> NEW.`order_type_label_snapshot`) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Order type label snapshot is immutable';
  END IF;
END;
