-- P0-1 开发环境认证/RBAC 种子（幂等，可重复执行；仅 local profile 加载，不进入生产库）。
-- 角色→权限映射依据 docs/09_研发实施规范/91_列表CRUD与字段级权限矩阵.md 与 docs/01_需求与产品/12_角色权限与数据隔离.md。
-- 所有开发账号密码统一为 Dev@12345（BCrypt），仅供本地开发，禁止在任何正式环境复用。
-- Development credentials only. Never reuse these accounts or this password outside local development.

-- ========== 组织与团队 ==========
INSERT INTO sys_org (id,tenant_id,org_code,org_name,status) VALUES (1,1,'WHALE','鲸选美学','ACTIVE')
  ON DUPLICATE KEY UPDATE org_name=VALUES(org_name),status=VALUES(status);
INSERT INTO sys_team (id,tenant_id,org_id,team_name,leader_user_id,status) VALUES (1,1,1,'管家一组',4,'ACTIVE')
  ON DUPLICATE KEY UPDATE team_name=VALUES(team_name),leader_user_id=VALUES(leader_user_id),status=VALUES(status);
INSERT INTO sys_team (id,tenant_id,org_id,team_name,leader_user_id,status) VALUES (2,1,1,'管家二组',NULL,'ACTIVE')
  ON DUPLICATE KEY UPDATE team_name=VALUES(team_name),leader_user_id=VALUES(leader_user_id),status=VALUES(status);

