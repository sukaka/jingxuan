-- 仅用于开发应急回滚；先备份V21任务、文档映射和配置并停止发布，禁止在生产直接执行。
-- 只移除知识发布增量，不删除素材修订、文件或其他AI配置。

DROP TABLE IF EXISTS `crm_knowledge_sync_job`;
DROP TABLE IF EXISTS `crm_knowledge_document`;

ALTER TABLE `crm_dify_knowledge_config`
  DROP COLUMN `sync_enabled`,
  DROP COLUMN `dataset_api_key_cipher`,
  DROP COLUMN `dataset_id`;

DELETE rp FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id`=rp.`tenant_id` AND p.`id`=rp.`permission_id`
WHERE p.`permission_code` IN ('ai:knowledge:read','ai:knowledge:publish');

DELETE FROM `sys_permission` WHERE `permission_code` IN ('ai:knowledge:read','ai:knowledge:publish');
