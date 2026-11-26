-- 受控回滚 V60__youzan_annual_snapshot_and_c_auth.sql：已产生有赞快照、绑定或访问历史时阻断 DROP。
DELIMITER $$
DROP PROCEDURE IF EXISTS `v60_youzan_annual_rollback_guard`$$
CREATE PROCEDURE `v60_youzan_annual_rollback_guard`()
BEGIN
  DECLARE v_count BIGINT DEFAULT 0;

  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_youzan_annual_order_snapshot') THEN
    SELECT v_count + COUNT(*) INTO v_count FROM `crm_youzan_annual_order_snapshot`;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_youzan_annual_profile') THEN
    SELECT v_count + COUNT(*) INTO v_count FROM `crm_youzan_annual_profile`;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_h5_c_identity_binding') THEN
    SELECT v_count + COUNT(*) INTO v_count FROM `crm_h5_c_identity_binding`;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_h5_c_visit_session') THEN
    SELECT v_count + COUNT(*) INTO v_count FROM `crm_h5_c_visit_session`;
  END IF;

  IF v_count > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V60 rollback blocked: youzan annual snapshot or c-end identity history is non-empty';
  END IF;
END$$
CALL `v60_youzan_annual_rollback_guard`()$$
DROP PROCEDURE IF EXISTS `v60_youzan_annual_rollback_guard`$$
DELIMITER ;

DROP TABLE IF EXISTS `crm_h5_c_visit_session`;
DROP TABLE IF EXISTS `crm_h5_sms_verification`;
DROP TABLE IF EXISTS `crm_h5_c_identity_binding`;
DROP TABLE IF EXISTS `crm_youzan_annual_order_snapshot`;
DROP TABLE IF EXISTS `crm_youzan_annual_profile`;
