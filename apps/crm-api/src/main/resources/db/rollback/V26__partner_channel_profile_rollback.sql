-- 仅在完成备份并获批准的下线窗口执行；删除后无法从旧列恢复渠道新增资料。
ALTER TABLE `crm_partner_channel`
  DROP KEY `idx_partner_channel_cooperation`,
  DROP CONSTRAINT `chk_partner_channel_commission`,
  DROP CONSTRAINT `chk_partner_channel_dates`,
  DROP COLUMN `commission_percent`,
  DROP COLUMN `real_name`,
  DROP COLUMN `contact_phone_cipher`,
  DROP COLUMN `id_card_number_cipher`,
  DROP COLUMN `settlement_method`,
  DROP COLUMN `cooperation_start_date`,
  DROP COLUMN `cooperation_end_date`,
  DROP COLUMN `payment_account_name_cipher`,
  DROP COLUMN `payment_account_no_cipher`,
  DROP COLUMN `payment_bank_name`;
