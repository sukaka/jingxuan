-- 仅用于未投入生产前的显式回滚；已产生订单选择快照时必须在任何业务 DROP 之前中止。
DELIMITER $$
DROP PROCEDURE IF EXISTS `v34_package_selection_rollback_guard`$$
CREATE PROCEDURE `v34_package_selection_rollback_guard`()
BEGIN
  IF EXISTS (SELECT 1 FROM `crm_order_item_selection` LIMIT 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V34 rollback blocked: order item selection snapshots exist';
  END IF;
END$$
CALL `v34_package_selection_rollback_guard`()$$
DROP PROCEDURE IF EXISTS `v34_package_selection_rollback_guard`$$
DELIMITER ;

DROP TRIGGER IF EXISTS `trg_order_item_selection_no_delete`;
DROP TRIGGER IF EXISTS `trg_order_item_selection_no_update`;
DROP TABLE IF EXISTS `crm_order_item_selection`;
DROP TABLE IF EXISTS `crm_package_selection_group`;
ALTER TABLE `crm_package_project` DROP COLUMN `reference_price_fen`;
