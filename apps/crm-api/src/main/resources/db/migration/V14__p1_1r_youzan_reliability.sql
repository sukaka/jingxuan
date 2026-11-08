-- P1-1R 有赞同步店铺级租约；任务历史仍保留在 crm_youzan_sync_job。
ALTER TABLE `crm_youzan_shop_config`
  ADD COLUMN `lease_owner` VARCHAR(96) NULL COMMENT '当前同步执行实例' AFTER `sync_enabled`,
  ADD COLUMN `lease_expires_at` DATETIME(3) NULL COMMENT '同步租约到期时间，UTC' AFTER `lease_owner`,
  ADD KEY `idx_youzan_shop_lease_1` (`tenant_id`,`shop_id`,`lease_expires_at`);
