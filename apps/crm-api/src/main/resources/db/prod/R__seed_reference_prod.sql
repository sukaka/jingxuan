-- 核心业务所需的系统参考数据（含国内常用城市），不包含客户、机构、订单等演示数据及客户状态等运营自配项。
-- 重复执行只补齐缺失代码，不覆盖运营已维护的显示名、排序或启停状态。
INSERT INTO sys_dictionary_type (tenant_id,dict_code,dict_name,status) VALUES
  (1,'cities','城市','ACTIVE'),
  (1,'orderTypes','订单类型','ACTIVE'),
  (1,'institutionBusinessTypes','机构业务类型','ACTIVE'),
  (1,'institutionOwnerships','机构性质','ACTIVE'),
  (1,'merchantGrades','商户等级','ACTIVE'),
  (1,'settlementTypes','机构结算方式','ACTIVE'),
  (1,'customerStatuses','客户状态','ACTIVE'),
  (1,'customerSources','客户来源','ACTIVE'),
  (1,'customerChannels','客户渠道','ACTIVE'),
  (1,'communicationChannels','沟通方式','ACTIVE'),
  (1,'taskTypes','任务类型','ACTIVE'),
  (1,'refundReasons','退款原因','ACTIVE'),
  (1,'materialTypes','素材类型','ACTIVE'),
  (1,'customerTiers','客户等级','ACTIVE'),
  (1,'customerTags','客户标签','ACTIVE')
  ON DUPLICATE KEY UPDATE dict_code=VALUES(dict_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'BJ','北京',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SH','上海',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'GZ','广州',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SZ','深圳',4,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HZ','杭州',5,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'CD','成都',6,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'CQ','重庆',7,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'NJ','南京',8,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'WH','武汉',9,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SUZ','苏州',10,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'TJ','天津',11,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'XA','西安',12,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'QD','青岛',13,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'CS','长沙',14,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'ZZ','郑州',15,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'NB','宁波',16,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'WX','无锡',17,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'XM','厦门',18,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'FZ','福州',19,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HF','合肥',20,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'JN','济南',21,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'DL','大连',22,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SY','沈阳',23,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'KM','昆明',24,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'FS','佛山',25,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'DG','东莞',26,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HRB','哈尔滨',27,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'CC','长春',28,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SJZ','石家庄',29,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'TY','太原',30,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'NC','南昌',31,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'NN','南宁',32,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'GY','贵阳',33,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'LZ','兰州',34,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HK','海口',35,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'SYA','三亚',36,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'WLMQ','乌鲁木齐',37,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HHHT','呼和浩特',38,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'YC','银川',39,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'XN','西宁',40,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'LS','拉萨',41,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'WZ','温州',42,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'ZH','珠海',43,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'CZ','常州',44,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'NT','南通',45,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'YT','烟台',46,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'HKG','香港',47,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'MAC','澳门',48,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'TPE','台北',49,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='cities'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'REGULAR','普通订单',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'FLASH','快闪订单',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PROMOTION','大促活动',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='orderTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'MED_AESTHETIC','医美',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'DENTAL','口腔',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'INTIMATE','私密',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'COMPREHENSIVE','综合',4,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionBusinessTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PUBLIC','公立机构',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionOwnerships'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PRIVATE','民营机构',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='institutionOwnerships'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'S','S类商户',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'A','A类商户',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'B','B类商户',3,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'C','C类商户',4,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='merchantGrades'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);

INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'MONTHLY','月结',1,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='settlementTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
INSERT INTO sys_dictionary_item (tenant_id,dict_type_id,option_code,option_label,sort_no,status)
  SELECT 1,id,'PER_REDEMPTION','次卡次结',2,'ACTIVE' FROM sys_dictionary_type WHERE tenant_id=1 AND dict_code='settlementTypes'
  ON DUPLICATE KEY UPDATE option_code=VALUES(option_code);
