-- 受控回滚 V59__annual_report_h5.sql：仅在未产生正式年终快照与访问记录时允许 DROP。
DELIMITER $$
DROP PROCEDURE IF EXISTS `v59_annual_report_h5_rollback_guard`$$
CREATE PROCEDURE `v59_annual_report_h5_rollback_guard`()
BEGIN
  DECLARE v_reports BIGINT DEFAULT 0;
  DECLARE v_sessions BIGINT DEFAULT 0;
  DECLARE v_events BIGINT DEFAULT 0;

  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_annual_report_visit_event') THEN
    SELECT COUNT(*) INTO v_events FROM `crm_annual_report_visit_event`;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_annual_report_visit_session') THEN
    SELECT COUNT(*) INTO v_sessions FROM `crm_annual_report_visit_session`;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'crm_annual_report') THEN
    SELECT COUNT(*) INTO v_reports FROM `crm_annual_report`;
  END IF;

  IF v_reports > 0 OR v_sessions > 0 OR v_events > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V59 rollback blocked: annual report or visit history is non-empty';
  END IF;
END$$
CALL `v59_annual_report_h5_rollback_guard`()$$
DROP PROCEDURE IF EXISTS `v59_annual_report_h5_rollback_guard`$$
DELIMITER ;

DROP TRIGGER IF EXISTS `trg_annual_visit_event_no_delete`;
DROP TRIGGER IF EXISTS `trg_annual_visit_event_no_update`;
DROP TRIGGER IF EXISTS `trg_annual_report_snapshot_guard`;

DROP TABLE IF EXISTS `crm_annual_report_visit_event`;
DROP TABLE IF EXISTS `crm_annual_report_visit_session`;
DROP TABLE IF EXISTS `crm_annual_report`;
