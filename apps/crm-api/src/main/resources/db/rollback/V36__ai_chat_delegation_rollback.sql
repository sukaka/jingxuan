DELIMITER $$
DROP PROCEDURE IF EXISTS `v36_ai_delegation_rollback_guard`$$
CREATE PROCEDURE `v36_ai_delegation_rollback_guard`()
BEGIN
  IF (SELECT COUNT(*) FROM `crm_ai_chat_delegation_run`) + (SELECT COUNT(*) FROM `crm_ai_chat_delegation_attempt`) > 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI delegation history exists; rollback refused';
  END IF;
END$$
CALL `v36_ai_delegation_rollback_guard`()$$
DROP PROCEDURE IF EXISTS `v36_ai_delegation_rollback_guard`$$
DELIMITER ;

DROP TRIGGER IF EXISTS `trg_ai_delegation_attempt_no_update`;
DROP TRIGGER IF EXISTS `trg_ai_delegation_attempt_no_delete`;
DROP TABLE IF EXISTS `crm_ai_chat_delegation_attempt`;
DROP TABLE IF EXISTS `crm_ai_chat_delegation_run`;
DROP TABLE IF EXISTS `crm_ai_chat_delegation_customer`;
DROP TABLE IF EXISTS `crm_ai_chat_delegation_scope`;
DROP TABLE IF EXISTS `crm_ai_chat_delegation_policy`;
