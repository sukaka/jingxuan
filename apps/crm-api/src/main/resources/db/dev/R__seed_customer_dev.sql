-- P0-1b 开发环境客户域种子（明确虚构数据，仅 local profile 加载，禁止用于生产）。
-- 用途：浏览器验收客户中心/查重合并/跟进/任务页面。手机号密文/盲索引用开发占位密钥预生成
-- （app.security.crypto.* 的 dev 默认值），对应虚构号码 138000000xx；严禁写入任何真实手机号。
-- Fictional demo data only. Never load into production; never put real personal data here.

-- ========== 虚构客户（固定 id 1001-1006；customer_no 与线上生成规则一致 C+id 左补零） ==========
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          mobile_cipher, mobile_hash, city_code, source_code, channel_code, customer_status,
                          tier_code, intent_level, spending_level, birthday, points_cached,
                          attention_projects, remark, first_added_at, last_followup_at)
VALUES (1001, 1, 2, 1, 'C0000001001', '沈梦琪', 'Momo酱',
        'pOiox6iqlyjq1lVRR8GwOJ7/1RPz1bvyBU8IG530UXCuTF1yUm6f',
        'ae4867eb811ebbec1a3876826d6df46ceb1f09dc5096d41f76865ec656ea3b23',
        'BJ', 'EXTERNAL_CONTENT', 'XHS', 'ACTIVE', 'A', 'HIGH', 'HIGH', '1996-04-18', 100,
        '热玛吉、超声炮', '虚构演示客户-01', '2026-09-01 10:00:00.000', '2026-10-20 09:30:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), nickname=VALUES(nickname),
        owner_staff_id=VALUES(owner_staff_id), team_id=VALUES(team_id), points_cached=VALUES(points_cached),
        mobile_cipher=VALUES(mobile_cipher), mobile_hash=VALUES(mobile_hash);
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          mobile_cipher, mobile_hash, city_code, source_code, channel_code, customer_status,
                          tier_code, intent_level, spending_level, birthday, attention_projects, remark,
                          first_added_at)
VALUES (1002, 1, 2, 1, 'C0000001002', '顾晓萌', '萌萌',
        '+c9zJoV390x2292b4HE4qfwveTZTwftHx7qquMfsyTpbnbOxo5cj',
        'c47125cadf8749ceeb6d89230399626c149aae1bb1baca3fbd9caed3cc899110',
        'SH', 'OWN_CONTENT', 'WECHAT_CHANNEL', 'POTENTIAL', 'S', 'MEDIUM', 'MEDIUM', '1999-11-02',
        '光子嫩肤', '虚构演示客户-02', '2026-09-15 14:00:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), nickname=VALUES(nickname),
        owner_staff_id=VALUES(owner_staff_id), team_id=VALUES(team_id),
        mobile_cipher=VALUES(mobile_cipher), mobile_hash=VALUES(mobile_hash);
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          mobile_cipher, mobile_hash, city_code, source_code, channel_code, customer_status,
                          tier_code, intent_level, attention_projects, remark, first_added_at)
VALUES (1003, 1, 3, 2, 'C0000001003', '林婉清', '清清',
        '22CmATHBOGd7KD3ZL9IV+uReKQM4u6lvULS6Of3C1XyxhGRdoYgN',
        'b24db81e7d9147a3249e7b0381a3c45ce49943d04772237f57dfe655f8876d74',
        'HZ', 'REFERRAL', 'WEIBO', 'ACTIVE', 'B', 'HIGH', '水光针', '虚构演示客户-03',
        '2026-08-20 11:00:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), nickname=VALUES(nickname),
        owner_staff_id=VALUES(owner_staff_id), team_id=VALUES(team_id),
        mobile_cipher=VALUES(mobile_cipher), mobile_hash=VALUES(mobile_hash);
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          city_code, source_code, channel_code, customer_status, tier_code, intent_level,
                          attention_projects, remark, first_added_at)
VALUES (1004, 1, 3, 2, 'C0000001004', '苏倩', NULL,
        'GZ', 'DIRECT', 'OFFLINE', 'POTENTIAL', 'C', 'LOW',
        NULL, '虚构演示客户-04（无手机号，C01 场景）', '2026-10-10 16:00:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), owner_staff_id=VALUES(owner_staff_id),
        team_id=VALUES(team_id);
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          mobile_cipher, mobile_hash, city_code, source_code, channel_code, customer_status,
                          tier_code, intent_level, attention_projects, remark, first_added_at)
