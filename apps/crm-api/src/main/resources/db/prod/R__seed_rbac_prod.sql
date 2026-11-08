-- 首次安装所需的组织、角色和权限目录；不包含任何登录账号或开发密码。
-- 管理员密码只能由 BootstrapAdminCommand 单独初始化，不在数据库迁移中保存。
INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (1,1,'WHALE','鲸选美学','ACTIVE')
  ON DUPLICATE KEY UPDATE org_name=VALUES(org_name),status=VALUES(status);

INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (1,1,'ADMIN','系统管理员','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (2,1,'OWNER','经营负责人','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (3,1,'MANAGER_LEAD','管家主管','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (4,1,'MANAGER','管家','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (5,1,'BUSINESS','商务运营','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (6,1,'FINANCE','财务','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (7,1,'INSTITUTION_STAFF','机构操作员','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);
INSERT INTO sys_role (id,tenant_id,role_code,role_name,status) VALUES (8,1,'INSTITUTION_FINANCE','机构财务','ACTIVE')
  ON DUPLICATE KEY UPDATE role_name=VALUES(role_name),status=VALUES(status);

INSERT INTO sys_permission (tenant_id,permission_code,description) VALUES
  (1,'customer:read','客户查看'),(1,'customer:write','客户新建/编辑'),(1,'customer:transfer','客户转交'),(1,'customer:merge','客户合并审批'),
  (1,'customer:points_adjust','客户积分调整（追加流水）'),
  (1,'followup:read','跟进查看'),(1,'followup:write','跟进写'),
  (1,'task:read','任务查看'),(1,'task:write','任务写'),
  (1,'dictionary:read','字典读取'),(1,'dictionary:write','字典维护'),
  (1,'product:read','产品查看'),(1,'product:write','产品维护'),
  (1,'order:read','订单查看'),(1,'order:write','订单新建/编辑'),(1,'order:confirm_payment','订单收款确认'),
  (1,'entitlement:read','权益查看'),
  (1,'appointment:read','预约查看'),(1,'appointment:write','预约新建/编辑'),(1,'appointment:confirm','机构确认接诊/到院'),
  (1,'redemption:read','核销查看'),(1,'redemption:submit','核销申请'),(1,'redemption:post','机构最终核销'),(1,'redemption:reverse','核销冲正'),
  (1,'redemption:approve','核销冲正审核'),
  (1,'refund:read','退款查看'),(1,'refund:write','退款申请'),(1,'refund:approve','退款审批'),(1,'refund:execute','退款执行'),
  (1,'institution:read','机构查看'),(1,'institution:write','机构资料维护'),(1,'institution:change_request','机构敏感变更申请'),
  (1,'institution:approve_market','机构变更市场审批'),(1,'institution:approve_finance','机构变更财务审批'),
  (1,'settlement:read','结算查看'),(1,'settlement:write','结算操作'),
  (1,'material:read','素材查看'),(1,'material:write','素材维护'),(1,'material:review','素材审核'),
  (1,'campaign:read','活动查看'),(1,'campaign:write','活动维护'),
  (1,'channel:read','渠道查看'),(1,'channel:write','渠道维护'),
  (1,'report:read','经营报表查看'),
  (1,'export:read','导出记录查看'),(1,'export:create','创建导出任务'),
  (1,'audit:read','审计日志查看'),
  (1,'user:read','用户/角色查看'),(1,'user:write','用户/角色管理'),
  (1,'wecom:read','企微会话查看'),(1,'wecom:monitor','会话监管'),
  (1,'ai:read','AI 结果查看'),(1,'ai:write','AI 抽取任务创建'),(1,'ai:confirm','AI 结果人工确认'),(1,'ai:config','AI 供应商配置管理'),
  (1,'ai:knowledge:config','Dify 知识问答配置管理'),
  (1,'ai:knowledge:read','知识发布状态查看'),(1,'ai:knowledge:publish','知识资料发布与停用'),
  (1,'risk:read','风控台账查看'),(1,'risk:write','风控处理'),
  (1,'integration:youzan:manage','有赞店铺授权与同步配置'),
  (1,'integration:runtime:manage','外部集成全局运行开关管理'),
  (1,'customer:merge_reverse','客户合并更正审批与执行')
  ON DUPLICATE KEY UPDATE description=VALUES(description);

INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
  WHERE r.tenant_id=1 AND p.tenant_id=1 AND r.role_code='ADMIN';
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='OWNER' AND p.permission_code LIKE '%:read';
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='MANAGER_LEAD' AND p.permission_code IN (
    'customer:read','customer:write','customer:transfer','customer:merge','customer:points_adjust',
    'followup:read','followup:write','task:read','task:write',
    'order:read','order:write','order:confirm_payment','entitlement:read',
    'appointment:read','appointment:write','redemption:read','redemption:submit','redemption:reverse',
    'refund:read','refund:write','institution:read','product:read','dictionary:read',
    'report:read','export:create','export:read','wecom:read','wecom:monitor','ai:read','ai:write','ai:confirm');
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='MANAGER' AND p.permission_code IN (
    'customer:read','customer:write','followup:read','followup:write','task:read','task:write',
    'product:read','institution:read','dictionary:read','order:read','order:write','entitlement:read',
    'appointment:read','appointment:write','redemption:read','redemption:submit','redemption:reverse',
    'refund:read','refund:write','material:read','report:read','wecom:read','ai:read','ai:write','ai:confirm');
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='BUSINESS' AND p.permission_code IN (
    'institution:read','institution:write','institution:change_request','institution:approve_market',
    'product:read','product:write','campaign:read','campaign:write','channel:read','channel:write',
    'material:read','material:write','material:review','dictionary:read','order:read','report:read');
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='FINANCE' AND p.permission_code IN (
    'refund:read','refund:approve','refund:execute','settlement:read','settlement:write',
    'institution:read','institution:approve_finance','order:read','order:confirm_payment',
    'customer:read','dictionary:read','report:read','audit:read','redemption:approve','redemption:read');
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='INSTITUTION_STAFF' AND p.permission_code IN (
    'appointment:read','appointment:confirm','redemption:read','redemption:post','redemption:reverse',
    'institution:read','institution:change_request','product:read','dictionary:read');
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='INSTITUTION_FINANCE' AND p.permission_code IN (
    'settlement:read','redemption:read','institution:read','dictionary:read');