-- ========== 用户（密码均为 Dev@12345 的 BCrypt 哈希） ==========
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (1,1,1,NULL,'admin','系统管理员','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (2,1,1,1,'manager1','王丽','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),team_id=VALUES(team_id),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (3,1,1,2,'manager2','李强','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),team_id=VALUES(team_id),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (4,1,1,1,'lead1','张敏','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),team_id=VALUES(team_id),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (5,1,1,NULL,'finance1','赵钱','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (6,1,1,NULL,'business1','孙周','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (7,1,1,NULL,'owner1','经营负责人','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (8,1,1,NULL,'inst1','机构前台','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','INSTITUTION','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (9,1,1,2,'lead2','陈静','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','EMPLOYEE','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),team_id=VALUES(team_id),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (10,1,1,NULL,'operator1','机构一操作员','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','INSTITUTION','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);
INSERT INTO sys_user (id,tenant_id,org_id,team_id,username,display_name,password_hash,user_type,status) VALUES
  (11,1,1,NULL,'operator2','机构二操作员','$2y$10$bA7nD4dAyv5R2OVlUo69k.mU1UkiSPlZV7jvAKKrPp8IMNAQuA7Ri','INSTITUTION','ACTIVE')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),user_type=VALUES(user_type),status=VALUES(status);

-- ========== 角色 ==========
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

-- ========== 权限目录（含 AI 任务/供应商/知识配置发布、有赞连接与客户合并更正） ==========
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

-- ========== 角色→权限映射 ==========
-- ADMIN = 全部权限
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r CROSS JOIN sys_permission p
  WHERE r.tenant_id=1 AND p.tenant_id=1 AND r.role_code='ADMIN';
-- OWNER = 所有 *:read（含 report:read / export:read）
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='OWNER' AND p.permission_code LIKE '%:read';
-- MANAGER_LEAD = 管家主管
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='MANAGER_LEAD' AND p.permission_code IN (
    'customer:read','customer:write','customer:transfer','customer:merge','customer:points_adjust',
    'followup:read','followup:write','task:read','task:write',
    'order:read','order:write','order:confirm_payment','entitlement:read',
    'appointment:read','appointment:write','redemption:read','redemption:submit','redemption:reverse',
    'refund:read','refund:write','institution:read','product:read','dictionary:read',
    'report:read','export:create','export:read','wecom:read','wecom:monitor','ai:read','ai:write','ai:confirm');
-- MANAGER = 管家
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='MANAGER' AND p.permission_code IN (
    'customer:read','customer:write','followup:read','followup:write','task:read','task:write',
    'product:read','institution:read','dictionary:read','order:read','order:write','entitlement:read',
    'appointment:read','appointment:write','redemption:read','redemption:submit','redemption:reverse',
    'refund:read','refund:write','material:read','report:read','wecom:read','ai:read','ai:write','ai:confirm');
-- BUSINESS = 商务运营
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='BUSINESS' AND p.permission_code IN (
    'institution:read','institution:write','institution:change_request','institution:approve_market',
    'product:read','product:write','campaign:read','campaign:write','channel:read','channel:write',
    'material:read','material:write','material:review','dictionary:read','order:read','report:read');
-- FINANCE = 财务
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='FINANCE' AND p.permission_code IN (
    'refund:read','refund:approve','refund:execute','settlement:read','settlement:write',
    'institution:read','institution:approve_finance','order:read','order:confirm_payment',
    'customer:read','dictionary:read','report:read','audit:read','redemption:approve','redemption:read');
-- INSTITUTION_STAFF = 机构操作员
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='INSTITUTION_STAFF' AND p.permission_code IN (
    'appointment:read','appointment:confirm','redemption:read','redemption:post','redemption:reverse',
    'institution:read','institution:change_request','product:read','dictionary:read');
-- INSTITUTION_FINANCE = 机构财务
INSERT IGNORE INTO sys_role_permission (tenant_id,role_id,permission_id)
  SELECT 1,r.id,p.id FROM sys_role r JOIN sys_permission p ON p.tenant_id=r.tenant_id
  WHERE r.tenant_id=1 AND r.role_code='INSTITUTION_FINANCE' AND p.permission_code IN (
    'settlement:read','redemption:read','institution:read','dictionary:read');

-- ========== 用户→角色 ==========
INSERT IGNORE INTO sys_user_role (tenant_id,user_id,role_id)
  SELECT 1,u.id,r.id FROM sys_user u JOIN sys_role r ON r.tenant_id=u.tenant_id
  WHERE u.tenant_id=1 AND ((u.username='admin' AND r.role_code='ADMIN')
    OR (u.username='manager1' AND r.role_code='MANAGER')
    OR (u.username='manager2' AND r.role_code='MANAGER')
    OR (u.username='lead1' AND r.role_code='MANAGER_LEAD')
    OR (u.username='lead2' AND r.role_code='MANAGER_LEAD')
    OR (u.username='finance1' AND r.role_code='FINANCE')
    OR (u.username='business1' AND r.role_code='BUSINESS')
    OR (u.username='owner1' AND r.role_code='OWNER')
    OR (u.username='inst1' AND r.role_code='INSTITUTION_STAFF')
    OR (u.username='operator1' AND r.role_code='INSTITUTION_STAFF')
    OR (u.username='operator2' AND r.role_code='INSTITUTION_STAFF'));

-- ========== 数据范围 ==========
-- sys_data_scope 无 (tenant_id,user_id,resource) 唯一键，先删后插保证种子幂等。
DELETE ds FROM sys_data_scope ds JOIN sys_user u ON u.id=ds.user_id AND u.tenant_id=ds.tenant_id
  WHERE ds.tenant_id=1 AND u.username IN ('admin','owner1','lead1','lead2','manager1','manager2','finance1','business1');
-- admin / owner1：客户与机构均为 ALL
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,res.resource,'ALL',NULL FROM sys_user u
  JOIN (SELECT 'customer' AS resource UNION ALL SELECT 'institution') res
  WHERE u.tenant_id=1 AND u.username IN ('admin','owner1');
-- 业务/财务/管家机构资料查看范围；客户范围仍分别受原规则限制，不授予 order:write。
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,'institution','ALL',NULL FROM sys_user u
  WHERE u.tenant_id=1 AND u.username IN ('business1','finance1','lead1','lead2','manager1','manager2');
-- finance1：仅客户 ALL，用于财务确认支付；不授予 order:write。
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,'customer','ALL',NULL FROM sys_user u WHERE u.tenant_id=1 AND u.username='finance1';
-- lead1：客户 TEAM（管家一组，team_id=1）
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,'customer','TEAM',1 FROM sys_user u WHERE u.tenant_id=1 AND u.username='lead1';
-- lead2：客户 TEAM（管家二组，team_id=2）
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,'customer','TEAM',2 FROM sys_user u WHERE u.tenant_id=1 AND u.username='lead2';
-- manager1 / manager2：客户 SELF（仅本人 owner_staff_id）
INSERT INTO sys_data_scope (tenant_id,user_id,resource,scope_type,scope_ref_id)
  SELECT 1,u.id,'customer','SELF',NULL FROM sys_user u WHERE u.tenant_id=1 AND u.username IN ('manager1','manager2');
-- 机构用户的数据范围不落在 sys_data_scope：由 crm_institution_user 映射决定（INSTITUTION 语义）。

-- ========== 机构账号映射 ==========
-- 仅开发：inst1 保留前台；operator1/operator2 分别用于同机构OPERATOR与跨机构验证。
INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status)
  SELECT 1,1,u.id,'FRONTDESK','ACTIVE' FROM sys_user u WHERE u.tenant_id=1 AND u.username='inst1'
  ON DUPLICATE KEY UPDATE institution_role=VALUES(institution_role),status=VALUES(status);
INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status)
  SELECT 1,1,u.id,'OPERATOR','ACTIVE' FROM sys_user u WHERE u.tenant_id=1 AND u.username='operator1'
  ON DUPLICATE KEY UPDATE institution_role=VALUES(institution_role),status=VALUES(status);
INSERT INTO crm_institution_user (tenant_id,institution_id,user_id,institution_role,status)
  SELECT 1,2,u.id,'OPERATOR','ACTIVE' FROM sys_user u WHERE u.tenant_id=1 AND u.username='operator2'
  ON DUPLICATE KEY UPDATE institution_role=VALUES(institution_role),status=VALUES(status);
