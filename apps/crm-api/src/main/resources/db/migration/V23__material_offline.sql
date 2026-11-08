-- 素材下架只记录主档生命周期，不修改审核状态、终审修订或当前审核版本指针。

ALTER TABLE `crm_material`
  ADD COLUMN `offline_at` DATETIME(3) NULL COMMENT '下架时间，UTC；NULL表示未下架' AFTER `reviewer_id`,
  ADD COLUMN `offline_by` BIGINT UNSIGNED NULL COMMENT '下架操作人' AFTER `offline_at`,
  ADD COLUMN `offline_reason` VARCHAR(500) NULL COMMENT '下架原因，审计快照保留' AFTER `offline_by`,
  ADD KEY `idx_material_offline` (`tenant_id`,`offline_at`,`id`),
  ADD CONSTRAINT `fk_material_offline_by` FOREIGN KEY (`offline_by`) REFERENCES `sys_user` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `chk_material_offline` CHECK (
    (`offline_at` IS NULL AND `offline_by` IS NULL AND `offline_reason` IS NULL)
    OR (`offline_at` IS NOT NULL AND `offline_by` IS NOT NULL AND `offline_reason` IS NOT NULL)
  );
