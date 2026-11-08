-- 仅用于开发应急回滚；先备份V20知识库配置并停止相关写入，禁止在生产直接执行。
-- 只移除知识问答配置与专属权限，不影响其他AI供应商配置或历史业务数据。

DROP TABLE IF EXISTS `crm_dify_knowledge_config`;

DELETE rp FROM `sys_role_permission` rp
JOIN `sys_permission` p ON p.`tenant_id`=rp.`tenant_id` AND p.`id`=rp.`permission_id`
WHERE p.`permission_code`='ai:knowledge:config';

DELETE FROM `sys_permission` WHERE `permission_code`='ai:knowledge:config';
