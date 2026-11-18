DELIMITER $$
DROP PROCEDURE IF EXISTS `v39_ai_chat_send_fence_rollback_guard`$$
CREATE PROCEDURE `v39_ai_chat_send_fence_rollback_guard`()
BEGIN
  IF EXISTS (SELECT 1 FROM `crm_ai_chat_send_fence` WHERE `in_flight` = 1 LIMIT 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI send fence has in-flight calls; rollback refused';
  END IF;
END$$
CALL `v39_ai_chat_send_fence_rollback_guard`()$$
DROP PROCEDURE IF EXISTS `v39_ai_chat_send_fence_rollback_guard`$$
DELIMITER ;

DROP TABLE IF EXISTS `crm_ai_chat_send_fence`;
