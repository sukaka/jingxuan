-- 仅用于开发应急回滚；先备份下架状态与审计记录并停止相关写入，禁止在生产直接执行。
-- 不删除素材、审核修订、文件或审计历史。

ALTER TABLE `crm_material`
  DROP FOREIGN KEY `fk_material_offline_by`,
  DROP KEY `idx_material_offline`,
  DROP CHECK `chk_material_offline`;

ALTER TABLE `crm_material`
  DROP COLUMN `offline_reason`,
  DROP COLUMN `offline_by`,
  DROP COLUMN `offline_at`;