VALUES (1005, 1, 4, 1, 'C0000001005', '何静姝', '静姝',
        'SG+79dO9qVd1Cu8GVDtLEeoEZeKfedOW2wOn1HyO9enI8Rk4XJAA',
        '54efb4a603f5405a24cb05b9e0a0cd05c25913edf5af238706e87ddf40c5dcfc',
        'CD', 'DIRECT', 'YOUZAN', 'ACTIVE', 'B', 'MEDIUM', '双眼皮修复', '虚构演示客户-05',
        '2026-07-05 09:00:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), nickname=VALUES(nickname),
        owner_staff_id=VALUES(owner_staff_id), team_id=VALUES(team_id),
        mobile_cipher=VALUES(mobile_cipher), mobile_hash=VALUES(mobile_hash);
INSERT INTO crm_customer (id, tenant_id, owner_staff_id, team_id, customer_no, display_name, nickname,
                          mobile_cipher, mobile_hash, city_code, source_code, channel_code, customer_status,
                          tier_code, intent_level, attention_projects, remark, first_added_at)
VALUES (1006, 1, 4, 1, 'C0000001006', '赵云舒', '云舒',
        'uORrLgl49UzkXR7GNOPpoFOI/Xh0CiEQyC8S5+4PzteBUScgfgsJ',
        'a53a9c1f414c288a3ef98d99b707d910da6472913247b7cff595f6ef8ae736d7',
        'BJ', 'EXTERNAL_CONTENT', 'XHS', 'AWAITING_VISIT', 'A', 'HIGH', '隆鼻面诊', '虚构演示客户-06',
        '2026-10-01 13:30:00.000')
  ON DUPLICATE KEY UPDATE display_name=VALUES(display_name), nickname=VALUES(nickname),
        owner_staff_id=VALUES(owner_staff_id), team_id=VALUES(team_id),
        mobile_cipher=VALUES(mobile_cipher), mobile_hash=VALUES(mobile_hash);

-- ========== 标签 ==========
INSERT IGNORE INTO crm_customer_tag (tenant_id, customer_id, tag_code, created_by) VALUES
  (1, 1001, 'VIP', 2), (1, 1001, 'HIGH_INTENT', 2),
  (1, 1002, 'NEW_7D', 2), (1, 1003, 'REFERRAL_GOOD', 3), (1, 1006, 'HIGH_INTENT', 4);

-- ========== 跟进流水（1001 一条；1002 一条带下次跟进时间，供 followupDueBefore 筛选演示） ==========
INSERT INTO crm_followup (id, tenant_id, customer_id, owner_staff_id, channel_code, contact_at, summary,
                          intent_project, estimated_amount_fen, next_followup_at, created_by)
VALUES (9001, 1, 1001, 2, 'WECOM', '2026-10-20 09:30:00.000', '确认到院意向，关心恢复期', '热玛吉', 2980000,
        '2026-10-27 10:00:00.000', 2)
  ON DUPLICATE KEY UPDATE summary=VALUES(summary);
INSERT INTO crm_followup (id, tenant_id, customer_id, owner_staff_id, channel_code, contact_at, summary,
                          intent_project, estimated_amount_fen, next_followup_at, created_by)
VALUES (9002, 1, 1002, 2, 'PHONE', '2026-10-21 15:00:00.000', '价格敏感，发案例对比图', '光子嫩肤', 120000,
        '2026-10-25 11:00:00.000', 2)
  ON DUPLICATE KEY UPDATE summary=VALUES(summary);
UPDATE crm_customer SET last_followup_at='2026-10-21 15:00:00.000' WHERE tenant_id=1 AND id=1002 AND deleted=0;

-- ========== 任务（演示每日跟进页） ==========
INSERT INTO crm_task (id, tenant_id, customer_id, assignee_user_id, task_type, priority, status, title,
                      description, due_at, source_type)
VALUES (9001, 1, 1001, 2, 'FOLLOWUP', 'HIGH', 'OPEN', '回访：热玛吉术后第 3 天', '虚构演示任务',
        '2026-10-24 18:00:00.000', 'MANUAL')
  ON DUPLICATE KEY UPDATE title=VALUES(title);
INSERT INTO crm_task (id, tenant_id, customer_id, assignee_user_id, task_type, priority, status, title,
                      description, due_at, source_type)
VALUES (9002, 1, 1006, 4, 'APPOINTMENT', 'MEDIUM', 'OPEN', '确认隆鼻面诊到院时间', '虚构演示任务',
        '2026-10-26 12:00:00.000', 'MANUAL')
  ON DUPLICATE KEY UPDATE title=VALUES(title);

-- ========== 积分流水（账本为准；points_cached 与 SUM(delta) 一致） ==========
INSERT IGNORE INTO crm_points_ledger (tenant_id, customer_id, delta, reason_code, source_type, source_ref,
                                      operator_user_id)
VALUES (1, 1001, 100, 'WELCOME', 'MANUAL_ADJUST', 'SEED-DEMO-1001', 4);
UPDATE crm_customer c
  JOIN (SELECT customer_id, COALESCE(SUM(delta),0) bal FROM crm_points_ledger WHERE tenant_id=1
        GROUP BY customer_id) l ON l.customer_id = c.id
  SET c.points_cached = l.bal
WHERE c.tenant_id=1 AND c.id BETWEEN 1001 AND 1006;
