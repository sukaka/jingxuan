# CHANGELOG

本仓库按 `docs/06_大模型开发指令/61_分阶段Prompt.md` 分阶段推进，每个阶段一条记录。

## [V66-YOUZAN-ORDER-MEMO-STEWARD-ASSIGNMENT] 有赞订单按单号拉取、`trade_memo` 备注解析与按「账号」自动/人工分配管家 — 2026-11-27
- **数据库迁移与受控回滚（`V66`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V66__youzan_order_memo_steward_assignment.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V66__youzan_order_memo_steward_assignment_rollback.sql`：
    - 新增 `crm_youzan_steward_alias`（租户级 `账号别名 → CRM 在职管家` 映射表，唯一键 `uk_yz_steward_alias_key(tenant_id, alias_key)`，外键关联 `sys_user`）。
    - 新增 `crm_youzan_order_assignment`（单订单一对一 `uk_yz_order_assign_order(tenant_id, shop_id, external_order_no)`，记录有赞订单展示元数据、脱敏后的下单表单信息、结构化 `trade_memo` 解析字段及归属管家分配状态/来源/乐观锁版本）。
- **后端 `youzan.trade.get/4.0.2` 拉取增强、备注解析、管家分配与数据范围隔离（`apps/crm-api`）**：
  - 新增 `YouzanTradeMemoParser`：解析 `remark_info.trade_memo` 中的「业绩」「渠道」「类型」「来源」「账号」「到院」「补充说明」，自动清理零宽字符并脱敏任意误填 11 位手机号；同时解析 `orders[].buyer_messages` 下单人姓名/手机号并严格脱敏（`YouzanEventService` 同步将 `buyer_messages` 纳入递归脱敏字段列表）。
  - 增强 `YouzanOrderMapper#hasCompleteDetail`：批量拉取（`youzan.trades.sold.get/4.0.2`）若列表响应缺少 `remark_info.trade_memo` 自动回退调用 `youzan.trade.get/4.0.2` 补全详情；列表已包含 `remark_info.trade_memo` 时零额外 N+1 调用；同步实现本地 `MockYouzanAdapter#fetchOrder` 合成样本支持。
  - 新增 `YouzanOrderAssignmentService`、`YouzanOrderAssignmentRepository` / `YouzanOrderAssignmentMapper.xml`：优先匹配启用的 `crm_youzan_steward_alias` 别名表，其次精确匹配在职 `MANAGER`/`MANAGER_LEAD` 的 `display_name`，重名标 `AMBIGUOUS`、未命中标 `UNMATCHED`、无账号标 `NO_ACCOUNT`；人工分配（`MANUAL`）在后续订单同步刷新时不被覆盖，且严禁修改既有 CRM 老客归属管家。
  - 扩展 `YouzanMapper.xml`、`YouzanManagementService` 与 `YouzanIntegrationController`：
    - `GET /api/v1/integrations/youzan/orders` 支持 `assignedStewardId`、`assignmentStatus`、`keyword` 筛选，并将有赞订单分配记录纳入 `SELF`/`TEAM`/`ALL` 数据范围隔离（跨管家越权返回 404，传范围外管家筛选收敛为空）。
    - 新增 `POST /api/v1/integrations/youzan/orders/pull-by-tid`（按 `tid` 实时调用 `youzan.trade.get/4.0.2` 拉取并分配）、`PUT /api/v1/integrations/youzan/orders/{externalOrderNo}/assign`（人工分配/改派并支持管理员一键保存别名与回填历史待分配订单）、`GET /api/v1/integrations/youzan/steward-options` 及 `GET/POST/DELETE /api/v1/integrations/youzan/steward-aliases`（均写审计 `YOUZAN_ORDER_PULL_BY_TID`/`YOUZAN_ORDER_STEWARD_ASSIGN`/`YOUZAN_STEWARD_ALIAS_SAVE`/`YOUZAN_STEWARD_ALIAS_DELETE`，全接口拒绝机构账号）。
- **前端有赞订单同步中心增强与契约文档（`apps/crm-web` + `contracts` + `docs`）**：
  - 升级 `YouzanOrdersView.vue` 与 `YouzanOrderDetailDrawer.vue`：新增按有赞订单号（`tid`）实时拉取栏、关键字/归属管家/分配状态筛选条、订单列表「归属管家」「商家备注摘要」列及有赞状态/支付方式副行；详情抽屉新增「商家备注与管家分配」分区展示完整结构化字段与脱敏下单人信息。
  - 新增 `YouzanOrderAssignDialog.vue`（人工分配/改派与一键保存别名）与 `YouzanStewardAliasPanel.vue`（「管家账号映射」页签，支持新增/编辑/删除及自动回填历史待分配订单）；更新 `AuditLogsView.vue` 审计中文标签。
  - 同步更新 `contracts/openapi/openapi.v1.yaml`、`docs/04_接口与集成/42_有赞订单对接.md` 与 `docs/04_接口与集成/42_有赞字段映射与交接.md`。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4 + Flyway V66 Testcontainers 集成测试 + 静态门禁：`./mvnw -pl apps/crm-api verify -Dtest='com.whale.crm.integration.youzan.**.*Test' -Dit.test='YouzanOfficialFlowIT,YouzanOrderQueryIT,YouzanConnectionApiIT,YouzanAcceptanceIT' -DfailIfNoTests=false` **BUILD SUCCESS**（`07:18 min`）：
    - Surefire 单元测试：**142/142 通过**（22 个有赞测试类，含新增 `YouzanTradeMemoParserTest` 4/4、`YouzanOrderAssignmentServiceTest` 6/6，更新 `RealYouzanAdapterTest` 10/10、`YouzanEventMaskingTest` 3/3、`YouzanOfficialAdapterTest` 10/10、`RealYouzanHttpFlowTest` 3/3）；
    - Failsafe 集成测试（MySQL 8.4 Testcontainers）：**25/25 通过**（`YouzanOrderQueryIT` 10/10、`YouzanConnectionApiIT` 3/3、`YouzanOfficialFlowIT` 4/4、`YouzanAcceptanceIT` 8/8）；
    - 静态门禁：Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors / 0 warnings`、Spotless `1483 files clean`。
  - 前端类型检查、Lint、有赞相关单测与生产构建：`cd apps/crm-web && npm run typecheck && npm run lint && npx vitest run tests/youzan-api.test.ts tests/youzan-order-detail.test.ts tests/youzan-view.test.ts tests/youzan-ui.test.ts tests/youzan-routing.test.ts tests/youzan-shop-config.test.ts && npm run build` 全部通过：`typecheck` 0 错误、`lint`（`--max-warnings 0`）0 错误 / 0 警告、Vitest **6/6 文件、65/65 用例通过**，`vite build`（`7.42s`）成功。
  - OpenAPI YAML 校验：Python `yaml.safe_load` + 全量 `$ref` 递归校验通过（`360 paths / 644 schemas`）；变更文件密钥与敏感信息扫描零泄露。
  - 未运行项：与本模块无关的其他领域全量后端单测与 `*IT`、Kafka 消费集成测试（无新增 Kafka 事件）、全量非本次改动前端测试套件、真实有赞云线上凭证端到端拉取与浏览器 E2E 未运行。

## [YOUZAN-CUSTOMER-DETAIL-AND-HISTORY-SNAPSHOT] 有赞客户拉取详情补全（备注/城市/标签）与客户详情只读有赞历史订单 — 2026-11-27
- **有赞客户拉取详情补全与无损补齐（`apps/crm-api`，无新增数据库表、Flyway 迁移或 Kafka 事件）**：
  - 扩展 `YouzanCustomerPullClient`（`YouzanPulledCustomer` / `YouzanCustomerEnrichment`）、`OfficialYouzanCustomerPullClient` 与 `MockYouzanCustomerPullClient`：
    - `youzan.scrm.customer.list/1.0.0` 与 `youzan.trades.sold.get/4.0.2` 列表解析新增提取 `remark`/`memo`、省/市级 `cityName`（过滤直辖市 `市辖区`/`县` 占位，严禁采集详细街道门牌地址）及过滤密文后的 `tagNames`（每客户最多 30 个，单标签最长 64 字符）。
    - `enrichCustomer` 扩展解析 `youzan.users.info.query/1.0.0`（`primitive_info` 省市）、`youzan.scrm.customer.get/3.0.0`（`remark`/`memo`、省市与标签），并在上述接口未返回标签时可选调用 `youzan.scrm.tag.relation.get`（默认 `4.0.0`，可通过 `YOUZAN_SCRM_TAG_RELATION_VERSION` 覆写，按 `account_type=YzOpenId` 查询，`4204`/`4005` 仅记非阻断警告）。
  - 扩展 `CustomerExternalImportService` 与 `YouzanCustomerSyncService`：
    - 城市按 `cities` 字典 `ACTIVE` 选项做标准化匹配（去 `市`/`自治州` 后缀并兼容省市组合文本），未命中不写非法字典码。
    - 有赞标签解析在独立事务外执行（避免重复标签回滚主导入事务）：复用 `customerTags` 字典已有 `ACTIVE` 项，不自动复活 `INACTIVE`/`ARCHIVED` 标签；仅当操作员具备 `customer:tag:manage` 权限时自动创建 `YZ_TAG_<12位sha256>` 字典项并通过 `insertIfAbsent` 幂等关联。
    - `SCRM_LIST` 开启 `enrichProfile=true` 时，对缺失 `remark`/`cityName`/`birthday`/`tagNames` 的客户同样触发详情补全；对已绑定旧客户（`SKIPPED_EXISTING`）调用 `backfillMissingProfile`（`REQUIRES_NEW` + 行锁）**仅补空** `cityCode`/`remark`/`birthday` 与幂等追加标签并记 `ACTION_CUSTOMER_UPDATE` 审计，绝不覆盖人工已维护字段，也不触碰 `MOBILE_CONFLICT` 客户。
- **客户详情有赞历史订单只读快照接口（`apps/crm-api`，复用 V60 `crm_youzan_annual_order_snapshot`）**：
  - 扩展 `YouzanAnnualSnapshotRepository` / `YouzanAnnualSnapshotMapper.xml`：联合 `crm_youzan_customer_link`、`crm_customer_identity(YOUZAN/LINKED)` 与 `crm_youzan_annual_profile` 聚合客户已关联有赞身份，按显式 `tenant_id` 分页查询并汇总只读历史快照订单。
  - 新增 `CustomerYouzanHistoryOrderController`（`GET /api/v1/customers/{customerId}/youzan-history-orders`，`@PreAuthorize("hasAuthority('customer:read')")`，支持 `paidOnly=true`、`page`、`size`）、`CustomerYouzanHistoryOrderService` 与 `CustomerYouzanHistoryOrdersResponse`：严格拒绝 `INSTITUTION` 机构用户（`403`）、复用 `CustomerScopeSupport#requireVisible` 行级数据范围校验（越权返回 `404`）、对商品标题/规格执行 `QuickReplyTextMasker` 脱敏，绝不返回 `yz_open_id`/明文手机号/街道地址，绝不写入 `crm_order` 或发放 `crm_entitlement`。
- **前端展示、只读历史快照同步入口及契约文档（`apps/crm-web` + `contracts` + `docs`）**：
  - 扩展 `src/api/customer.ts` 新增 `getCustomerYouzanHistoryOrders` 与对应只读历史订单类型；新增 `src/views/youzan/YouzanHistorySnapshotSyncDialog.vue`（按年份预设一键触发 `POST /api/v1/annual-reports/youzan-snapshots/sync`，370 天上限校验与只读安全提示）。
  - 升级 `src/views/customer/CustomerBusinessPanel.vue`：在「订单与退款」页签分区展示「CRM 正式订单与退款」与「有赞历史订单（只读快照）」（汇总卡片、仅已支付开关、结构化商品行明细、独立分页与错误隔离；机构用户自动隐藏；具备 `integration:youzan:manage` 权限时显示「同步历史快照」入口）。
  - 升级 `src/views/youzan/YouzanCustomerPullPanel.vue`：更新资料补全/断点续拉文案，新增「拉取历史订单只读快照」按钮与弹窗。
  - 同步更新 `contracts/openapi/openapi.v1.yaml`（新增 `/customers/{customerId}/youzan-history-orders` 与 5 个 Schema，更新 `enrichProfile` 说明，共 `355 paths / 635 schemas / 2963 refs`）及 `docs/04_接口与集成/42_有赞订单对接.md`（10.1 与新增 10.6 节）。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成测试 + 静态门禁：`./mvnw -B -pl apps/crm-api -am -Dtest='OfficialYouzanCustomerPullClientTest,YouzanCustomerSyncServiceTest,CustomerYouzanHistoryOrderServiceTest,RealYouzanAnnualPullClientTest' -Dit.test='YouzanCustomerSyncApiIT,CustomerYouzanHistoryOrderApiIT,YouzanAnnualSnapshotAndAuthApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（`02:33 min`）：
    - Surefire 单元测试：**26/26 通过**（`OfficialYouzanCustomerPullClientTest` 8/8、`YouzanCustomerSyncServiceTest` 7/7、`RealYouzanAnnualPullClientTest` 8/8、`CustomerYouzanHistoryOrderServiceTest` 3/3）；
    - Failsafe 集成测试（MySQL 8.4 Testcontainers）：**8/8 通过**（`YouzanCustomerSyncApiIT` 6/6、`YouzanAnnualSnapshotAndAuthApiIT` 1/1、`CustomerYouzanHistoryOrderApiIT` 1/1）；
    - 静态门禁：Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors / 0 warnings`、Spotless `1468 files clean`。
  - 前端类型检查、Lint、相关单测与生产构建：`cd apps/crm-web && npm run typecheck && npm run lint && npx vitest run tests/customer-youzan-history-orders.test.ts tests/youzan-customer-pull-panel.test.ts tests/youzan-api.test.ts tests/youzan-view.test.ts tests/customer-profile.test.ts && npm run build` 全部通过：`typecheck` 0 错误、`lint`（`--max-warnings 0`）0 错误 / 0 警告、Vitest **5/5 测试文件、56/56 用例通过**（`customer-youzan-history-orders.test.ts` 4/4、`youzan-customer-pull-panel.test.ts` 11/11、`youzan-api.test.ts` 5/5、`youzan-view.test.ts` 32/32、`customer-profile.test.ts` 4/4）、`build`（`2.22s`）成功。
  - OpenAPI YAML 校验：Python `yaml.safe_load` + 全量 `$ref` 递归解析通过（OpenAPI `3.0.3`，`355 paths`，`635 schemas`，`2963 refs`）。
  - 未运行项：与本模块无关的其他领域全量后端单测与 `*IT`、Kafka 消费集成测试（无新增 Kafka 事件）、全量非有赞/客户前端测试套件、真实有赞云线上凭证端到端拉取与全站浏览器 E2E 未运行。

## [V65-CUSTOMER-PRIVACY-GOVERNANCE] 客户隐私全员默认脱敏、上级主管审批限时查看与管理员按钮揭盲审计 — 2026-11-27
- **数据库迁移、受控回滚与权限种子（`V65`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V65__customer_privacy_governance.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V65__customer_privacy_governance_rollback.sql`，同步更新 `db/dev/R__seed_auth_dev.sql` 与 `db/prod/R__seed_rbac_prod.sql`：
    - `crm_customer` 新增 `id_card_cipher/hash`、`email_cipher/hash`、`wechat_id_cipher/hash` 密文/盲索引列与 3 个盲索引（`idx_customer_id_card_hash`/`idx_customer_email_hash`/`idx_customer_wechat_hash`），与既有 `mobile_cipher/hash` 构成四类核心隐私加密存储。
    - `sys_user` 新增 `supervisor_user_id`（`idx_user_supervisor`）支持显式配置直属上级。
    - 新增 `crm_customer_privacy_request`（申请单与限时授权状态机）与只追加 `crm_customer_privacy_access_log`（揭盲访问日志，配 `trg_privacy_access_log_no_update` / `trg_privacy_access_log_no_delete` 触发器物理防篡改）。
    - 新增 4 项功能权限 `customer:privacy:request`、`customer:privacy:approve`、`customer:privacy:admin_reveal`（**仅授予 `ADMIN`**）、`customer:privacy:audit`，严禁向任何合作机构角色授予隐私权限。
- **后端全站脱敏加固、上级路由解析与隐私审批/揭盲接口（`apps/crm-api`）**：
  - 用户上级体系：扩展 `SysUser` / `UserApplicationService` / `UserController`（新增 `GET /api/v1/users/supervisors`），校验直属上级仅限同租户在职 `EMPLOYEE`，严格拒绝自指与成环，并写入用户审计快照。
  - 四类隐私加密与脱敏：新增 `CustomerPrivacyField` 与 `CustomerPrivacyFieldSupport`（GB 11643-1999 身份证校验、邮箱/微信号格式校验、禁止回写含 `*` 脱敏占位串、AES-256-GCM 加密与域前缀 HMAC-SHA256 盲索引、精确检索与授权解密）；加固 `CustomerApplicationService`、`LegacyProfileApplicationService`（修复 `legacy_full_json` 兜底值未脱敏风险）与 `ExportExecutionService`/`ExportColumnPolicy`（导出增加脱敏列，永不导出明文）。
  - 隐私审批与揭盲：`CustomerPrivacyApproverResolver` 按 `DIRECT_SUPERVISOR → TEAM_LEADER → TEAM_MANAGER_LEAD → ROLE_SUPERIOR → ADMIN_FALLBACK` 自动解析上级并严禁自我审批；`CustomerPrivacyApplicationService` + `CustomerPrivacyController` 暴露 9 个端点（`/customers/{customerId}/privacy/{status,requests,reveal}`、`/customer-privacy-requests`、`/{id}/{cancel,approve,reject,revoke}`、`/customer-privacy-access-logs`）；`POST /reveal` 响应头强制 `Cache-Control: no-store, must-revalidate` + `Pragma: no-cache`，ADMIN 模式强制必填原因（1–500 字符），非 ADMIN 模式强制校有效审批授权与获批字段子集，每次揭盲双写只追加访问流水与 `crm_audit_log`（`AuditService` 新增 8 个 `CUSTOMER_PRIVACY_*` 审计常量；越权/自审尝试通过独立事务审计留痕；复用既有站内通知服务，无新增 Kafka 主题/事件）。
- **前端脱敏展示、显式按钮揭盲、审批与日志中心及契约文档（`apps/crm-web` + `contracts` + `docs`）**：
  - 新增 `src/api/customerPrivacy.ts`、`src/views/customer/CustomerPrivacyPanel.vue`（嵌入 `CustomerDetailDrawer.vue`：默认全员脱敏；ADMIN 点「查看隐私信息」填原因临时展开明文，支持手动隐藏、120s 自动隐藏与关抽屉/卸载销毁；非 ADMIN 发起申请/撤回/获批后点「打开查看明文」仅展开获批字段）与 `src/views/CustomerPrivacyApprovalsView.vue`（路由 `/customers/privacy-approvals`，含「隐私审批管理」「我的隐私申请」「隐私访问审计日志」三页签，审批支持收窄字段与提前撤销，日志仅展示脱敏快照；侧边栏增加待审角标）。
  - 扩展 `CustomerFormDrawer.vue`（身份证号/邮箱/微信号加密录入、脱敏占位提示、显式清空开关与 `*` 拦检）、`UsersView.vue`（直属上级列与新建/编辑搜索选择）与 `AuditLogsView.vue`（新增 8 类隐私审计中文标签、`actorUsername`/`reason` 展示及 `before`/`after` 兼容）。
  - 同步更新 `contracts/openapi/openapi.v1.yaml`（`354 paths`、`630 schemas`）、`docs/01_需求与产品/12_角色权限与数据隔离.md`、`docs/02_架构与技术/23_安全隐私与审计.md` 与 `docs/03_数据模型/31_数据库字段字典.md`。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成/回滚 + 静态门禁：`./mvnw -B -pl apps/crm-api -am -Dtest='CustomerPrivacyFieldSupportTest,CustomerPrivacyApproverResolverTest,CustomerPrivacyApplicationServiceTest,UserApplicationServiceTest,CustomerTagBatchLookupTest,ExportExecutionServiceTest' -Dit.test='CustomerPrivacyApiIT,CustomerApiIT,UserAdminApiIT,AuditLogApiIT,ProductionReferenceDataIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（`05:17 min`）：
    - Surefire 单元测试：**30/30 通过**（`UserApplicationServiceTest` 10/10、`ExportExecutionServiceTest` 7/7、`CustomerTagBatchLookupTest` 4/4、`CustomerPrivacyApplicationServiceTest` 3/3、`CustomerPrivacyApproverResolverTest` 3/3、`CustomerPrivacyFieldSupportTest` 3/3）；
    - Failsafe 集成测试（MySQL 8.4 Testcontainers）：**38/38 通过**（`UserAdminApiIT` 15/15、`CustomerApiIT` 14/14、`ProductionReferenceDataIT` 4/4、`AuditLogApiIT` 3/3、`CustomerPrivacyApiIT` 2/2 含全链路脱敏/审批/揭盲/触发器防篡改与 V65 迁移+回滚脚本实测）；
    - 静态门禁：Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors / 0 warnings`、Spotless `1463 files clean`。
  - 前端静态检查、全量单测与生产构建：`cd apps/crm-web && npm run typecheck && npm run lint && npm run test -- --maxWorkers=2 && npm run build` 全部通过：`typecheck`（`vue-tsc --noEmit`）0 错误、`lint`（`eslint . --max-warnings 0`）0 错误 / 0 警告、`vitest run --maxWorkers=2` **165/165 测试文件、807/807 用例通过**（`221.10s`，含 `customer-privacy-panel.test.ts` 4/4、`customer-privacy-approvals-view.test.ts` 3/3、`users-view.test.ts` 6/6、`app-shell.test.ts` 10/10、`customer-profile.test.ts` 4/4、`customers-view.test.ts` 11/11）、`build`（`vue-tsc -b && vite build`）`3.51s` 成功。
  - OpenAPI YAML 校验：Python `yaml.safe_load` + 全量 `$ref` 递归解析通过（OpenAPI `3.0.3`，`354 paths`，`630 schemas`，`2955 refs`）。
  - 未运行项：与本模块无关的其他领域全量后端单测与 `*IT`、Kafka 消费集成测试（无新增 Kafka 事件）及全站浏览器 E2E 未运行。

## [YOUZAN-SCRM-CUSTOMER-LIST-1-0-0] 有赞全量客户拉取切换至现行接口 `youzan.scrm.customer.list/1.0.0` — 2026-11-27
- **适配层修正与隐私/超限防护（`apps/crm-api` + `apps/crm-web` + `contracts` + `docs`，无新增数据库表、Flyway 迁移或 Kafka 事件）**：
  - 将 `OfficialYouzanCustomerPullClient#pullScrmPage` 由已下架的 `youzan.scrm.customer.search/3.1.2`（分页参数 `page`）切换为有赞云现行标准接口 `youzan.scrm.customer.list`（默认 `1.0.0`，可通过 `YOUZAN_SCRM_CUSTOMER_LIST_VERSION` 覆写），分页参数修正为 `page_no` / `page_size`（上限裁剪为 `50`），继续透传秒级时间戳 `created_at_start` / `created_at_end`。
  - 针对 `youzan.scrm.customer.list/1.0.0` 单窗最多返回 `10,000` 条（`500` 页 × `20` 条）及业务超限码 `142710032` 的约束，当 `data.total >= 10000` 或网关返回 `142710032` 时自动抛出 `YouzanCustomerWindowTooLargeException` 触发时间窗二分切窗，避免触顶静默截断；遇到 `4204`/`4005` 权限错误时返回 `422 PENDING_BUSINESS_RULE` 并明确提示核查「店铺客户信息同步」能力包或切换 `ORDER_BUYERS`。
  - 针对 `show_name`（官方取值顺序为「手机-姓名-昵称」）增加手机号形态识别过滤：当 `name` 为空且 `show_name` 为明文手机号时不再写入客户姓名/昵称与审计日志，交由 `enrichProfile` 补全真实昵称/姓名或安全兜底。
  - 保持前端 `YouzanCustomerPullPanel.vue` 默认选中 `SCRM_LIST`（有赞全量客户）并更新权限说明为 `scrm.customer.list`；同步更新 `OfficialYouzanCustomerPullClientTest`、`contracts/openapi/openapi.v1.yaml`、`docs/04_接口与集成/42_有赞订单对接.md` 与 `docs/07_上线与对接指引/有赞云历史订单快照与微信H5自动登录配置指南.md`。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成测试 + 静态门禁：`./mvnw -B -pl apps/crm-api -am -Dtest='OfficialYouzanCustomerPullClientTest,YouzanCustomerSyncServiceTest,com.whale.crm.integration.youzan.adapter.*Test' -Dit.test='YouzanCustomerSyncApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**：
    - Surefire 单元测试：**34/34 通过**（`OfficialYouzanCustomerPullClientTest` 6/6、`YouzanCustomerSyncServiceTest` 6/6、`RealYouzanAdapterTest` 9/9、`RealYouzanHttpFlowTest` 3/3、`YouzanOfficialAdapterTest` 10/10）；
    - Failsafe 集成测试（MySQL 8.4 Testcontainers）：`YouzanCustomerSyncApiIT` **5/5 通过**；
    - 静态门禁：Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors / 0 warnings`、Spotless `1438 files clean`。
  - 前端类型检查、Lint、有赞单测与生产构建：
    - `npm --prefix apps/crm-web run typecheck`（`vue-tsc --noEmit`，0 错误）；
    - `npm --prefix apps/crm-web run lint`（`eslint . --max-warnings 0`，0 错误 / 0 警告）；
    - `cd apps/crm-web && npx vitest run --testTimeout=15000 tests/youzan-customer-pull-panel.test.ts tests/youzan-view.test.ts tests/youzan-api.test.ts`（**3/3 测试文件、47/47 用例通过**：`youzan-api.test.ts` 5/5、`youzan-customer-pull-panel.test.ts` 10/10、`youzan-view.test.ts` 32/32）；
    - `npm --prefix apps/crm-web run build`（`vue-tsc -b && vite build`，`5.76s` 构建成功）。
  - OpenAPI YAML 校验：`yaml.safe_load` 解析通过（OpenAPI `3.0.3`，`344 paths`，`610 schemas`）。
  - 未运行项：非有赞域全量后端单测与 `*IT`、Kafka 消费集成测试、全量非有赞前端测试套件、真实有赞云线上凭证端到端拉取与全站浏览器 E2E 未运行。

## [YOUZAN-ORDER-ITEM-DETAILS] 有赞订单结构化商品明细展示与 CRM SKU 关联补全 — 2026-11-26
- **后端结构化商品明细与批量 SKU 名称补全（`apps/crm-api` + `contracts` + `docs`，无新增数据库表、Flyway 迁移或 Kafka 事件）**：
  - 保持适配层 `YouzanOrderLine` 契约不变，新增 `YouzanOrderItemView`（`external_sku_id`/`quantity`/`itemTitle`/`skuSpec`/`priceFen`/`paymentFen`/`outerSkuCode`/`crmSkuId`/`crmSkuName`），升级 `YouzanOrderView.items` 为 `List<YouzanOrderItemView>`（默认不可变空列表）。
  - 复用并泛化 `YouzanManagementService` 商品元数据提取逻辑：仅从脱敏原文 `providerPayloadMaskedJson`（回退 `payloadMaskedJson`）解析商品标题、规格、单价与行实付/小计（`BigDecimal` 精确转非负整数分，禁止 float/double）及商家 SKU 编码，绝不读取未脱敏 PII；缺失元数据的 Mock/历史事件平滑降级为仅保留 `external_sku_id` 与 `quantity`。
  - 保持权限与安全边界：`payloadMaskedJson` 仍仅向 `integration:youzan:manage` 返回，普通 `order:read` 用户该字段为 `null` 但可正常查看非敏感结构化商品明细；`CatalogApplicationService#skusByIds`（配套 `CatalogRepository`/`CatalogMapper` 按显式 `tenant_id` 与 `institutionIds` 范围批量查询）结合内部订单行快照（`OrderItem.skuNameSnapshot`）与按页预加载 ACTIVE SKU 映射，消除分页 N+1 查询，并在无 `product:read` 权限、目录异常或机构用户场景下优雅降级不抛 403/500。
  - 同步更新 `contracts/openapi/openapi.v1.yaml`（新增 `YouzanOrderItemView` 并更新 `YouzanOrderView.items`，`344 paths / 610 schemas`）与 `docs/04_接口与集成/42_有赞字段映射与交接.md`。
- **验证证据（后端）**：
  - 全量编译 + 单元测试：`./mvnw -B -pl apps/crm-api test -Dtest='com.whale.crm.integration.youzan.**.*Test,com.whale.crm.product.*Test'` **BUILD SUCCESS**（`137/137` 通过，含 `YouzanManagementServiceTest` `17/17`、`CatalogApplicationServiceTest` `1/1`）。
  - 静态门禁：`./mvnw -B -pl apps/crm-api checkstyle:check spotless:check spotbugs:check` **BUILD SUCCESS**（Checkstyle `0 violations`、Spotless `1438 files clean`、SpotBugs `0 bugs / 0 errors / 0 warnings`）。
  - MySQL 8.4 Testcontainers 集成测试：`./mvnw -B -pl apps/crm-api -DskipUTs=true -Dtest=NonExistent -DfailIfNoTests=false failsafe:integration-test failsafe:verify -Dit.test='YouzanOrderQueryIT'` **BUILD SUCCESS**（`10/10` 通过，覆盖实测有赞结构化商品解析、`admin`/`manager1`/`finance1` 权限隔离与无 `product:read` 优雅查询、历史订单冻结快照与 Mock 回退）。
  - OpenAPI YAML 校验：`yaml.safe_load` 及 `YouzanOrderItemView` 9 字段与 `$ref` 校验通过（OpenAPI `3.0.3`，`344 paths`，`610 schemas`）。
  - 未运行项：非有赞/产品域的其他全量后端单元测试与 `*IT`、Kafka 消费集成测试与真实有赞线上凭证联调未运行。

## [V64-YOUZAN-CUSTOMER-SYNC] 有赞客户双模式拉取、断点续拉与 CRM 正式建档 — 2026-11-26
- **数据库迁移与受控回滚（`V64`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V64__youzan_customer_sync.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V64__youzan_customer_sync_rollback.sql`，新增 `crm_youzan_customer_sync_cursor`（`(tenant_id, shop_id, source_mode)` 持久化断点游标、增量锚点 `last_anchor_at` 与增量累加的 `total_imported_count`）、`crm_youzan_customer_sync_run`（存储生成列 `running_shop_key` + 唯一键 `uk_yz_cust_run_running` 实现同店铺同模式并发互斥锁与租约超时回收）与 `crm_youzan_customer_sync_item`（逐条脱敏处理结果明细，仅存脱敏称呼/手机号、关联或冲突 CRM 客户 ID/编号，绝不落明文手机号与明文微信 OpenID；回滚仅删除 3 张同步表，不影响已建 CRM 正式客户）。
- **后端适配器、断点游标引擎与 5 个管理接口（`apps/crm-api`）**：
  - 适配器与路由：`YouzanCustomerPullClient`、`OfficialYouzanCustomerPullClient`（`SCRM_LIST` 调用 `youzan.scrm.customer.search` 默认 `3.1.2` 遇 `4005` 自动回退 `3.1.1`/`3.1.0`；`ORDER_BUYERS` 复用既有 4 个实测接口 `trades.sold.get` 4.0.2 / `trade.get` 4.0.2 / `users.info.query` 1.0.0 / `scrm.customer.get` 3.0.0；自动过滤有赞安全密文 `$...$1$`）、`MockYouzanCustomerPullClient`（严格 `@Profile("local")`）与 `YouzanCustomerPullClientRouter`（开启真实同步时强制校验已启用店铺配置，绝不静默降级）。
  - 应用服务与跨模块建档：`CustomerExternalImportService`、`YouzanCustomerCursorState` 与 `YouzanCustomerSyncService`：底层采用固定页长（`SCRM_LIST` 固定 20、`ORDER_BUYERS` 固定 50）与持久化断点游标 `(anchorStart, anchorEnd, windowStart, windowEnd, pageNo, itemOffset)`，支持 `10→10→20→全部` 无错位无漏条续拉；`ALL` 模式单次最多拉取 `limit`（默认 200、上限 500）且最多扫 30 页并返回 `hasMore=true` 供前端自动分批续拉；扫完后记录 `lastAnchorAt=anchorEnd` 并在下轮自动从 `lastAnchorAt - 60s` 增量拉取；超大时间窗触发 `YouzanCustomerWindowTooLargeException` 时以 `2015-01-01T00:00:00Z` 兜底中点二分切窗；`ORDER_BUYERS` 自动按 ≤89 天切窗（默认过去 365 天，支持 `Asia/Shanghai` 起止日期）。
  - 业务红线与安全保障：预检目标归属管家为在职且可分配的 `MANAGER`/`MANAGER_LEAD` 及启用来源/渠道字典；已关联 `yz_open_id` 幂等跳过（`SKIPPED_EXISTING`），未关联但手机号盲索引命中已有 CRM 老客户时记 `MOBILE_CONFLICT` 并输出 `conflictCustomerIds`/`conflictCustomerNos`，**绝不新建重复档案或覆盖原客户**；新建客户后同步写入 `crm_youzan_customer_link`（`STRONG`/`HIGH`）并联动回填未解风控单；暴露 `/api/v1/integrations/youzan/customer-sync/{status,pull,cursor/reset,runs,runs/{runId}/items}` 5 个端点（读 `integration:youzan:manage`，写叠加 `customer:write` + CSRF，全接口拒绝机构账号，`POST /pull` 强制 `Idempotency-Key` 幂等重放，`finally` 保证异常释放并发锁），记录 `YOUZAN_CUSTOMER_SYNC` / `YOUZAN_CUSTOMER_CURSOR_RESET` 审计日志（无新增 Kafka 事件）。
- **前端拉取工作台、契约与对接文档（`apps/crm-web` + `contracts` + `docs`）**：
  - `src/api/youzan.ts` 扩展 5 个客户拉取 API 与类型；新建 `src/views/youzan/YouzanCustomerPullPanel.vue`（展示通道状态与断点游标进度、双数据源切换、归属管家/来源/渠道/日期/资料补全开关、`10/20/50/100` 预设与 `1~500` 自定义、按每批 200 条独立 `Idempotency-Key` 自动续拉且支持「停止拉取」的「全部拉取」、二次确认「重置游标」、结果明细筛选分页、`MOBILE_CONFLICT` 一键「去人工链接」跳转预填 `yz_open_id` 及历史批次切换）；挂载至 `YouzanOrdersView.vue`「客户拉取与建档」页签（支持 `?tab=customer-pull`），在 `CustomersView.vue` 增加「从有赞拉取客户」快捷入口，并在 `AuditLogsView.vue` 补充 2 项审计中文标签。
  - 更新 `contracts/openapi/openapi.v1.yaml`（新增 5 个路径与 10 个 Schema/Envelope，共 `344` paths / `609` schemas）、`docs/04_接口与集成/42_有赞订单对接.md`（补充第 3 节 5 端点与第 10 节完整技术规范与 V64 表结构）与 `docs/07_上线与对接指引/有赞云历史订单快照与微信H5自动登录配置指南.md`。
- **验证证据（对应群组记录 `#6`、`#9`、`#13`）**：
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成/回滚 + 静态门禁（`#9`）：
    - `./mvnw -B -pl apps/crm-api -am -Dtest='OfficialYouzanCustomerPullClientTest,YouzanCustomerSyncServiceTest,ProductionProfileTest' -Dit.test='YouzanCustomerSyncApiIT,YouzanConnectionApiIT,CustomerApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（`03:24 min`）：
      - Surefire 单元测试：**15/15 通过**（`OfficialYouzanCustomerPullClientTest` 6/6、`YouzanCustomerSyncServiceTest` 6/6、`ProductionProfileTest` 3/3）；
      - Failsafe 集成测试（MySQL 8.4 Testcontainers）：**22/22 通过**（`YouzanCustomerSyncApiIT` 5/5（含 5 端点 RBAC/机构拒绝、10→10→15 连续拉取 35 条 Mock 客户零重复、幂等重放与重置重拉全跳过、`ORDER_BUYERS` 复购去重 + `MOBILE_CONFLICT` 老客户保护、`running_shop_key` 互斥锁与租约过期自动恢复、V64 迁移与受控回滚脚本复验）、`YouzanConnectionApiIT` 3/3、`CustomerApiIT` 14/14）；
      - 静态门禁：Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors / 0 warnings`、Spotless `1436 files clean`（0 format diffs）。
  - 前端静态检查、全量单测与构建（`#13`）：
    - `npm --prefix apps/crm-web run typecheck && npm --prefix apps/crm-web run lint && npm --prefix apps/crm-web run test -- --maxWorkers=2 && npm --prefix apps/crm-web run build` 全部通过：`typecheck`（`vue-tsc --noEmit`）0 错误、`lint`（`eslint . --max-warnings 0`）0 错误 / 0 警告、`vitest run --maxWorkers=2` **163/163 测试文件、794/794 用例通过**（`153.19s`；其中 `tests/youzan-customer-pull-panel.test.ts` 10/10、`tests/youzan-api.test.ts` 5/5、`tests/youzan-view.test.ts` 31/31、`tests/customers-view.test.ts` 11/11）、`build`（`vue-tsc -b && vite build`）`3.08s` 成功。
  - OpenAPI 契约校验（`#6`）：Python `yaml.safe_load` + 全量 `$ref` 递归解析通过（`paths: 344`，`schemas: 609`，`refs_checked: 2868`）。
  - 未运行项：非本次涉及领域的其他历史全量后端单测与 `*IT`、Kafka 消费集成测试、真实有赞云线上凭证端到端调用与全站浏览器 E2E 测试未运行。

## [YOUZAN-SKU-AUTOFILL-AND-BATCH-RETRY] 有赞外部 SKU 自动提取预填与已映射异常手动批量重试 — 2026-11-26
- **后端候选提取、旧异常自动消解与手动批量重试接口（`apps/crm-api` + `contracts` + `docs`，无新增数据库表、Flyway 迁移或 Kafka 事件）**：
  - 新增 `GET /api/v1/integrations/youzan/sku-candidates?shopId=...`（`YouzanSkuCandidateView`），在严格租户与 `DataScope` 边界下从待处理 `MAPPING_FAILED` 异常自动聚合提取外部 SKU 编码、商品标题、规格、参考单价（整数分）、商家编码、待处理异常数与示例订单号（零客户 PII 与密文泄露；排除空白 SKU / `quantity <= 0` 的异常明细行并容错跳过单条损坏密文）。
  - 新增 `POST /api/v1/integrations/youzan/sync-issues/batch-retry`（`YouzanBatchRetryRequest` / `YouzanBatchRetryResponse`，校验写权限与 `Idempotency-Key` 并写 `YOUZAN_ISSUE_BATCH_RETRY` 审计日志），仅由人工显式触发批量补偿重试已完成 SKU 映射的待处理异常；保存 SKU 映射仍绝不自动确认订单或发放权益。
  - 修复 `YouzanEventService#markFailure` 旧异常滞留：重试推进至后续阶段（如 `MAPPING_FAILED` 推进到 `CUSTOMER_UNRESOLVED` / `PAYMENT_MISMATCH`）时，自动将同一事件上其他类型的 `OPEN`/`RETRYING` 旧异常标记为 `RESOLVED`（不修改 `RESOLVED`/`DEAD`，且无 ADMIN 账号时不抛异常）；同步更新 `contracts/openapi/openapi.v1.yaml`（344 paths）与 `docs/04_接口与集成/42_有赞订单对接.md`。
- **前端自动预填、跨页签联动与防误操作体验（`apps/crm-web`）**：
  - `src/api/youzan.ts` 新增 `listYouzanSkuCandidates`、`batchRetryYouzanSyncIssues` 及对应类型；`src/views/youzan/youzanUi.ts` 新增 `extractUnmappedExternalSkuId`（兼容中英文冒号、真实 `item_id:sku_id`、Mock SKU、尾部句号及 `null`/非映射错误过滤）。
  - `YouzanSkuMappingPanel.vue` 新增待映射外部 SKU 候选表（「一键映射」自动带入外部 SKU ID 与商品元数据卡，对 `INACTIVE` 历史映射自动携带 `version` 重新启用防 409 冲突）、就绪待重试横幅与行级「重试异常(N)」、弹窗候选下拉（支持清空与 `INACTIVE` 版本回填）、目标店铺展示及模拟店/跨店未检出提醒、候选加载失败告警与重试，以及只读权限差异化提示；`autoOpenRequest` 消费后通知父级清理，避免切换页签误弹。
  - `YouzanSyncIssuePanel.vue` 修复操作列 `<template v-if>` 片段导致 Element Plus 表格隐藏测量行命中 `row.id=undefined` 的问题，新增未映射 `MAPPING_FAILED` 异常「去配映射」（`detailMasked` 无编码时回退解析 `lastError`）与已映射「已配映射」提示，并在批量重试进行中禁用行级重试按钮。
  - `YouzanOrdersView.vue` 串联候选加载（带请求序号与店铺防竞态保护及错误透传）、订单页「匹配并同步」缺失 SKU 自动跳转预填、异常队列工具栏按当前店铺手动「批量重试已映射异常」（网络失败重试沿用同一 `Idempotency-Key`，如实汇总 `processed`/`advanced`/`stillFailed`/`skipped`/`remaining`）。
- **验证证据（对应群组记录 `#10`、`#14`、`#15`）**：
  - 后端验证：`./mvnw -B -pl apps/crm-api test -Dtest='com.whale.crm.integration.youzan.**.*Test'` **PASS**（`125/125`，含 `YouzanManagementServiceTest` `14/14`、`YouzanEventServiceBehaviorTest` `9/9`）；`./mvnw -B -pl apps/crm-api checkstyle:check spotless:check spotbugs:check` **PASS**（Checkstyle `0 violations`、Spotless `1436 files clean`、SpotBugs `0 bugs / 0 errors`）；`./mvnw -B -pl apps/crm-api -DskipUTs=true -Dtest=NonExistent -DfailIfNoTests=false failsafe:integration-test failsafe:verify -Dit.test='YouzanOrderQueryIT'` **PASS**（`9/9`，Testcontainers MySQL 8.4 + Flyway，含 `skuCandidatesAndBatchRetryWorkEndToEndAgainstMysqlWithScopeAndSupersededIssueResolution`）。
  - OpenAPI YAML 校验：`yaml.safe_load` 解析通过（OpenAPI `3.0.3`，`344 paths`）。
  - 前端验证：`npm --prefix apps/crm-web run typecheck`（`vue-tsc --noEmit`，0 错误）、`npm --prefix apps/crm-web run lint`（`eslint . --max-warnings 0`，0 错误 / 0 警告）、`cd apps/crm-web && npx vitest run tests/youzan-*.test.ts`（`7/7` 文件 `61/61` 用例通过，含 `youzan-api.test.ts` `5/5`、`youzan-ui.test.ts` `4/4`、`youzan-view.test.ts` `31/31`、`youzan-order-detail.test.ts` `7/7`、`youzan-shop-config.test.ts` `6/6`、`youzan-customer-pull-panel.test.ts` `6/6`、`youzan-routing.test.ts` `2/2`）、`npm --prefix apps/crm-web run build`（`vue-tsc -b && vite build`，`1.42s` 构建成功）。
  - 未运行项：非有赞域全量后端 `*IT`（`./mvnw -B -pl apps/crm-api -am verify`）、非有赞域全量前端测试套件与全站浏览器 E2E 未运行。

## [YOUZAN-SHOP-PERSIST-AND-MANUAL-LIMIT] 修复有赞订单页刷新回退模拟店铺导致订单不可见，并支持手动设定拉取条数 — 2026-11-26
- **根因修复：刷新不再回退 `MOCK-SHOP-001`（`apps/crm-web`）**：
  - `YouzanOrdersView.vue` 将当前查询/选中/同步的店铺编号（`kdt_id`）按登录用户持久化至 `localStorage`（并支持 URL `?shopId=...` 优先读取）；首次进入且尚无本地记录时，若租户已配置真实店铺则自动优先选中已连接启用的真实店铺（否则选中首个已配置真实店铺，未配置真实店铺时仍默认 `MOCK-SHOP-001`），并在查询栏提供已配置真实店铺一键快捷切换芯片；同步成功后自动回到第 1 页刷新列表。
- **手动拉取条数控制（`apps/crm-api` + `apps/crm-web` + `contracts` + `docs`）**：
  - 后端 `YouzanSyncRequest` 新增可选字段 `maxOrders`（`@Min(1) @Max(500)`，保留既有 3 参构造向后兼容）；`YouzanManagementService#reconcile` 在传入 `maxOrders` 时自动选取恒定页长并在达到上限或剩余条数小于页长时安全截断（既自动跳过空历史时间窗，又避免同一时间窗内变更 `page_size` 导致偏移跳单/重复），`YouzanManagementService#sync` 同步按 `min(limit, maxOrders, 100)` 裁剪单页条数；`YouzanManagementWindowTest` 新增 3 个单元测试覆盖。
  - 前端 `src/api/youzan.ts` 扩展 `YouzanSyncInput.maxOrders`；`YouzanOrdersView.vue` 在顶部操作栏新增「拉取条数」控件（快捷档位 `10 / 20 / 50 / 100 / 500` + `1–500` 自定义数字输入，按用户持久化），切换条数时自动清空旧同步游标防分页错位；同步更新 `contracts/openapi/openapi.v1.yaml` 与 `docs/04_接口与集成/42_有赞订单对接.md`。
- **验证证据（无新增数据库表或 Flyway 迁移，无新增 Kafka 事件）**：
  - 后端验证：`./mvnw -B -pl apps/crm-api -am -Dtest='YouzanManagementWindowTest,YouzanManagementServiceTest,YouzanManagementServiceContractTest,MockYouzanAdapterTest,RealYouzanAdapterTest,RealYouzanHttpFlowTest' -Dit.test='YouzanConnectionApiIT,YouzanOfficialFlowIT,YouzanOrderQueryIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（Surefire `40/40`、Failsafe MySQL 8.4 Testcontainers `14/14`、Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1408 files clean`）。
  - 前端验证：`npm --prefix apps/crm-web run typecheck`（0 错误）、`npm --prefix apps/crm-web run lint`（`eslint . --max-warnings 0`，0 错误 / 0 警告）、`cd apps/crm-web && npx vitest run tests/youzan-*.test.ts`（`6/6` 文件 `43/43` 用例通过，含 `youzan-view.test.ts` `22/22`、`youzan-api.test.ts` `3/3`）、`npm run build`（`6.64s` 构建成功）。
  - OpenAPI YAML 校验：`yaml.safe_load` 解析通过（`paths: 337`，`schemas: 594`）。
  - 未运行项：非有赞域的其他后端 `*IT`、Kafka 消费集成测试、全量前端非有赞测试套件与全站浏览器 E2E 未运行。

## [V63-TAG-FILTER-WECOM-SYNC] 客户列表标签维度筛选与企微企业标签双向同步 — 2026-11-26
- **客户列表多标签筛选（`apps/crm-api` + `apps/crm-web`）**：
  - `GET /api/v1/customers` 新增可选查询参数 `tagCodes`（支持重复参数与逗号分隔，自动去空格去重，上限 20 个、单个不超过 64 字符，超限返回 `400 VALIDATION_FAILED`）与 `tagMatchMode`（`ANY`/`ALL` 大小写不敏感，默认 `ANY`，非法值返回 `400 VALIDATION_FAILED`）；`CustomerMapper.xml` 在未传 `tagCodes` 时保持原 SQL 100% 不变，传参时以显式 `tenant_id` 与 `DataScope` 配合 `EXISTS` 复用 `crm_customer_tag` 索引（`Wave1Wave2ExplainIT` 已验证无全表扫描）。
  - 前端 `src/api/customer.ts` 扩展 `CustomerListQuery` 并将 `tagCodes` 序列化为逗号分隔查询串；`CustomersView.vue` 新增标签多选筛选（`customer-tag-filter`）、选中多标签时显示「满足任一 / 同时满足」匹配模式切换（`customer-tag-match-mode`），新建客户保存后同步重置标签筛选，既有客户分配、来源渠道管理与多管家排查逻辑保持不变。
- **企微企业标签双向同步（官方接口 + `@Profile("local")` Mock 开关，严禁个人微信）**：
  - **数据库迁移与回滚（`V63`）**：新增 `apps/crm-api/src/main/resources/db/migration/V63__wecom_tag_bidirectional_sync.sql` 与 `apps/crm-api/src/main/resources/db/rollback/V63__wecom_tag_bidirectional_sync_rollback.sql`，建立 `crm_wecom_tag_mapping`（按租户/企业双向唯一映射）、`crm_wecom_tag_sync_run`（`running_corp_key` 生成列防并发重入）与 `crm_wecom_customer_tag_push_task`（异步推送队列与重试租约），含全列注释与索引。
  - **后端适配器、应用服务与 8 个接口（`apps/crm-api`）**：新增 `WecomTagClient`、`OfficialWecomTagClient`（仅调用企微官方 `/cgi-bin/externalcontact/get_corp_tag_list`、`add_corp_tag`、`edit_corp_tag`、`del_corp_tag`、`mark_tag`，复用 `WecomTokenService` 自动刷新与脱敏错误）、`MockWecomTagClient`（严格 `@Profile("local")`）、`WecomTagClientRouter`（接入运行配置开关与 `allowedTenantId`）；新增 `WecomTagSyncService`、`WecomTagPushTaskScheduler` 与 `CustomerTagSyncPort`，打通客户建档/编辑/标签替换/批量打标后非阻塞入队推送，以及企微回调（`change_external_tag` / `edit_external_contact` / `add_external_contact`，去重消除重复非受保护拉取）与联系人同步拉取（`doPullAllCustomerTags` 与 `CustomerApplicationService.syncTagsFromWecom` 双层 D01 启用字典校验：仅同步已映射且启用中的标签，绝不新写入停用标签、保留客户既有历史停用标签与未映射 CRM 内部标签，且不回声触发反向推送）；暴露 `/api/v1/integrations/wecom/tag-sync/*` 8 个端点（`mappings`、`mappings/{crmTagCode}`、`catalog`、`customers`、`customers/{customerId}/push`、`runs`、`tasks`、`tasks/{taskId}/retry`，严格执行 RBAC、`Idempotency-Key`、`externalUseridMasked` 脱敏及跨团队 `DataScope` 隔离：`GET /tasks`、`POST /tasks/{id}/retry`、`POST /customers` 与 `POST /customers/{id}/push` 均限制在当前用户可见客户范围），并在 `AuditService` 新增 `WECOM_TAG_CATALOG_SYNC` / `WECOM_TAG_MAPPING_UPDATE` / `WECOM_CUSTOMER_TAG_PULL` / `WECOM_CUSTOMER_TAG_PUSH` 四类审计常量。
  - **前端同步管理与 OpenAPI 契约（`apps/crm-web` + `contracts`）**：`src/api/wecom.ts` 新增 8 个标签同步 API 辅助函数与类型；新增 `src/views/wecom/WecomTagSyncPanel.vue` 并挂载于 `WecomArchiveAdminView.vue`「标签双向同步」Tab（按通道模式精确展示「官方企微通道 / 模拟演示模式 / 未启用（`DISABLED`）」与不接个人微信说明，按 RBAC 分级控制标签库同步 / 解除映射 / 客户批量同步 / 推送任务队列重试）；增强 `CustomerTagManageDialog.vue` 展示企微映射状态与一键双向同步、`TagsIdentityPanel.vue` 对含企微身份客户增加「同步到企微」入口，以及 `AuditLogsView.vue` 补充 4 项审计中文标签；同步更新 `contracts/openapi/openapi.v1.yaml`（`paths: 337`，`schemas: 594`，无新增 Kafka 事件）。
- **验证证据（对应群组记录 `#32`、`#34`、`#45`、`#46`）**：
  - 前端验证（`#32` + `#45`）：`npm --prefix apps/crm-web run typecheck`（0 错误）、`npm --prefix apps/crm-web run lint`（`eslint . --max-warnings 0`，0 错误 / 0 警告）、`npm --prefix apps/crm-web run build`（`vue-tsc -b && vite build`，2743 modules，0 TS 错误）；修复 `#45` 前 `npm --prefix apps/crm-web run test -- --maxWorkers=2` 全量 **162/162 文件、768/768 用例通过**，修复 `DISABLED` 状态展示后复跑受影响套件 `tests/wecom-tag-sync-panel.test.ts`（6/6）与 `tests/wecom-view.test.ts`（18/18）合计 **2/2 文件、24/24 用例通过**。
  - OpenAPI 契约校验（`#34` + `#45`）：`yaml.safe_load` 及全量 `$ref` 递归解析通过（`paths: 337`，`schemas: 594`）。
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成/回滚 + EXPLAIN + 静态门禁（`#46`）：`./mvnw -B -pl apps/crm-api -am -Dtest='OfficialWecomTagClientTest,WecomTagSyncServiceTest,CustomerTagAdminServiceTest,ProductionProfileTest,WecomCallbackServiceTest,WecomPropertiesBindingTest,CustomerTagBatchLookupTest,BatchTagJob*Test' -Dit.test='CustomerApiIT,CustomerTagAdminApiIT,WecomTagSyncApiIT,WecomContactSyncApiIT,WecomCallbackConcurrencyIT,Wave1Wave2ExplainIT,RolePermissionApiIT,ProductionReferenceDataIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（Surefire `32/32`：`WecomTagSyncServiceTest` 10、`BatchTagJobExecutionServiceTest` 5、`CustomerTagBatchLookupTest` 4、`WecomCallbackServiceTest` 3、`ProductionProfileTest` 3、`OfficialWecomTagClientTest` 2、`CustomerTagAdminServiceTest` 2、`BatchTagJobApplicationServiceTest` 2、`WecomPropertiesBindingTest` 1；Failsafe `42/42`：`CustomerApiIT` 14、`RolePermissionApiIT` 7、`WecomTagSyncApiIT` 5（含 V63 迁移与回滚、D01 停用标签防护、`INSTITUTION` 403 全端点及跨组 404/隔离断言）、`WecomContactSyncApiIT` 5、`ProductionReferenceDataIT` 4、`Wave1Wave2ExplainIT` 3（含 `tagCodes` ANY/ALL EXPLAIN 索引断言）、`WecomCallbackConcurrencyIT` 2、`CustomerTagAdminApiIT` 2；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1408 files clean`）。
  - 未运行项：真实腾讯企微线上接口联调（无线上凭证，使用官方适配器 HTTP 单测 `OfficialWecomTagClientTest` + 本地 Mock MySQL 集成测试 `WecomTagSyncApiIT` 验证）与全站浏览器 E2E 回归未执行。

## [V61-CUSTOMER-TAG-UX-RBAC] 客户标签上移、建档/编辑直接打标与管家主管标签内容维护 — 2026-11-26
- **数据库迁移、回滚与 RBAC 种子同步（`V61`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V61__customer_tag_manage_permission.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V61__customer_tag_manage_permission_rollback.sql`，并同步更新 `db/prod/R__seed_rbac_prod.sql` 与 `db/dev/R__seed_auth_dev.sql`：新增客户标签内容维护权限 `customer:tag:manage`，仅授予 `ADMIN` 与 `MANAGER_LEAD`（普通管家 `MANAGER` 不授予此权限，且 `MANAGER_LEAD` 仍不具备全局 `dictionary:write`）。
- **后端标签管理与建档/编辑打标（`apps/crm-api`）**：
  - 新增 `CustomerTagController`、`CustomerTagAdminService`、`CreateCustomerTagRequest`、`UpdateCustomerTagRequest`，暴露 `/api/v1/customer-tags`（`GET` / `POST` / `PATCH /{id}` / `POST /{id}/disable` / `POST /{id}/enable`，`@PreAuthorize("hasAuthority('customer:tag:manage')")`，普通管家 `MANAGER` 调用返回 `403`）。
  - 扩展 `DictionaryItem#enable()`、`AuditService#ACTION_DICTIONARY_ITEM_ENABLE` 与 `DictionaryApplicationService` 按字典类型边界封装方法（`listItemsByDictCode` / `createItemForType` / `updateItemForType` / `disableItemForType` / `enableItemForType`）：跨字典类型 ID 返回 `404`、`MANAGER_LEAD` 访问全局 `/api/v1/dictionary-items` 仍返回 `403`、并发重复编码捕获 `DuplicateKeyException` 返回 `409`，完整校验同类型重名、乐观锁 `version` 并写入 `DICTIONARY_ITEM_CREATE/UPDATE/DISABLE/ENABLE` 审计日志。
  - `CreateCustomerRequest`、`UpdateCustomerRequest` 与 `CustomerApplicationService` 支持可选 `tagCodes`：`POST /api/v1/customers`、`PATCH /api/v1/customers/{id}` 与 `PUT /api/v1/customers/{id}/tags` 均强制校验新增标签必须属于启用中的 `customerTags` 字典（未知或已停用标签返回 `409`），编辑时非破坏性保留客户已有历史停用标签（D01）并记录 `CUSTOMER_TAGS_UPDATE` 审计日志。
- **前端体验升级与 OpenAPI 契约同步（`apps/crm-web` + `contracts`）**：
  - `CustomerFormDrawer.vue` 建档与编辑直接提供显眼「客户标签」多选（移除 `allow-create`、提交 `tagCodes`、停用历史标签只读回显、409 冲突透传后端具体原因，仅含 `customer:tag:manage` 展示「管理标签内容」）；`CustomerDetailDrawer.vue` 与 `TagsIdentityPanel.vue` 将客户标签由折叠面板上移至头部与概览独立卡片，加载实时 `customerTags` 字典并移除 `allow-create`。
  - 新增 `CustomerTagManageDialog.vue`（仅 `customer:tag:manage` 可新增/修改/停用/重新启用标签，兼容 `ARCHIVED` 只读展示、`@submit.prevent` 防表单误提交与 `sortNo` 有限数值兜底）；`CustomersView.vue` 与 `BatchTagJobsView.vue` 展示实时字典中文标签与超量计数（`+N`）并按 `customer:tag:manage` 控制「标签管理」入口；`WecomQuickActionsPanel.vue` 移除自由文本自定义标签输入、为未关联客户建档增加已有标签选择并在空字典时展示空态提示；`WecomSidebarView.vue` 加载实时 `customerTags` 字典；`AuditLogsView.vue` 与 `DictionariesView.vue` 补充 `DICTIONARY_ITEM_ENABLE` 审计中文展示。
  - 同步更新 `contracts/openapi/openapi.v1.yaml`（新增 `/api/v1/customer-tags*` 接口与 Schema，并在 `CreateCustomerRequest` / `UpdateCustomerRequest` 增加 `tagCodes`；无新增 Kafka 事件）。
- **验证证据（对应群组记录 `#12`、`#24`、`#26`）**：
  - 后端单测 + 真实 MySQL 8.4 Testcontainers 集成/回滚 + 静态门禁（`#24`）：`./mvnw -B -pl apps/crm-api -am -Dtest='CustomerTagAdminServiceTest,ProductionProfileTest,BatchTagJob*Test' -Dit.test='CustomerTagAdminApiIT,CustomerApiIT,DictionaryAdminApiIT,RolePermissionApiIT,BatchTagJobApiIT,ProductionReferenceDataIT,CustomerCompletionApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（Surefire `12/12`：`ProductionProfileTest` 3、`CustomerTagAdminServiceTest` 2、`BatchTagJobExecutionServiceTest` 5、`BatchTagJobApplicationServiceTest` 2；Failsafe `44/44`：`ProductionReferenceDataIT` 4、`RolePermissionApiIT` 7、`DictionaryAdminApiIT` 6、`CustomerApiIT` 13、`CustomerTagAdminApiIT` 2（含 V61 迁移与回滚复验）、`BatchTagJobApiIT` 6、`CustomerCompletionApiIT` 6；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1382 files clean`）。
  - 前端静态检查、测试与构建（`#12`）：`npm --prefix apps/crm-web run typecheck`（`vue-tsc --noEmit`，0 错误）、`npm --prefix apps/crm-web run lint`（`eslint . --max-warnings 0`，0 错误 / 0 警告）、`npm --prefix apps/crm-web run build`（`vue-tsc -b && vite build`，2740 modules，0 TS 错误）；`npm --prefix apps/crm-web run test -- --maxWorkers=2` 通过 `159/160` 文件（`751/752` 用例），唯一因并发 CPU 争抢触发 5s 超时的 `tests/fulfillment-redemption-create.test.ts` 立即复跑 `1/1` 文件（`2/2` 用例）通过（合计 `160/160` 文件、`752/752` 用例通过）；在并发 `V62` 变动合入后复验 `typecheck`/`lint`/`build` 仍全部退出码 0，且 7 个客户标签/建档/企微/批量打标测试文件 `7/7`（`40/40` 用例：`customer-tags-management` 6、`wecom-quick-actions-panel` 7、`wecom-sidebar-view` 8、`customer-api` 12、`customer-dictionary-d01` 3、`customer-attention-projects` 1、`batch-tag-jobs` 3）全部通过。
  - 独立安全与代码审查（`#26`）：只读审查通过（RBAC、租户与字典类型边界、乐观锁、审计日志与 `contracts/openapi/openapi.v1.yaml` 329 paths 校验均通过，未修改既有 `annualreport/` 改动）。
  - 未运行项：非本次涉及领域的其他后端 `*IT`、Kafka 集成测试与全站浏览器 E2E 未运行。

## [V62-CUSTOMER-UNIQUENESS-AND-SOURCE-CHANNEL] 客户唯一性多管家排查（无需合并）与来源/渠道管家主管统一管理 — 2026-11-26
- **数据库迁移与受控回滚（`V62`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V62__customer_source_channel_manage_permission.sql` 与回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V62__customer_source_channel_manage_permission_rollback.sql`：确保 `customerSources`（客户来源）与 `customerChannels`（客户渠道）系统字典类型存在，新增独立权限码 `customer:source_channel:manage` 并仅授予 `ADMIN` 与 `MANAGER_LEAD`（管家主管不授予全局 `dictionary:write`，普通管家 `MANAGER` 不含此权限）；同步更新 `db/dev/R__seed_auth_dev.sql` 与 `db/prod/R__seed_rbac_prod.sql`。
- **客户来源与客户渠道统一管理（`apps/crm-api` + `apps/crm-web`）**：
  - 后端新增 `CustomerSourceChannelController`、`CustomerSourceChannelAdminService`、`CreateCustomerOptionRequest`、`UpdateCustomerOptionRequest`，提供 `/api/v1/customer-sources`（及 `/{id}`、`/{id}/disable`、`/{id}/enable`）与 `/api/v1/customer-channels` 专用维护端点（`@PreAuthorize("hasAuthority('customer:source_channel:manage')")`，自动分配 `SRC_` / `CHN_` 编码、名称查重、乐观锁 `version`、停用保留历史客户只读与完整审计）；`DictionaryApplicationService` 重复名称冲突提示改为按字典名动态拼装。
  - 前端新增 `CustomerSourceChannelManageDialog.vue`（来源/渠道双页签维护），在 `CustomersView.vue` 与 `CustomerFormDrawer.vue` 按 `customer:source_channel:manage` 展示管理入口；普通管家在客户建档与编辑中来源、渠道严格仅支持下拉选择已启用项（无 `allow-create`，停用历史码只读回显）。合作渠道（`/channels`、`crm_partner_channel`）权限与流程保持不变。
- **客户唯一性 · 多管家排查（只读聚合，无需合并，`apps/crm-api` + `apps/crm-web`）**：
  - 后端新增 `CustomerUniquenessController`、`CustomerUniquenessService`、跨模块只读端口 `CustomerWecomFollowReader` / `WecomCustomerFollowReader` 与 DTO（`MultiStewardSummaryResponse`、`MultiStewardCustomerGroupResponse`、`CustomerStewardSummaryResponse`、`CustomerStewardRelationItemResponse`），新增 `GET /api/v1/customer-uniqueness/summary`、`GET /api/v1/customer-uniqueness/multi-stewards`、`GET /api/v1/customers/{id}/steward-relations`（`@PreAuthorize("hasAuthority('customer:merge')")`），并在 `GET /api/v1/customers` 与 `GET /api/v1/customers/{id}` 中仅对具备 `customer:merge` 权限的调用方附加 `addedStewardCount` / `addedStewardNames` / `addedStewards`。
  - 通过并查集综合识别同租户手机号盲索引（`mobile_hash`）、外部身份（`crm_customer_identity`）与企微多管家活跃跟进（`crm_wecom_contact`）；严格执行 A2 跨团队数据隔离：管家主管（`TEAM` 范围）仅可见与本组存在交集的多管家顾客，跨团队管家仅暴露姓名、团队名、关联类型、添加时间与 `inMyScope=false` 汇总，`customerId/customerNo/customerName` 置空且不参与跨组关键词检索，与本组无交集的分组完全不可见。历史合并与管理员更正后端 API 保持兼容。
  - 前端将 `/customers/merge`（`CustomerMergeView.vue`）及导航标题改造为只读「多管家排查 / 客户唯一性 · 多管家排查」（关闭合并扫描、新建与确认入口，仅对含 `customer:merge_reverse` 的管理员保留「历史合并更正（管理员）」二级页签）；在 `CustomersView.vue` 第 4 张统计卡与所属管家列展示「已加 N 位管家」徽章，在 `CustomerDetailDrawer.vue` 顶部与概览区展示「多管家添加情况（无需合并）」与「跨组仅汇总」标记。同步更新 `contracts/openapi/openapi.v1.yaml`（329 paths / 580 schemas，无新增 Kafka 事件）。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4.8 集成/回滚 + 静态门禁：`./mvnw -B -pl apps/crm-api -am -Dtest=ProductionProfileTest -Dit.test=CustomerSourceChannelAdminApiIT,CustomerUniquenessApiIT,CustomerTagAdminApiIT,CustomerApiIT,CustomerMergeApiIT,RolePermissionApiIT,ProductionReferenceDataIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` **BUILD SUCCESS**（Surefire `3/3`：`ProductionProfileTest` 3/3；Failsafe `36/36`：`ProductionReferenceDataIT` 4/4、`RolePermissionApiIT` 7/7、`CustomerApiIT` 13/13、`CustomerTagAdminApiIT` 2/2、`CustomerUniquenessApiIT` 1/1、`CustomerSourceChannelAdminApiIT` 2/2（含 V62 迁移与回滚复验）、`CustomerMergeApiIT` 7/7；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1382 files clean`）。
  - OpenAPI YAML 校验通过（329 paths / 580 schemas）。
  - 前端验证：`npm run typecheck`（0 错误）、`npm run lint`（`eslint --max-warnings 0`，0 警告）、`npx vitest run --maxWorkers=2 tests/customers-view.test.ts tests/customer-merge-reversal-view.test.ts tests/customer-source-channel-management.test.ts tests/customer-tags-management.test.ts tests/customer-dictionary-d01.test.ts tests/customer-attention-projects.test.ts tests/customer-api.test.ts tests/menu.test.ts tests/router-guard.test.ts tests/app-shell.test.ts`（`10/10` 文件 `129/129` 用例通过，修改样例类型后复跑 `customer-merge-reversal-view.test.ts` `3/3` 通过）、`npm run build`（`vue-tsc -b && vite build`，`8.20s` 成功）。
  - 未运行项：非本次涉及领域的其他后端 `*IT`、Kafka 集成测试、全量前端测试套件及浏览器 E2E 未运行。

## [CUSTOMER-SUPERVISOR-ASSIGN] 主管直接分配客户给管家（单客/批量立即生效、按管家筛选与分页归属日志） — 2026-11-26
- **后端直接分配与分页归属日志接口（`apps/crm-api`，无新增数据库表或 Flyway 迁移）**：
  - 新增 `CustomerAssignmentApplicationService`、`CustomerOwnershipLogController` 及 DTO（`AssignCustomerOwnerRequest`、`CustomerOwnerAssignmentResponse`、`BatchAssignCustomerOwnerRequest`、`BatchAssignCustomerOwnerResponse`、`CustomerOwnershipLogItemResponse`、`ScopedTransferItemResponse`），并在 `CustomerController`、`CustomerTransferReviewController` 暴露：
    - `POST /api/v1/customers/{id}/owner-assignment`（`customer:transfer`）：单客直接分配立即生效，校验 `DataScope` 行级可见性、目标管家资格（在职 `MANAGER`/`MANAGER_LEAD`）、角色/团队边界（`MANAGER_LEAD` 限本团队，`ADMIN` 可跨团队，普通管家 `403`）、客户乐观锁 `version`、同管家冲突与待审批转交单保护（存在 `PENDING` 转交单返回 `409`）；支持 `Idempotency-Key` 幂等重放，同事务更新 `crm_customer.owner_staff_id`/`team_id`、追加不可修改的 `crm_customer_owner_history`（`approval_ref=DIRECT_ASSIGN`）与审计日志 `CUSTOMER_OWNER_ASSIGN`，不覆盖历史订单、核销或现有任务快照。
    - `POST /api/v1/customers/owner-assignments/batch`（`customer:transfer`）：最多 100 位客户批量分配，按客户 ID 升序加 `FOR UPDATE` 行锁防死锁，同事务原子生效（原已归属目标管家自动标记 `SKIPPED`，任一客户冲突/越权整批回滚），同事务逐条追加 `crm_customer_owner_history`（`approval_ref=BATCH-...`）与审计日志，支持 `Idempotency-Key` 无损回放。
    - `GET /api/v1/customer-ownership-history` 与 `GET /api/v1/customer-ownership-transfers`（`customer:read`）：按 `CustomerScopeSupport` 行级范围分页查询全部已生效归属变更（标注 `DIRECT_ASSIGN`/`BATCH_ASSIGN`/`TRANSFER_APPROVAL`）及转交审批单，消除原前端 N+1 拉取；同步在 `OwnershipHistoryItemResponse` 增量返回 `approvalRef`/`changeMode`。
- **前端客户中心、分配弹窗与归属日志升级（`apps/crm-web`）**：
  - 新增 `src/views/customer/CustomerAssignDialog.vue`（单客与批量共用弹窗，展示原归属管家/批量跳过提示、必填分配原因与自动幂等键），接入 `CustomersView.vue`（新增「按所属管家筛选」、列表多选与「批量分配管家」栏、行级「分配管家」按钮）、`CustomerDetailDrawer.vue`（头部「分配管家」快捷按钮）与 `OwnershipPanel.vue`（保留原有两步转交审批流并补充「直接分配管家」入口与「变更方式」列）。
  - 升级 `CustomerOwnershipLogsView.vue` 为「已生效归属记录 + 转交审批单」双页签服务端分页视图（支持关键词/涉及管家/状态筛选及待审批转交单直接批准/拒绝）；修正 `src/router/menu.ts` 中同时持有 `MANAGER` 与 `MANAGER_LEAD` 双角色时菜单回退为普通管家菜单的问题。
- **契约与文档同步**：
  - 更新 `contracts/openapi/openapi.v1.yaml`（新增 4 条路径与对应 Schema，YAML 校验通过：318 paths / 575 schemas）与 `docs/01_需求与产品/11_业务流程与状态机.md`（第 7 节补充主管直接分配不变量）。
- **验证证据**：
  - 后端单测 + 真实 MySQL 8.4 集成测试 + 静态门禁：
    - `./mvnw -B -pl apps/crm-api -am -DargLine='-Dspring.flyway.target=61' -Dtest='CustomerAssignmentApplicationServiceTest' -Dit.test='CustomerOwnerAssignmentApiIT,CustomerTransferApiIT,CustomerApiIT,CustomerCompletionApiIT,CustomerTimelineApiIT' -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`：**BUILD SUCCESS**（Surefire `CustomerAssignmentApplicationServiceTest` `8/8`；Failsafe `32/32` 含 `CustomerOwnerAssignmentApiIT` `3/3`、`CustomerTransferApiIT` `3/3`、`CustomerApiIT` `12/12`、`CustomerCompletionApiIT` `6/6`、`CustomerTimelineApiIT` `8/8`；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1382 files clean`）。注：工作区存在非本次改动的并发未提交文件 `V62__customer_source_channel_manage_permission.sql` 含 SQL 语法错误，因此集成测试指定 `spring.flyway.target=61` 隔离验证。
  - 前端验证：`cd apps/crm-web && npm run typecheck && npm run lint && npx vitest run tests/customer-api.test.ts tests/customers-view.test.ts tests/customer-assign-dialog.test.ts tests/ownership-panel.test.ts tests/customer-ownership-logs-view.test.ts tests/menu.test.ts && npm run build` 全部通过（`typecheck` 0 错误、`eslint --max-warnings 0` 0 警告、Vitest `6/6` 文件 `44/44` 用例通过、`vue-tsc -b && vite build` `3.30s` 成功）。
  - 未运行项：非客户域其余后端 `*IT`、Kafka 集成测试与全站浏览器 E2E 未运行。

## [YOUZAN-ANNUAL-SNAPSHOT-PROTOCOL-FIX] 有赞年度历史快照 API 实测协议修复与密文防护 — 2026-11-26
- **有赞云只读快照接口协议对齐（`apps/crm-api`，无新增数据库迁移及 REST API / 前端契约变更）**：
  - `youzan.users.info.query`（`1.0.0`）：请求参数 `result_type_list` 修正为实测可用的 `[0, 1, 2, 9]`（修复 `[0, 1, 2, 3, 5]` 触发 `135400000 参数错误`）；响应解析从 `platform_info` 提取 `weixin_open_id`、从 `wechat_info` / `platform_info`（及顶层 `data.wechat_info`）提取 `union_id`，并保留对 `weixin_info` 的向后兼容回退。
  - `youzan.scrm.customer.get`：由有赞云网关不存在的 `3.1.2`（`gw_err_resp.err_code: 4005`）迁移至实测可用的 `3.0.0`；`PulledOrder` / `YouzanAnnualPullClient` / `YouzanAnnualSnapshotService` 透传订单 `buyer_info.fans_type` 与 `buyer_info.fans_id`，`RealYouzanAnnualPullClient` 支持按明文 `mobile` 或 `fans_type` + `fans_id` 查询并在首查失败/为空时交叉回退，两者均缺失时干净跳过不报伪错。
  - 有赞安全密文（`$...$1$`）防护：在 `RealYouzanAnnualPullClient` 订单/画像解析层及 `YouzanAnnualSnapshotService` 展示称呼与手机号归一化层双向过滤 `$...$1$` 密文字串，避免密文进入 `displayName` 或 `mobile`，由 `users.info.query`（`1.0.0`）与 `scrm.customer.get`（`3.0.0`）补全明文后按既定盲索引规则入库；新增纯合成数据单元测试 `RealYouzanAnnualPullClientTest`。
- **配置指南同步（`docs`）**：
  - 更新 `docs/07_上线与对接指引/有赞云历史订单快照与微信H5自动登录配置指南.md`：将接口表 `youzan.scrm.customer.get` 版本修正为 `3.0.0` 并注明按 `mobile` 或 `fans_type` + `fans_id` 查询，补充实测验证的 `result_type_list: [0, 1, 2, 9]`、`platform_info`/`wechat_info` 解析、测试店与生产同主体店铺 `union_id` 返回差异以及订单 `$...$1$` 密文自动过滤说明。

## [V60-YOUZAN-ANNUAL-SNAPSHOT-AND-CAUTH] 有赞历史订单只读快照与微信服务号 H5 统一免密自动登录 — 2026-11-26
- **数据库迁移与受控回滚（`V60`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V60__youzan_annual_snapshot_and_c_auth.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V60__youzan_annual_snapshot_and_c_auth_rollback.sql`：新增 `crm_youzan_annual_profile`（有赞买家年度只读镜像，OpenID/UnionID/手机号全部经 `BlindIndex` 存盲索引）、`crm_youzan_annual_order_snapshot`（有赞历史订单只读快照，严格与 `crm_order` 正式账本与权益发放隔离）、`crm_h5_c_identity_binding`（微信 OpenID 与买家永久绑定）、`crm_h5_sms_verification`（兜底短信验证码哈希校验）与 `crm_h5_c_visit_session`（统一入口纯有赞快照用户停留/页数埋点）；非空时回滚脚本通过 `SIGNAL SQLSTATE '45000'` 阻断 `DROP`。
- **有赞历史只读快照拉取与买家名称/微信身份同步（`apps/crm-api`）**：
  - 新增 `YouzanAnnualPullClient` / `RealYouzanAnnualPullClient`、`YouzanAnnualSnapshotService`、`AnnualReportYouzanSnapshotController` 与 `YouzanAnnualSnapshotMapper(.xml)`：自动按 `<=89` 天切窗调用 `youzan.trades.sold.get`（4.0.2）与 `youzan.trade.get`（4.0.2）拉取商品标题与金额，并可选调用 `youzan.users.info.query`（1.0.0）与 `youzan.scrm.customer.get`（3.1.2）补全买家姓名/昵称/性别与微信 OpenID/UnionID 盲索引；未开启真实有赞开关时显式拒绝，不伪造真实返回；`AnnualReportService` 生成 CRM 客户年终快照时自动按 `tid` 去重合并该客户名下的有赞历史只读快照单。
- **微信服务号静默自动登录与统一防泄密入口（`apps/crm-api` + `apps/crm-web`）**：
  - 新增 `WechatOfficialOAuthClient`、`AnnualH5SmsSender`、`AnnualReportClientAuthService` 与 `PublicAnnualReportAuthController`：服务号静默授权（`scope=snsapi_base`）仅调用 `/sns/oauth2/access_token`，绝不调用全局 `/cgi-bin/token` 避免顶掉已授权给有赞的第三方平台 Token；签发独立密钥与窄路径作用域的 C 端 `WHALE_H5_SESSION` `HttpOnly` Cookie（与 B 端 `CRM_TOKEN` 严格隔离，访问 B 端返回 `401`）；预留手机号验证码兜底开关（生产默认 `DISABLED`，验证通过后自动永久绑定当前微信 `openid`）。
  - 前端新增统一免密路由 `/h5/annual-reports/entry`（在 `PublicAnnualReportView.vue` 内兼容静默登录、兜底验证卡片、朋友圈安全统一入口分享防泄密）与 B 端「有赞历史订单只读快照与微信免密统一入口」管理面板（一键复制统一入口、状态展示、一键拉取快照弹窗、脱敏买家镜像抽屉）；同步更新 `contracts/openapi/openapi.v1.yaml`（310 paths）并新增 `docs/07_上线与对接指引/有赞云历史订单快照与微信H5自动登录配置指南.md`。
- **验证证据与未确认依赖**：
  - 后端单测 + 真实 MySQL 8.4 集成/回滚 + 静态门禁：
    - `./mvnw -B -pl apps/crm-api -am -Dtest=AnnualReportCopyEngineTest -Dit.test=YouzanAnnualSnapshotAndAuthApiIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`：**BUILD SUCCESS**（`AnnualReportCopyEngineTest` `3/3`、`YouzanAnnualSnapshotAndAuthApiIT` `1/1`、Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1351 files clean`）。
    - 上一轮同组集成/回滚验证：`V60YouzanAnnualRollbackIT` `2/2 passed`、`AnnualReportApiIT` `1/1 passed`、`WecomH5CardApiIT` `2/2 passed`；静态 SQL 门禁 0 命中；OpenAPI YAML 校验通过（310 paths / 563 schemas）。
  - 前端验证：`cd apps/crm-web && npm run typecheck && npm run lint && npx vitest run tests/annual-report-api.test.ts tests/public-annual-report.test.ts tests/menu.test.ts && npm run build` 全部通过（`typecheck` 0 错误、`eslint --max-warnings 0` 0 警告、目标 Vitest `3/3` 文件 `23/23` 用例通过、`vue-tsc -b && vite build` `4.63s` 成功）。
  - 未运行项：非本次涉及领域的其他后端 `*IT`、Kafka 集成测试、全量前端 157 文件复跑与真机微信服务号线上联调未运行（等待填入 Q11 有赞云自用应用凭证与服务号 `AppID/AppSecret` 后联调）。

## [V59-ANNUAL-REPORT-H5] 客户年终购买轨迹 H5 正式版（CRM 真实聚合、千人千面文案、埋点统计与管家跟进闭环） — 2026-11-26
- **数据库迁移与受控回滚（`V59`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V59__annual_report_h5.sql` 与受控回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V59__annual_report_h5_rollback.sql`：新增 `crm_annual_report`（不可变脱敏快照 + 43 位高熵 `share_key` + `ACTIVE -> DISABLED` 状态机，MySQL `BEFORE UPDATE` 触发器 `trg_annual_report_snapshot_guard` 锁定快照与状态转换）、`crm_annual_report_visit_session`（单会话可见停留时长、已看幕集合、逐幕停留、转化标记）与 `crm_annual_report_visit_event`（只增埋点事件流水，触发器 `trg_annual_visit_event_no_update` / `trg_annual_visit_event_no_delete` 禁止篡改与物理删除）。回滚脚本通过 `DELIMITER $$` 存储过程守卫，表非空时 `SIGNAL SQLSTATE '45000'` 拒绝 `DROP`。
- **后端领域聚合、千人千面文案、访问埋点与安全边界（`apps/crm-api`）**：
  - 新增 `com.whale.crm.annualreport` 模块（`AnnualReportController`、`AnnualReportService`、`AnnualReportTelemetryService`、`AnnualReportCopyEngine`、`AnnualReportMapper` + `AnnualReportMapper.xml` 及领域记录/DTO）：实时聚合真实 `crm_order`（同时覆盖已同步的 `YOUZAN` 与 `CRM` 已支付订单，金额整数分）、`crm_order_item`、`crm_entitlement`、`crm_redemption`（排除已审批冲正）、`crm_project`、`crm_institution`、`crm_followup`、`crm_customer_tag` 与员工信息，按 `Asia/Shanghai` 口径冻结年度脱敏快照。
  - 服务端内置差异化文案引擎（`POETIC` 诗意松弛 / `CLINICAL` 清醒专业 / `MINIMAL` 极简高效），支持基于客龄、单量、标签与性别称谓自动推断或人工指定，全量经过 `QuickReplyTextMasker` 隐私脱敏与医美禁用承诺词过滤。
  - C 端公开接口（`GET /api/v1/public/annual-reports/{shareKey}`、`POST .../telemetry`、`POST .../booking-intent`）零暴露 `tenantId/customerId/ownerStaffId/手机号/企微外部ID`；埋点服务端强校验单次增量上限（30s）与会话总时长上限（1h）；「通知专属管家预约」通过 `TaskApplicationService#createFromAnnualReportBookingIntent` 幂等创建 1 条归属 `owner_staff_id` 的 `FOLLOWUP` 跟进任务，绝不自动确认订单、预约或机构核销。
  - B 端管理与统计接口（单客生成、最多 200 人批量生成、分页检索、运营漏斗与 6 幕留存汇总、单份报告会话明细、停用链接）强制 `customer:read` / `customer:write`、`CustomerScopeSupport`（`ALL`/`TEAM`/`SELF`，拒绝 `INSTITUTION` 账号）、显式 `tenant_id`、CSRF、`Idempotency-Key`、乐观锁 `version` 与审计日志；`SecurityConfig` 与 `CsrfValidationFilter` 仅精确放行上述公开路径。
- **前端正式版 C 端 H5 与 B 端运营台（`apps/crm-web`）**：
  - 新增 `src/api/annualReport.ts`、`src/utils/paperLightboxEngine.ts`、`src/utils/annualPosterExporter.ts`、`src/views/PublicAnnualReportView.vue`（公开路由 `/h5/annual-reports/:shareKey`，完整保留纸雕灯影 6 幕交互、实时可见计时心跳 + `sendBeacon` 兜底、真实管家预约意向提交与 `750×1334` 离屏 PNG 海报导出）与 `src/views/AnnualReportsView.vue`（B 端路由 `/customers/annual-reports`，提供运营漏斗卡片、6 幕到达留存条、单客/批量生成弹窗、访问明细抽屉与停用链接）；更新 `src/api/http.ts`、`src/router/index.ts`、`src/router/menu.ts` 及 `contracts/openapi/openapi.v1.yaml`（新增 9 个路径与对应 Schema）。
- **验证证据与未确认依赖（Q11）**：
  - 后端单测 + 真实 MySQL 8.4 集成/回滚测试 + 静态门禁：`./mvnw -B -pl apps/crm-api -am -Dtest=AnnualReportCopyEngineTest -Dit.test=AnnualReportApiIT,AnnualReportMigrationRollbackIT,WecomH5CardApiIT,TaskApiIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify` 全部通过（Surefire `3/3`：`AnnualReportCopyEngineTest` 3/3；Failsafe `9/9`：`AnnualReportApiIT` 1/1、`AnnualReportMigrationRollbackIT` 2/2、`WecomH5CardApiIT` 2/2、`TaskApiIT` 4/4；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1323 files clean`）；静态 SQL 门禁 0 命中；OpenAPI YAML 解析通过（298 paths / 563 schemas）。
  - 前端验证：修复 `tests/menu.test.ts` 预期导航列表前全量 `vitest --maxWorkers=2` 运行 `157/157` 文件 `736/736` 用例全部通过；修复 3 处构建期 TS 类型问题后运行 `npm run typecheck && npm run lint && npx vitest run tests/annual-report-api.test.ts tests/public-annual-report.test.ts tests/menu.test.ts && npm run build` 全部通过（`typecheck` 0 错误、`eslint --max-warnings 0` 0 警告、目标 Vitest `3/3` 文件 `22/22` 用例通过、`vue-tsc -b && vite build` `3.57s` 成功产出 `AnnualReportsView` 与 `PublicAnnualReportView` 分包）。
  - 未运行项：非本次涉及领域的其他后端全量 `*IT`、Kafka 集成测试、全站浏览器 E2E 未运行；Q11 真实有赞线上密钥与沙盒联调仍未确认（`FEATURE_REAL_YOUZAN=false`，当前已兼容 `YOUZAN` 与 `CRM` 已支付订单，待 Q11 开放后自动贯通）。

## [USER-DATASCOPE-CUSTOMER-LIST-FIX] 修复新建管家建档后列表不可见、客户列表倒序与建档后筛选重置 — 2026-11-26
- **用户生命周期自动同步默认 `sys_data_scope`（保持 `sys_data_scope` 为行级权限唯一事实来源）**：
  - 新增 `DataScopeGrant`、`DefaultDataScopePolicy`，并在 `DataScopeRepository` / `MybatisDataScopeRepository` / `DataScopeMapper` / `DataScopeMapper.xml` 新增显式 `tenant_id` 隔离的 `replaceResourceScopes`（先按资源清理再写入）。
  - `UserApplicationService` 在创建员工（`create`）、变更角色（`assignRoles`）及管家主管调整团队（`update` 且 `teamId` 变更）时按既定角色矩阵同步 `customer` / `institution` 默认数据范围：`ADMIN`/`OWNER`/`OPERATION`/`OPERATION_LEAD`/`FINANCE` 授予 `customer=ALL`、`institution=ALL`；`MANAGER_LEAD` 授予 `customer=TEAM(teamId)`（未分团队降级 `SELF`）、`institution=ALL`；`MANAGER` 授予 `customer=SELF`、`institution=ALL`；`BUSINESS` 仅授予 `institution=ALL`（客户保持 `NONE`）；角色降权/清空或主管调组时自动收回旧范围防止权限残留；机构账号（`INSTITUTION`）不写 `sys_data_scope`。
- **存量数据范围回填迁移（`V58`）**：
  - 新增 `apps/crm-api/src/main/resources/db/migration/V58__backfill_user_default_data_scopes.sql` 与回滚脚本 `apps/crm-api/src/main/resources/db/rollback/V58__backfill_user_default_data_scopes_rollback.sql`，仅对缺失对应资源记录的存量内部员工幂等补齐默认 `customer`/`institution` 数据范围，不覆盖既有授权，不触碰机构账号。
- **客户列表排序、建档刷新与用户管理表单校验**：
  - `CustomerMapper.xml#search` 由 `ORDER BY c.id ASC` 调整为 `ORDER BY c.id DESC`，新建客户置顶显示。
  - `apps/crm-web/src/views/CustomersView.vue` 新增 `onFormSaved`：新建客户保存后清空关键词/等级/需跟进筛选并回到第 1 页刷新；编辑客户保存保留原筛选条件与页码。
  - 新增 `apps/crm-web/src/utils/userValidation.ts` 并在 `apps/crm-web/src/views/UsersView.vue`（配套 `apps/crm-web/tests/users-view.test.ts`）补齐新建/编辑用户前端字段校验与后端字段级 `ApiResponse.ErrorDetail` 错误内联映射。
- **验证证据（无 REST API / Kafka 契约变更）**：
  - 后端单元 + 真实 MySQL 8.4 集成测试 + 静态门禁：
    - `./mvnw -B -pl apps/crm-api -Dtest='DefaultDataScopePolicyTest,UserApplicationServiceTest,DataScopeServiceTest,Customer*Test' -Dit.test='CustomerApiIT,CustomerCompletionApiIT,UserAdminApiIT,DataScopeServiceIT,TaskApiIT,ExportApiIT,V58BackfillDataScopeMigrationIT,Wave1Wave2ExplainIT' -DfailIfNoTests=false verify`：Surefire `32/32 passed`；Failsafe 中 `DataScopeServiceIT`（5/5）、`Wave1Wave2ExplainIT`（3/3，含 `ORDER BY c.id DESC` EXPLAIN 门禁）、`UserAdminApiIT`（15/15）、`ExportApiIT`（9/9）、`CustomerApiIT`（12/12）、`TaskApiIT`（4/4）、`CustomerCompletionApiIT`（6/6）共 `54/54 passed`；随后修正 `V58BackfillDataScopeMigrationIT` 建库连接账号并执行 `./mvnw -B -pl apps/crm-api -Dtest='DefaultDataScopePolicyTest,UserApplicationServiceTest' -Dit.test='V58BackfillDataScopeMigrationIT' -DfailIfNoTests=false verify`，Surefire `15/15 passed`、`V58BackfillDataScopeMigrationIT` `1/1 passed`；Checkstyle `0 violations`、SpotBugs `0 bugs / 0 errors`、Spotless `1298 files clean`。
  - 前端全量验证：`npm --prefix apps/crm-web run typecheck && npm --prefix apps/crm-web run lint && npm --prefix apps/crm-web run test -- --maxWorkers=2 && npm --prefix apps/crm-web run build` 全部通过（`typecheck` 通过、`eslint` 0 警告、`vitest` `155/155` 文件 `730/730` 用例通过、`vite build` 成功）。
  - 未运行项：非本次涉及领域的其他后端 `*IT`、Kafka 集成测试与浏览器 E2E 未重跑。

## [PROD-REF-CITIES] 生产参考种子补齐常用城市字典项 — 2026-11-26
- **参考数据补齐（0 API / 前端 / 表结构 / 权限契约变更）**：
  - 在 `apps/crm-api/src/main/resources/db/prod/R__seed_reference_prod.sql` 中为 `cities`（城市）字典补齐 49 个国内常用城市（前 13 个编码 `BJ`–`QD` 与开发环境种子完全一致，补齐其余省会/直辖市及主要一二线城市与港澳台），使用 `ON DUPLICATE KEY UPDATE option_code=VALUES(option_code)` 保证 Flyway 可重复迁移幂等且不覆盖运营已维护的名称、排序或启停状态；`customerStatuses`（客户状态）保持空项由业务方自行在后台维护。
  - 更新 `ProductionProfileTest` 与 `ProductionReferenceDataIT`，验证生产种子包含 49 个唯一启用城市项、不包含 `customerStatuses` 预设项，且重复执行种子脚本不覆盖运营对城市项的自定义修改。
- **验证命令与实际结果**：
  - `./mvnw -B -pl apps/crm-api -Dtest=ProductionProfileTest -Dit.test=ProductionReferenceDataIT -DfailIfNoTests=false verify`：通过（Surefire `3/3`、Failsafe MySQL 8.4.8 Testcontainer `4/4`、Checkstyle 0 违规、SpotBugs 0 缺陷/0 错误、Spotless 1296 文件洁净）。

## [QUERY-PERF-V56-V57] Wave 1 / Wave 2 / Phase 2 SQL 与索引性能优化及 EXPLAIN 回归闭环 — 2026-11-26
- **补充优化与 2.0.4 增量发布**：
  - `CustomerMapper#countSearch(TEAM)` 与 `CustomerSegmentMapper#countMembers(TEAM)` 将 `TEAM` 范围计数重构为两路 `UNION`（走 `idx_customer_1` + `idx_customer_team`），`ExportDataMapper` 的 `customerWhere` / `followupWhere` 移除冗余 `LEFT JOIN sys_user`，并补充 `Wave1Wave2ExplainIT` 回归与 `EXPLAIN ANALYZE` 断言；同步收录独立零依赖演示目录 `demos/year-end-h5/`（含纸雕灯影交互、文案引擎与本地 `localStorage` 访问埋点监控面板，仅用合成示例客户）。
  - 补充验证：`./mvnw -B -pl apps/crm-api -Dtest='Customer*Test,Export*Test' -Dit.test='Wave1Wave2ExplainIT,Customer*IT,Export*IT' -DfailIfNoTests=false verify` 全部通过（Surefire 36/36、Failsafe 90/90、Checkstyle 0 违规、SpotBugs 0 缺陷、Spotless 1294 文件洁净）。
- 变更摘要（**0 REST API / DTO / Kafka 契约变更，0 前端 `apps/crm-web` 变更**）：
  - **Wave 1（`V56__query_speed_accuracy_schema.sql` / `V56__query_speed_accuracy_schema_rollback.sql`）**：
    - Schema 治理：对齐 `crm_ai_job` / `crm_ai_chat_delegation_run.conversation_id` 与 `crm_wecom_staff_account` / `crm_wecom_callback_event.wecom_userid` 至 `VARCHAR(192)`；预置 Wave 2 增量列及安全回填（保留 `updated_at` 与 `version`）：`crm_customer.next_followup_at`（`idx_customer_next_followup` 等 5 个索引）、`crm_youzan_event.external_customer_id`/`campaign_id`（`idx_youzan_event_ext_customer`、`idx_youzan_event_campaign`、`idx_youzan_event_received`）、`crm_risk_case.customer_id`/`order_id`（`idx_risk_case_customer`、`idx_risk_case_created`）；补齐客户关联反向索引（`idx_customer_tag_reverse`、`idx_customer_identity_ext`）、报表 7 大事实表覆盖/时间窗索引（含重建 `crm_payment_txn.idx_payment_txn_1` 与 `crm_entitlement_ledger.idx_entitlement_ledg_1`）、企微消息/联系人索引（`idx_wecom_message_sent_id`、`idx_wecom_message_conv_id`、`idx_wecom_message_link_direct`、`idx_wecom_contact_consent_cover`）、任务/通知/轮询/企微分析/审计/合并/登录索引（含重建 `crm_task.idx_task_1`、`idx_task_customer`、`idx_task_due_poll`、重建 `crm_notification.idx_notification_unread`、`crm_audit_log.idx_audit_log_action`/`idx_audit_log_tenant_id` 等），并清理 `crm_followup_batch_order.idx_followup_batch_order_item` 与 `crm_merge_resource_snapshot.idx_merge_resource_snapshot_1` 冗余索引。
    - 准确性与分页改造：P0-A1 客户等级计算接入 `WecomArchiveService#activeArchiveScope`（`CustomerTierMapper#selectMetrics` 去 `'MOCK_ARCHIVE'` 硬编码）；P0-A2 `WecomMapper` 群消息当前同意补齐 `rpe.status='ACTIVE'`；P0-A5 `TradingMapper#orderStats` 实付口径改 `SUM(paid_fen)`；P0-P1 `ReportingMapper#operationsFactUnion` 下推 `staff_id`/`institution_id` 并移除 `operationsEnrichedFacts` 6 表重复 `LEFT JOIN`；P0-A3 `ExportDataMapper` 核销导出对齐 `crm_customer` 实时归属与 `c.deleted=0` 且 4 类导出全部切换 `id > #{q.afterId}` keyset 游标分页（并对 `page` 分支补充条件 `FORCE INDEX (PRIMARY)` 与 `STRAIGHT_JOIN crm_customer` 消除 `afterId=0` / 中段退化及 filesort）；`FollowupMapper#scopedWhere` 补 `c.deleted=0`；`CustomerMapper#search` 与 `CustomerSegmentMapper#memberColumns` 移除 `GROUP_CONCAT` 改用 `CustomerTagMapper#selectTagCodesByCustomerIds` 批量 `IN` 装配。
  - **Wave 2（基于 `V56` 结构的运行时读写切换）**：
    - P0-A4（`crm_customer.next_followup_at`）：在 `FollowupApplicationService`、`MergeApplicationService` 与 `MergeReversalApplicationService` 中于 `FOR UPDATE` 行锁下通过 `CustomerMapper#refreshNextFollowupAt` 同事务维护（不递增 `version`）；`CustomerMapper#scopedWhere`（`followupDueBefore`）及 `CustomerMapper#search` / `CustomerSegmentMapper#memberColumns` 切换至 `c.next_followup_at`。
    - 有赞结构化列与风控关联：在 `YouzanEventService#ingest` 与官方 webhook `replacePayloadAndProcess`（`YouzanMapper#updateEventPayload`）中同步写入 `crm_youzan_event.external_customer_id`/`campaign_id`，全面替代 `YouzanMapper` 与 `RiskCaseMapper` 全部 `JSON_EXTRACT`；`RiskCaseRegistrationService`、`YouzanEventService`、`YouzanManagementService` 及合并/撤销流程同事务维护 `crm_risk_case.customer_id`/`order_id`（含 `CLOSED` 状态防护），`RiskCaseApplicationService#customerLinks` 拆分为 `RiskCaseMapper#findResolvedCustomerLinks` 主键快路径与 5 路延迟回退查询（`findDelayedOrderCustomerLinks` / `findDelayedYouzanIssueCustomerLinks` / `findDelayedRefundCustomerLinks` / `findDelayedEntitlementCustomerLinks` / `findDelayedRedemptionCustomerLinks`），彻底消除 `REGEXP`/`CAST`/`JSON_EXTRACT`。
    - 客户时间线 9 路 SQL 下推游标与 `LIMIT`：`CustomerTimelineApplicationService` 将 `(cursorOccurredAt, cursorSourceId)` 与 `LIMIT` 下推至 **8 个 Mapper 的 9 条 SQL**（`CustomerOwnerHistoryMapper#selectTimelineRowsByCustomerId`、`FollowupMapper#selectTimelineEvents`、`TaskMapper#selectTimeline`、`TradingMapper#selectTimelineOrders`、`FulfillmentMapper#selectTimelineAppointments`、`FulfillmentMapper#selectTimelineRedemptions`、`RefundMapper#findTimelineRefunds`、`CustomerTierMapper#selectTimelineHistory`、`ComplaintMapper#findTimelineCases`），消除 9 次 `COUNT(*)` 与 JVM 全量翻页循环。
  - **Phase 2（`V57__phase2_high_yield_schema.sql` / `V57__phase2_high_yield_schema_rollback.sql` 及运行时切换）**：
    - 新增 `crm_appointment` 里程碑时间戳（`confirmed_at` / `checked_in_at` / `service_done_at` 与 `idx_appointment_confirmed_at` / `idx_appointment_checked_in_at` / `idx_appointment_service_done_at`），`FulfillmentMapper` 状态流转同步维护，`ReportingMapper` 与 `WecomAnalyticsMapper` 彻底消除 `crm_appointment_history` 扫描。
    - 新增 `crm_youzan_order_latest` 投影表（`uk_youzan_order_latest_1` / `idx_youzan_order_latest_event` / `idx_youzan_order_latest_ev_id`），`YouzanEventService` 与 `YouzanMapper#selectLatestOrderEvents`/`#countDistinctExternalOrders` 切换读取最新外部订单事件。
    - 新增 `crm_wecom_contact.is_unlinked` 存储生成列与 `idx_wecom_contact_unlinked`，`WecomMapper#selectContacts` 改用覆盖索引 deferred-join 分页，并将 `TEAM` 范围子查询重构为两路 `UNION`（`idx_customer_1` + `idx_customer_team`）彻底消除 `crm_customer` 全表扫描；`crm_wecom_data_gap.from_seq` 补齐 `NOT NULL DEFAULT 0` 并于 `WecomMapper#insertGap` 使用 `COALESCE(#{fromSeq}, 0)`。
    - 新增 `crm_material.latest_revision_id`（`idx_material_latest_revision`）与 `crm_material_tag` 标签表（`uk_material_tag_1`、`idx_material_tag_reverse`、`idx_material_tag_material`），`MaterialApplicationService` 同步维护，重构 `MaterialMapper#search`/`#count` 消除 `ROW_NUMBER()` 与 `tags_json LIKE`。
    - 补齐 `crm_order.external_order_dedupe_key`（`uk_order_external_dedupe`）、`crm_institution.idx_institution_archived_status_id` 与 `crm_profile_change_request.idx_profile_change_status_id`，并将 `P2OperationsGovernanceSchemaIT` 回滚前基线限定至 `target("21")`。
  - **测试加固与验证（M6 联合回归与 EXPLAIN 闭环）**：
    - 新增 `apps/crm-api/src/test/java/com/whale/crm/platform/Wave1Wave2ExplainIT.java`（独立 MySQL 8.4.8 Testcontainer + Flyway V57 + 1,000 行偏斜种子 + 49 表 `ANALYZE TABLE` + 真实 MyBatis XML/注解 Mapper 81 条 `EXPLAIN` 全表扫描门禁与 8 组 `EXPLAIN ANALYZE` 断言，覆盖 `WecomMapper#selectContacts(TEAM)`、`ExportDataMapper#page(ALL 0/mid/tail & TEAM 0)` 与 `CustomerSegmentMapper#searchMembers`/`#countMembers`）。
    - 修复 `ComplaintBrokerReliabilityIT`、`AiChatDelegationBrokerReliabilityIT`、`WecomDualConsumerKafkaE2EIT` 在共享 MySQL 容器下因 `crm_outbox_event` 积压超过单批 `batchSize=50` 导致的单次 `relayOnce()` 饥饿问题（改用 `relayUntilSent(...)` 轮询并保留全部 Kafka/DLQ/业务断言）。
- 验证命令与实测结果：
  - `./mvnw -B -pl apps/crm-api -am verify`：Surefire `635 passed`（0 failures, 0 errors, 0 skipped）、Failsafe `503 passed`（0 failures, 0 errors, 0 skipped；含 `QuerySpeedAccuracySchemaRollbackIT` `2/2`、`Phase2SchemaRollbackIT` `2/2`、`Wave1Wave2ExplainIT` `3/3` 及全部 Kafka/WeCom IT）、Spotless `1294 files clean` / Checkstyle `0 violations` / SpotBugs `0 bugs / 0 errors`。
  - `npm --prefix apps/crm-web run typecheck && npm --prefix apps/crm-web run lint && npm --prefix apps/crm-web run test -- --maxWorkers=2 && npm --prefix apps/crm-web run build`：`typecheck` 通过、`eslint` 0 errors/warnings、`vitest` `155` files / `725` tests passed、`vite build` 成功（`2.08s`；注：默认并发 Worker 下 `tests/exports-view.test.ts` 曾出现单次 5000ms 超时，限流 `--maxWorkers=2` 下全部通过）。
  - 静态 SQL/Mapper 检查（`CURRENT_DATE|CURDATE`、`JSON_EXTRACT`、`REGEXP|CAST(`、`crm_appointment_history`、`ROW_NUMBER(|tags_json\s+LIKE`、`GROUP_CONCAT`）：全部 0 命中。

## [UI-COPY-CLEANUP-2.0.3] 全站 UI 中文文案与内部术语全面清理及离线增量更新包（2.0.3） — 2026-11-25
- **全站 UI 中文文案与内部术语全面清理**：
  - 全面清理前端页面、弹窗、表单校验与状态提示、后端校验/业务错误文案（含新增 `ValidationMessages.properties`）及 `apps/ai-worker` 回复/提取/知识库提示文案（共涉及前后端与 AI Worker 278 个文件），统一将内部枚举编码、研发术语与生硬英文状态替换为面向业务人员的规范中文表达，保持既有权限边界、状态机、API 契约与数据结构不变（无新增数据库表或 Flyway SQL 迁移）。
- **离线增量更新包构建与一键升级/回滚能力（2.0.3）**：
  - 新增 `scripts/build-incremental-patch.sh`（默认 `CRM_PATCH_VERSION=2.0.3`、`CRM_TARGET_PLATFORM=linux/amd64`，支持 `--skip-tests`）：一次构建 JAR、前端 `dist` 与 4 个应用层 `linux/amd64` 镜像（`whale-crm/crm-api:2.0.3`、`whale-crm/web:2.0.3`、`whale-crm/ai-worker:2.0.3`、`whale-crm/clamav-proxy:2.0.3`），显式使用 `docker save --platform` 导出唯一 `images/application.tar.gz` 并解析 `manifest.json` 严格校验全部为 `linux/amd64` 且不含 MySQL/Redis/Kafka/MinIO/ClamAV/Caddy 等 6 个第三方中间件镜像；一次同时产出 openEuler 增量包（`release/whale-crm-patch-2.0.3-openeuler-linux-amd64.tar.gz` + `.sha256`）与 Windows 增量包（`release/whale-crm-patch-2.0.3-windows-amd64.zip` + `.sha256`），均附 `PATCH_README.md` 与 `manifest.txt`（`INSTALL_MODE=incremental-patch`）。
  - 新增 openEuler/Linux 一键增量升级脚本 `infra/production/apply-patch.sh` 与 Windows 一键增量升级脚本 `infra/production/windows/apply-patch.bat` / `apply-patch.ps1`：支持传入现有安装目录（或自动探测含 `.env` 与 `compose.yml` 的目录）、架构与 Docker Compose v2 预检、升级前默认自动备份 MySQL（`mysqldump | gzip` + `gzip -t`）与 `.env`（支持 `BACKUP_ENCRYPTION_PASSPHRASE` 加密）、写入 `runtime/last-release-version`、载入应用镜像、**仅原地更新 `.env` 中 `RELEASE_VERSION`**（不覆盖任何密钥、端口、HTTPS 证书或 URL 配置）、仅滚动更新 4 个应用容器（`up -d --no-deps --wait`，不重启 6 个中间件容器）、HTTPS `/login` 探活（含本地 `ca.crt` 与 `127.0.0.1` 回环兜底）及 `--rollback` / `-Rollback`（含 `--to-version` / `-ToVersion`）一键回滚。
  - 同步收口 `scripts/build-windows-installer.sh`、`scripts/build-windows-installer.ps1` 与 `infra/production/tests/test_windows_installer.py`，使全量 Windows 安装包与增量更新包脚本及产物互不干扰；新增 `infra/production/tests/test_incremental_patch.py` 覆盖构建契约、Linux 沙盒升级/自动探测/加密备份/独立 `docker-compose` v2/回滚与缺镜像阻断、Windows PowerShell/BAT 静态安全与编码契约及增量产物校验。
- **验证证据**：
  - 后端静态质量检查：`./mvnw -B -pl apps/crm-api spotless:check checkstyle:check spotbugs:check` 通过（Spotless 1276 文件洁净、Checkstyle 0 违规、SpotBugs 0 缺陷 0 警告）；后端全量单元测试 593/593 通过。
  - 前端与 AI Worker：`apps/crm-web` 全量 155/155 测试文件 725 项用例、typecheck、lint 与 build 全部通过；`apps/ai-worker` `pytest` 122 项用例全部通过。
  - 基础设施与增量脚本测试：`python3 -m unittest discover -s infra/production/tests -p 'test_*.py' -v` 共 51 项用例（覆盖 `test_devsecops_contracts.py`、`test_incremental_patch.py`、`test_m2_scripts.py`、`test_windows_installer.py`；构建增量包前 50 passed / 1 skipped，0 失败）。注：全量后端 `*IT` 集成测试与浏览器 E2E 本次未重跑。

## [AUTH-SELF-PASSWORD] 个人账号右上角菜单与自助修改密码（复杂度策略 + 双重确认 + 会话吊销） — 2026-11-25
- **后端密码复杂度策略与自助改密接口**：
  - 新增 `PasswordPolicyProperties`（`app.security.auth.password-policy.*`，支持通过环境变量配置最小/最大长度、大小写、数字、特殊字符、禁止空白、禁止包含用户名、禁止与原密码相同；默认 8–64 位且包含大写、小写、数字、特殊字符，不含空白/控制字符，不包含当前用户名且不可与原密码相同）与 `PasswordPolicyService`；`SecurityConfig` 注册该配置类并在 `application.yml` 提供显式配置项。
  - `AuthController` / `AuthApplicationService` 新增 `GET /api/v1/auth/password-policy` 与 `POST /api/v1/auth/password`（任意已登录账号可改自身密码；强制双提交 CSRF；严格校验原密码、新密码复杂度与两次新密码一致；原密码校验失败返回 `400 VALIDATION_FAILED` 且独立审计 `PASSWORD_CHANGE_FAILED` 与隔离限流 `429 RATE_LIMITED` + `Retry-After: 900`，不误退当前会话；校验通过后经 `UserMapper.updatePasswordHash` 按 `tenant_id/id/version/ACTIVE` 乐观锁更新 BCrypt 哈希，吊销该用户全部登录会话、清理 `CRM_TOKEN`/`CRM_CSRF` Cookie 并写入脱敏审计 `PASSWORD_CHANGE`；`ChangePasswordRequest.toString()` 脱敏）。无新增数据库表或 Flyway 迁移。
- **前端右上角账号菜单与修改密码弹窗**：
  - `ShellHeader.vue` 将右上角用户名入口升级为可访问下拉菜单（支持点击展开、外部点击与 Escape 关闭），聚合「修改密码」与「切换身份」；`ShellSidebar.vue` 底部同步补充「修改密码」快捷入口。
  - 新增 `src/utils/passwordPolicy.ts` 与 `src/components/shell/ChangePasswordDialog.vue`，接入 `AppShell.vue`、`src/api/auth.ts` 与 `src/stores/auth.ts`：打开弹窗时从 `/api/v1/auth/password-policy` 拉取当前策略，实时展示密码强度条与逐项复杂度勾选状态，要求输入原密码与两次一致的新密码；修改成功后清理本地会话并引导跳转登录页重新登录。
- **契约与验证证据**：
  - OpenAPI：`contracts/openapi/openapi.v1.yaml` 新增 `/auth/password-policy`、`/auth/password` 及 `PasswordPolicy`/`PasswordPolicyEnvelope`/`ChangePasswordRequest`（校验通过：290 paths / 543 schemas / 169 唯一 `operationId` / 0 断链 `$ref`）。
  - 后端：全量单元测试 593/593 通过（含新增 `PasswordPolicyServiceTest` 6、`AuthApplicationServiceTest` 12、`LoginAttemptServiceTest` 7）；真实 MySQL 8.4 Testcontainers + 本机 Redis 集成测试 `AuthApiIT`（20/20）、`CsrfProtectionIT`（5/5）、`UserAdminApiIT`（14/14）共 39/39 通过；Checkstyle 0 违规、SpotBugs 0 缺陷、Spotless 1276 文件洁净。注：非认证/用户模块 `*IT` 与浏览器 E2E 未重跑。
  - 前端：`npm --prefix apps/crm-web run typecheck`、`lint`（0 警告）、`build`（exit 0）全部通过；认证/顶栏/改密相关 8 个测试集（`password-policy`、`change-password-dialog`、`shell-header`、`app-shell`、`auth-store`、`router-guard`、`notifications-routing`、`csrf`）104/104 全部通过。注：工作区存在与本任务无关的并发文案改动导致其他 26 个无关前端测试集失败，本次未修改无关模块。

## [P3-DEPLOY-2.0.2-OPENEULER] 欧拉系统（x86_64）40000+ 高位端口默认 HTTPS 一键离线安装包 — 2026-11-25
- **默认强制 HTTPS + 40000+ 高位端口共存隔离**：
  - `infra/production/compose.yml`、`.env.example` 与 `install.sh` 将默认宿主机端口调整为 **HTTPS `48443`**（主入口，强制 `COOKIE_SECURE=true` + `Strict-Transport-Security`）与 **HTTP `48080`**（仅返回 `308 Permanent Redirect` 跳转 HTTPS 并隐藏 `Server` 头，不提供明文 HTTP 业务服务）；其余 MySQL/Redis/Kafka/MinIO/ClamAV/crm-api/ai-worker/web 全部运行在独立 Docker 内部网络，零宿主机端口暴露，避免与服务器既有系统冲突。
  - 新增 `infra/production/Caddyfile.sameorigin`：高位端口/IP HTTPS 模式下同源承载业务与文件预签名上传下载（`/{$FILES_BUCKET}/*` 转发 `minio:9000` 并通过 `header_up -Cookie` 剥离会话 Cookie、限制 `max_size 15MB`、注入 `nosniff` 与 `Content-Security-Policy: sandbox`；`/minio*` 通过前置 `handle` 块严格返回 `403`；`/health` 返回 `200 ok`），消除浏览器的多端口自签证书二次阻断与跨源 CSP 问题。
  - `install.sh` 新增 `--ip`、`--auto-ip`、`--tls-cert` / `--tls-key`、`--open-firewall`、`--skip-port-check` 参数及交互式一键引导：默认生成本机 10 年 Root CA（`runtime/certs/ca.crt`）与 825 天带 SAN（服务器 IP / `127.0.0.1` / `localhost`）的 HTTPS 证书（私钥仅本机生成，不进入安装包），也支持直接挂载自有商业/企业 SSL 证书；安装前自动校验 `x86_64` 架构与端口占用，适配 openEuler SELinux（`:ro,z` + `chcon`）、`firewalld`、`net.ipv4.ip_forward` 提示及不含 `--env-from-file` 的旧版 Docker Compose v2（通过标准输入传递管理员初始化凭据，零命令行明文密钥）。
  - 针对高负载多系统共存服务器，调优 `compose.yml` 中 `kafka`、`clamav`、`ai-worker` 与 `crm-api` 的健康检查启动宽限与超时参数（保持总内存限额 `7872m`、`cap_drop: ["ALL"]` 与网络隔离契约不变）。
- **纯 `linux/amd64` 打包架构修复与前端兜底**：
  - `scripts/build-installer.sh` 在 `docker save` 时显式附加 `--platform "$PLATFORM"` 并在导出后逐个解析 `images/*.tar.gz` 内的 `manifest.json` 校验镜像架构与目标平台一致，修复 Apple Silicon 宿主机 containerd 多架构索引存储下可能误导出非 `linux/amd64` 镜像的问题，并在成包后清理暂存目录。
  - `apps/crm-web` 新增 `src/utils/cryptoFallback.ts`（在 `main.ts` 与 `src/api/file.ts` 接入），为缺失 WebCrypto 安全上下文的环境补充 RFC 4122 v4 `crypto.randomUUID` 与纯 TypeScript FIPS 180-4 SHA-256 兜底并补齐 NIST 向量单测。
- **交付产物与验证证据**：
  - 交付：`release/whale-crm-2.0.2-openeuler-linux-amd64.tar.gz`（953 MB）及 `release/whale-crm-2.0.2-openeuler-linux-amd64.tar.gz.sha256`（归档白名单校验零 `.env`/私钥/凭据，全部 10 个镜像已验证为 `linux/amd64`）。
  - 前端：`npm --prefix apps/crm-web run typecheck`、`lint`（0 警告）、`test`（153 个测试文件 / 713 项测试全部通过）、`build`（exit 0）。
  - 基础设施与脚本：`bash -n`、`docker compose config --quiet`、`python3 -m unittest infra/production/tests/test_devsecops_contracts.py infra/production/tests/test_m2_scripts.py -v`（24/24 OK）；`ai-worker` `pytest`（122 passed）；后端 `ProductionProfileTest`（3/3 passed）。
  - 隔离端到端实测（对最终 `release/whale-crm-2.0.2-openeuler-linux-amd64.tar.gz` 全新解压执行 `--ip 127.0.0.1 --https-port 48443 --http-port 48080`）：SHA-256 与归档白名单通过；10 个 `linux/amd64` 容器全部 `healthy`；管理员初始化与 `initial-admin.txt`（600）通过；`http://127.0.0.1:48080/login` 返回 `308 -> https://127.0.0.1:48443/login`；`https://127.0.0.1:48443/health` 返回 `200 ok`、`/login` 返回 `200` + `HSTS max-age=31536000` 且无 `Server` 头、`/minio/health/live` 返回 `403`；`docker ps` 确认仅 Caddy 暴露 `48080`/`48443`；`runtime_smoke.py --url https://127.0.0.1:48443 --cacert runtime/certs/ca.crt --files` 全链路通过（验证码登录、Secure Cookie 会话、客户/知识查询、前端路由、同源 HTTPS 预签名上传、ClamAV 扫描 CLEAN、素材修订预签名下载与内容一致性校验）；`./install.sh backup` 生成 MySQL/MinIO/`.env` 备份并通过 `gzip -t`；`./install.sh stop` 停止容器并保留数据卷。
  - 未重跑/受限说明：本次未修改后端业务 Java/SQL，未重跑全量后端 `mvn verify`（Testcontainers）；本机为 7.65 GiB Apple Silicon Docker Desktop 跨架构 QEMU 模拟全部 10 个 `linux/amd64` 容器，停服后的二次 `./install.sh start` 阶段因模拟环境 CPU/内存争用触发 ClamAV 健康检查超时（741s），真实兆芯 x86_64 欧拉服务器原生运行、公网商业证书与外部真实供应商联调仍须在目标机现场复验。

## [YOUZAN-PREFLIGHT-FIX] 有赞真店联调前置问题与工作台可用性修复 — 2026-11-24
- **已关闭订单状态机与历史未付异常收敛**：`YouzanOrderMapper` 显式区分 `TRADE_CLOSED`/`TRADE_CLOSED_BY_USER` 为 `ORDER_CLOSED`（不再归入 `ORDER_PENDING`）；`YouzanEventService` 对未建内部订单的 `ORDER_CLOSED` 干净标记 `PROCESSED` 并记录 `YOUZAN_ORDER_CLOSED_IGNORED` 审计（不建草稿单、不生脏异常），当且仅当关闭事件无外部支付时间（`paidAt == null`）时通过 `YouzanMapper.selectOpenUnpaidIssuesByExternalOrderForUpdate`（`FOR UPDATE OF i`）自动收敛同店铺同外部单号下历史 `ORDER_PENDING`/`WAIT_PAY` 事件滞留的 `OPEN` 异常（`MAPPING_FAILED`/`CUSTOMER_UNRESOLVED`/`RETRY`）为 `RESOLVED` 并保留原 `last_error` 与审计 `resolvedHistoricalIssueIds`（严格保护 `ORDER_PAID` 历史异常与 `PAYMENT_MISMATCH` 不被误关）；对已建未支付单（`DRAFT`/`PENDING_PAYMENT`）按 `X(crm_customer) → X(crm_order)` 锁序自动取消为 `CANCELED`；对已支付历史订单严格禁止覆盖并记入 `PAYMENT_MISMATCH` 异常队列提醒人工核实退款。
- **官方 Webhook 控制台验证与退款事件保护**：`YouzanOfficialMessageParser` 仅在 `test=true` 时允许缺失 `kdt_id`；`YouzanConfigRepository`/`YouzanConfigMapper` 新增 `selectShopConfigsByClientId`；`YouzanOfficialMessageService` 对 `test=true` 控制台验证消息允许店铺尚未开启 `syncEnabled` 或测试报文带占位/缺失 `kdt_id` 时按 `clientId` 候选完成租户全局开关与 MD5 严格验签后无副作用返回 200 ACK，正式消息仍强制要求 `syncEnabled=true`；对 `eventType` 含 `refund` 的有赞退款推送直接记录告警日志并返回 200 ACK，避免触发无效 30 秒查单。
- **结构化非敏感订单视图与前端工作台优化**：`YouzanOrderView` 新增 `externalCustomerId`、`paidAt`、`orderTypeCode`、`campaignId`、`campaignBatchId`、`items` 非敏感结构化字段（保留 `payloadMaskedJson` 仅向 `integration:youzan:manage` 暴露的安全边界）；前端 `YouzanOrdersView.vue` / `YouzanOrderDetailDrawer.vue` 优先展示结构化字段（真实订单无顶层昵称/手机号时直接显示 `yz_open_id`）、「匹配并同步」向人工客户链接/SKU 映射面板传递待匹配外部标识建议（仍不自动保存）、「立即拉取订单」支持 `hasMore=true` 游标续跑超过 500 单，并对齐前后端 `order:read || integration:youzan:manage` 及 `campaign:write || integration:youzan:manage` 权限表达式（含 `router/index.ts`、`menu.ts` 与纯 `integration:youzan:manage` 打开已映射订单详情时不误调 `GET /api/v1/orders/{id}` 避免 403 报错）。
- **离线官方协议全链路真库集成测试**：新增 `YouzanOfficialFlowIT`（共享真实 MySQL 8.4 Testcontainers + 本机回环 HTTP Stub 模拟 `/auth/token`、`youzan.trades.sold.get/4.0.2`、`youzan.trade.get/4.0.2`），离线端到端验证官方 MD5 控制台验证、退款推送保护、`RealYouzanAdapter` 连接测试与拉单、`ORDER_CLOSED` 全状态分支、历史未付异常自动收敛与历史已付异常保护、非管理员结构化视图脱敏边界及官方回调查单入账闭环。
- **部署与契约同步**：`infra/production/compose.yml` 与 `infra/production/.env.example` 透传 `YOUZAN_API_BASE_URL`、`YOUZAN_TRADE_DETAIL_VERSION`、`YOUZAN_API_MIN_INTERVAL_MS`；同步更新 `contracts/openapi/openapi.v1.yaml`（新增 `YouzanOrderLine` 及 `YouzanOrderView` 扩展字段）、`docs/04_接口与集成/42_有赞订单对接.md` 与 `docs/04_接口与集成/42_有赞字段映射与交接.md`。无新增数据库表或 Flyway 迁移。
- **验证证据**：
  - 后端全量单元测试：582 run / 0 fail / 0 err / 0 skip。
  - 后端有赞真库集成与静态检查：`./mvnw -B -pl apps/crm-api -Dtest='com.whale.crm.integration.youzan.**.*Test,com.whale.crm.integration.config.*Test' -Dit.test='*Youzan*IT' -DfailIfNoTests=false verify` 通过（有赞/配置单测 105 run / 0 fail；真实 MySQL 8.4 `*Youzan*IT` 23 run / 0 fail / 0 err / 0 skip，含 `YouzanOrderQueryIT` 5、`YouzanConnectionApiIT` 3、`YouzanOfficialFlowIT` 3、`YouzanV8SchemaIT` 2、`YouzanAcceptanceIT` 8、`YouzanMultiTenantAndOfficialE2EIT` 2；Checkstyle 0 违规、SpotBugs 0 缺陷、Spotless 1271 文件洁净）。注：非有赞模块 `*IT`、`ai-worker` 与浏览器 E2E 本次未重跑。
  - 前端全量验证：`npm --prefix apps/crm-web run typecheck`、`lint`（0 warning）、`test`（153 个测试文件 / 713 项测试全部通过）、`build`（exit 0）。
  - 基础设施与 OpenAPI：`python3 -m unittest infra/production/tests/test_devsecops_contracts.py` 10/10 OK；OpenAPI 288 paths / 540 schemas / 2550 `$ref` / 0 断链 / 167 唯一 `operationId`。
  - 未验收项：真实有赞生产/沙箱店铺直连（Q11）、公网 HTTPS 回调、出口 IP 白名单及退款同步仍需待真实店铺授权就绪后验收。

## [AUDIT-M5B] M5B 有赞作用域与事件可靠度、V51 消费者、AI/Dify SSRF 防护与 OpenAPI 契约同步 — 2026-11-22
- **C-02 / C-04（有赞租户隔离与签名/重定向安全）**：移除 `integration/youzan/**` 与 `ai/**` 非 Controller 层 `TenantContext.currentTenantId()` 回退，显式传入 `tenantId`（覆盖 `insertSkuMapping`/`insertCustomerLink` 多租户测试与 `YouzanAdapterRouter.webhookAdapter(long)`）；`YouzanOfficialSignature.valid` 增加 `clientId`/`clientSecret` `isBlank()` 校验；`MockYouzanAdapter` 修复时间戳窗口 `Long.MIN_VALUE` 溢出；`YouzanRemoteClient` 禁用重定向（`followRedirects(false)` / `followSslRedirects(false)`）。保留 `HEAD=8c535f5` 官方 webhook 协议、增量游标与原有测试不变。
- **H-SEC-09 #7（有赞 SQL `DataScope` 下推与 `/orders` 列表防崩）**：`YouzanMapper.xml` 新增 `customerScopeCondition`/`eventScopeCondition` 下推（关联 `crm_customer` 且限定 `c.deleted = 0`）与 `allScopeOrderFilter`（`ALL` 作用域跳过已软删客户映射订单以保持 `items`/`total` 一致），覆盖 `selectLatestOrderEvents`、`countDistinctExternalOrders`、`selectScopedIssues`/`countScopedIssues`、`selectScopedCustomerLinks` 及 `reconcile-report` 统计；移除 `YouzanManagementService.orders` 外层 `@Transactional(readOnly = true)` 避免 `orderService.detail` `404` 触发 `UnexpectedRollbackException` HTTP 500，`scopeQuery` 委托 `CustomerScopeSupport.queryOf` 并在 `ScopeQuery`/`queryOf` 对 `teamIds` 确定性排序；`/integrations/youzan/orders` 列表越界行不再抛出 404 导致整页失败（保持 200 跳过不可见行），`/orders/{id}` 明细越界仍返回 404；控制层权限放宽为 `hasAnyAuthority('integration:youzan:manage', 'order:read'|'campaign:write')`；保留 4 参 `issues(tenantId, status, page, size)` 供 `RiskSourceSynchronizer` 使用；补齐 `SELF`/`TEAM`/`NONE`/`INSTITUTION` 与软删客户集成与单测。
- **H-BIZ-06 + M3 §3.2（有赞事件处理与锁序对齐）**：`YouzanEventService.doProcess` 对同秒（`!externalUpdatedAt.isAfter(lastExternalUpdatedAt)`）`ORDER_PAID` 事件在映射订单处于 `DRAFT` 或 `PENDING_PAYMENT` 时不再丢弃；且在 `existing == null` 与 `existing != null` 两条分支均先通过 `scopeSupport.requireVisibleForUpdate` 获取 `X(crm_customer)` 锁，再调用 `orderService`/`paymentService` 获取 `X(crm_order)` 锁。
- **C-05（V51 `wecom.message.archived.v1` 消费者与 OkHttp 安全）**：`AiChatDelegationEventConsumer` 与 `ComplaintEventConsumer` `PAYLOAD_FIELDS`/`WECOM_FIELDS` 补全 `conversationType` 与 `roomRosterVersion`，`validateEnvelope` `event_id`/`correlation_id` 上限放宽至 192；校验 DB 消息来源后对 `"ROOM"` 消息干净去重跳过且不入 DLQ、不生成托管运行或客诉命中记录（`AiChatDelegationService.receive` 同步增加 `"DIRECT"` 防御守卫）；`AiWorkerClient` 与 `KnowledgeWorkerClient` 禁用重定向并限制超时 `[1s, 300s]`；补齐基于真实 `WecomArchiveService` → `OutboxRelayService` JSON 的单元测试及真实 MySQL+Kafka 集成测试（`ComplaintBrokerReliabilityIT`、`AiChatDelegationBrokerReliabilityIT`）。
- **H-SEC-02（Java + Python `ai-worker` SSRF 与 DNS Rebinding 防护）**：
  - **Java**：抽取共享 `AiOutboundUrlValidator` 并接入 `AiProviderConfigService`（`save`/`testConnection`/`workerConfig`）与 `DifyKnowledgeConfigService`（`save`/`testConnection`/`workerConfig`/`datasetWorkerConfig`），对 `http`/`https` 均执行 DNS 解析校验，阻断回环、链路本地/`169.254.169.254`、RFC1918 私网、CGNAT `100.64.0.0/10`、多播、未指定及 IPv6 ULA/映射/NAT64/6to4 地址，支持 `application.yml` 中 `app.ai.provider.allowed-hosts` 与 `app.ai.knowledge.allowed-hosts` 运维白名单（白名单仍严禁链路本地/元数据/多播/未指定地址）。
  - **Python（`apps/ai-worker`）**：新增 `app/services/ssrf.py`（`validate_outbound_base_url` + 基于 `httpcore.SyncBackend` 的 `PinnedHTTPTransport` 防止 DNS Rebinding），收敛 `schemas.py`/`config.py` 长度与 `timeout_seconds <= 300` 上限，并在 `extractor.py`、`reply.py`、`knowledge.py`、`knowledge_dataset.py` 统一启用 `follow_redirects=False` 与 `trust_env=False`，新增 `tests/test_ssrf.py`。
- **H-BUG-05 / H-BUG-07**：`ConfirmAiJobRequest.decision` 补充 `@NotNull` 且 `AiJobApplicationService` 显式校验 `CONFIRM`/`REJECT`（`LinkedHashMap` 兼容空 `taskId`）；`AiChatSendFenceMapper`/Repository/Service 新增 `releaseForRetry`（仅在 `finishSend` 提交 `RETRY_SEND` 时释放 `in_flight=0, finished_at=NULL`，`SENT`/`FAILED` 仍锁定防重发）。
- **OpenAPI 与总账文档同步（`contracts/openapi/openapi.v1.yaml`）**：保留 `HEAD=8c535f5` 全部有赞协议章节；补齐 M4 缺失的 6 个端点（`/channels/{id}/status`、`POST/GET /channels/{id}/financial-change-requests`、`GET /channel-change-requests`、`POST /channel-change-requests/{reqId}/review`、`POST /risks/sync`）及 11 项 Schema/约束/权限/描述同步（`PartnerChannel` 脱敏模型、`UpdateUser.password` 非空白正则、`UpdateRolePermissions` 多段权限正则、`RecordSettlementPaymentRequest.amountFen >= 0`、`OperationsInstitutionRow.pendingReconciliationFen` 权限门控、`notification-settings/wecom-mappings*` `wecom:mapping:manage`、`followup-automation/*` `followup:policy:manage` 与 `ALL` 范围说明、`/customer-segments/{id}` 创建者或 `ALL` 范围、`/customers/{id}/timeline` 分领域权限、`/files/upload-init` `MERCHANT_RESOURCE`、`/customer-merge-candidates/scan`）；全量校验 288 paths、167 唯一 `operationId`、0 断链 `$ref`、100% Controller 路由覆盖。
- **验证证据（最终代码与契约定稿后实测）**：
  - Python `apps/ai-worker`：`(cd apps/ai-worker && .venv/bin/pytest -q)` 通过（110 passed）。
  - Java `crm-api` 静态检查与打包：`./mvnw -B -pl apps/crm-api compile test-compile spotless:check checkstyle:check spotbugs:check package` 通过（Spotless 1248 文件洁净、Checkstyle 0 违规、SpotBugs 0 缺陷、544 个单元测试 0 失败 0 跳过、产物 jar 不含 dev seeds）；`YouzanManagementServiceTest` 3 次独立 JVM 复跑（9 run / 0 fail）与 `Customer*Test,Wecom*Test`（38 run / 0 fail）全部通过。
  - Java MySQL/Kafka 集成测试分批全部通过：`Batch A`（`YouzanOrderQueryIT,YouzanAcceptanceIT,YouzanConnectionApiIT,YouzanV8SchemaIT`：18 run / 0 fail / 0 err）、`Batch B`（`AiProviderConfigApiIT,DifyKnowledgeConfigApiIT,AiJobRepositoryIT,AiReplySuggestionApiIT,KnowledgePublicationApiIT,AiChatDelegationMigrationRollbackIT`：19 run / 0 fail / 0 err）、`Batch C1`（`ComplaintDetectionIT,ComplaintBrokerReliabilityIT`：4 run / 0 fail / 0 err）、`Batch C2`（`AiChatDelegationBrokerReliabilityIT`：1 run / 0 fail / 0 err）。
  - OpenAPI 校验：`contracts/openapi/openapi.v1.yaml` 校验 288 paths、167 unique operationIds、0 broken `$ref`、100% Controller 路由覆盖。

## [AUDIT-M5A] M5A 企微归档/撤回修复与多模块高危缺陷收敛 — 2026-11-21
- **迁移 `V55`**：`V55__wecom_archive_scope_and_recall_repair.sql` 修复企微归档范围与撤回字段约束（`V1..V55` 冻结）。
- **核心修复**：覆盖 `C-02`（多模块租户参数显式化）、`C-03` / `C-04`（企微回调与归档身份/作用域边界）、`H-SEC-01`、`H-SEC-04`、`H-BUG-08`、`H-BUG-11`、`H-BUG-12`。

## [AUDIT-M4] M4 渠道财务审批、机构/风险/客户与素材权限收敛 — 2026-11-21
- **迁移 `V54`**：`V54__channel_change_requests_and_crm_fixes.sql` 新增渠道财务双人复核变更单表并收口历史约束。
- **核心修复**：覆盖 `C-02`、`H-SEC-03`、`H-SEC-09 #2..#6`、`H-BIZ-07`、`H-BIZ-08`、`H-BUG-06`、`H-BUG-09`、`M-BE-01`（含渠道 `/status` 启停、`/financial-change-requests`、`/channel-change-requests` 与 `/risks/sync`）。

## [AUDIT-M3] M3 交易/核销/退款/对账并发与状态机加固 — 2026-11-21
- **迁移 `V53`**：`V53__trading_settlement_fixes.sql`。
- **核心修复**：覆盖 `C-01`、`C-02`、`H-BIZ-01..05`、`H-SEC-09 #1/#7`、`M-BE-06..08`（不含后续交由 M5B 落地的 `H-BIZ-06` 与有赞 `DataScope` 下推）。

## [AUDIT-M1] M1 平台安全与基础数据作用域加固 — 2026-11-21
- **迁移 `V52`**：`V52__platform_security_and_indexes.sql`。
- **核心修复**：覆盖 `H-SEC-05..08`、`H-BUG-02..04`、`H-BUG-10`、`M-BE-01..05`。

## [AUDIT-DEFERRED] 已明确延后项（不在 M1-M5B 范围实施）
- `M-BE-04`、`M-BE-09`、`M-BE-10`、`M-BE-11`、`M-BE-12`、`M-BE-13`、`M-BE-14`、`M-BE-15`（含 `AiJob` `claimed_by` fence 与安全正则扩展等）及独立 `LOW` 优化项。

## [YOUZAN-OFFICIAL-FIX] 有赞云官方协议、同步可靠性与前端工作台修复 — 2026-11-20

### 实现
- **官方消息入口协议对齐**：`POST /api/v1/webhooks/youzan/official` 改为按有赞云官方消息推送协议返回 HTTP `200 OK` 与 `{"code":0,"msg":"success"}`（含 `test=true` 控制台验证消息与非订单事件的无副作用应答）；缺失请求头或过深 JSON 返回 `400`，签名无效/店铺未启用/开关禁用返回 `401`，>1MB（含 chunked）返回 `413`；支持 `full_order_info.order_info.tid`、原始 JSON `msg` 免误解码、大小写 MD5 验签、重试复用 `externalOrderNo`、`selectPendingEvents` 过滤 `process_attempts < 10` 防队头饥饿及并发重试不回退 `PROCESSED`/已分类事件。
- **官方客户端与适配器可靠性**：`YouzanRemoteClient` 统一解析有赞网关错误 `gw_err_resp`（`4202`/`4203`/`40010`/`4101`），常规取 Token 使用 `refresh=false`、强制刷新使用 `refresh=true`，`testConnection` 遇 Token 过期自动强制刷新重试一次；`RealYouzanAdapter` 直接映射 `youzan.trades.sold.get/4.0.2` 完整 `full_order_info`（全状态校验 `yz_open_id`/`payment`/`pay_time`）以避免 N+1 查单消耗配额，缺失字段自动回退 `youzan.trade.get`，超过 100 页上限时首轮即触发窗口二分，`nextCallAt` 改为 `ConcurrentHashMap`。
- **增量同步与店铺配置**：`YouzanIncrementalSyncService` 与调度同步支持续跑未完成游标、自动裁剪超过 90 天窗口、跨多页循环拉取、仅在窗口完整完成后推进 `lastSyncAt`、按 UTC 时间校验店铺租约、校验 `renewSyncJobLease` 并阻止 `finishJob` 复活已过期 `FAILED` 任务、整秒二分高密度窗口；`YouzanConnectionService` 对凭证入参自动去空白，仅修改 `shopName`/`syncEnabled` 时保留既有已验证 Token 与 `CONNECTED` 状态；`YouzanEventService.complete()` 改用真实管理员 `userId` 记录审计与异常解决人。
- **前端工作台与测试修复**：
  - `YouzanShopConfigPanel.vue` 对店铺/应用/密钥去空白并拒绝新建纯空格密钥，补齐 `kdt_id`/`grant_id`、无容器自用型应用凭证、Token 保留规则、动态 `webhookPath` 及公网 HTTPS/HTTP 200 ACK/IP 白名单指引。
  - `YouzanOrdersView.vue` 在切换店铺时清空 `syncCursor` 防止跨店游标泄漏，统一使用去空白 `normalizedShopId` 查询订单/报表/映射面板；真实店铺激活时区分 `CONNECTED`/`FAILED`/`UNVERIFIED` 状态并切换横幅、前置条件与手动同步提示，不再误报模拟同步或 Q11 禁用。
  - `YouzanSyncPolicyPanel.vue` 使用 `formatDateTime` 格式化最近调度时间并在保存后同步 `saved.nextRunAt`；`YouzanSkuMappingPanel.vue` 与 `YouzanCustomerLinkPanel.vue` 编辑态禁用外部自然键输入。
  - 修正 `youzan-view.test.ts` 中异常重试幂等键断言误取参数下标 `[0]` 的缺陷，补齐空白密钥拦截、游标重置、真实店铺提示、策略时间同步及编辑态自然键禁用测试。
- **契约与文档同步**：更新 `contracts/openapi/openapi.v1.yaml`（新增 `YouzanOfficialWebhookAck`、修正官方 webhook 200 响应及旧 Mock 描述）、`docs/04_接口与集成/42_有赞订单对接.md` 与 `docs/04_接口与集成/42_有赞字段映射与交接.md`。无新增数据库表或 Flyway 迁移。

### 验证
- 后端 `./mvnw -B -pl apps/crm-api -Dit.test='*Youzan*IT' verify`：**BUILD SUCCESS**；409 项后端全量单元测试 + 15 项有赞真实 MySQL 8.4 Testcontainers 集成测试（`YouzanOrderQueryIT` 3、`YouzanConnectionApiIT` 3、`YouzanV8SchemaIT` 2、`YouzanAcceptanceIT` 7）全部通过；Checkstyle 0 违规、SpotBugs 0 问题、Spotless 0 格式问题（1205 个文件清洁）。注：未运行非有赞模块的 `*IT` 套件与 E2E。
- 前端全量验证通过：`apps/crm-web` 执行 `npm run typecheck`、`npm run lint`（0 warning）、`npm run test`（142 个测试文件、615 项测试全部通过）与 `npm run build`（exit 0）。
- OpenAPI 契约解析校验通过：283 个 paths、2503 个本地 `$ref` 全部解析（0 缺失）、160 个 `operationId` 无重复。
- 真实有赞生产/沙箱店铺联调（Q11）、退款同步及目标服务器公网 HTTPS 与出口 IP 白名单仍需在部署环境配置验收。

## [V53-OPS] 本地 ClamAV 异常恢复与扫描验收 — 2026-11-14

### 实现
- 定位开发容器曾因 OOM 退出且没有自动恢复策略；`infra/compose/docker-compose.dev.yml` 为 ClamAV 增加 `restart: unless-stopped`，健康检查启动宽限期由 30 秒调整为 120 秒。
- 保留既有病毒库卷、私有本机端口和 `ConcurrentDatabaseReload=no`，不关闭扫描、不放宽文件校验，不修改生产 Compose 或业务代码。自动恢复不替代宿主机内存容量治理。

### 验证
- 容器实际为 `running/healthy`，`clamdscan --ping 3` 返回 PONG；Docker 中实际重启策略为 unless-stopped。
- 使用项目真实 `ClamAvScanner` 经本机 3310 端口扫描：标准 EICAR 测试样本返回 FOUND，合成正常文本返回 CLEAN。临时文件已删除，无新增数据库迁移或事件。
- 本次仅本地 Compose 运维变更，未重新宣称全栈测试或生产安全验收通过；既有全栈结果见 V53。

## [V53] 企微话术、AI 采纳与真实转化分析 — 2026-11-13

### 实现
- 新增 `GET /api/v1/reports/wecom-analytics` 与 `/operations/wecom-analytics` 页面，统一日期、员工和当前客户数据范围；复用 `report:read`，机构/跨员工/匿名访问拒绝。
- 话术分别统计使用、复制、SDK 成功/失败/未知、编辑和 AI 关联；复制不进入 SDK 成功率分母，不冒充发送或客户已读。
- AI 采纳率使用期内创建候选的当前 ACCEPTED 状态，区分驳回、待处理、编辑采纳、Mock、复制及 SDK 结果；采纳不冒充发送。
- 到院率按同一 CONFIRMED 预约批次中的真实 CHECKED_IN 历史；复购按期内不同已支付订单及付费客户，同一订单多个支付流水不重复。无分母比率返回 null，不伪造 0。
- `unsupportedMetrics` 明确标记已读率、H5 打开率、聊天因果归因和话术业务因果效果，不以同客户+时间窗编造转化因果。Mock 候选数量单列。
- 采用只读报表 Repository/Mapper 与 Application Service，SQL 显式 tenantId、时间窗口、客户范围及员工/历史归属快照。无新增表、迁移、事件或 Redis 集群依赖；OpenAPI、字段字典、统计口径、测试矩阵和 DEC-2026-006 已同步。

### 验证
- V53 定向 `verify` 通过：4 项聚合服务单元测试、2 项真实 MySQL 接口测试，覆盖真实计数、分子分母、订单去重、时间窗口、ALL/TEAM/SELF、员工筛选和匿名/机构/跨员工拒绝；Checkstyle、SpotBugs、Spotless 为 0 问题。
- 前端全量 142 个文件、608 项测试通过，lint/typecheck/build 通过；分析页 4 项定向测试覆盖真实指标、能力边界、无权限和筛选失败清空旧结果。
- OpenAPI 283 个 paths、2503 个本地引用全部解析、160 个 operationId 无重复。正式生产转化目标与真实模型/供应商效果仍需业务与供应商环境验收。
- 首轮总回归发现 4 项跨企业消息计数交叉；已为 `/messages` 增加可选 corpId 过滤并同步归档页，未删除历史或放宽权限。对应原企微验收 7 项单独通过；最终 `./scripts/verify.sh` 退出 0：后端 359 项单元测试、350 项真实 MySQL/Kafka 集成测试，前端 142 个文件 608 项测试及 Worker 70 项全部通过，Checkstyle/SpotBugs/Spotless 为 0 问题。

## [V52] 完整 AI 意图类型与风险门禁 — 2026-11-13

### 实现
- 在既有 8 类基础上补齐 `PACKAGE_QUERY、VISIT_INTENT、AFTERCARE_FEEDBACK、FOLLOWUP_REQUIRED、RISK_EVENT`，Worker、CRM 二次校验、前端中文标签和 OpenAPI 统一支持 13 类，旧类型继续兼容。
- Mock 分类升级为 `mock-rules-v2`，区分风险、退款、投诉、预约、到院、套餐、价格、复购、术后反馈、回访等候选；证据消息取实际支持分类的输入，不再固定第一条客户消息。
- 高风险分类强制对应风险标记与人工核实动作；未知类型在 CRM 终止，不保存候选。事实状态、金融写禁止、证据归属和字典范围校验保持有效。
- 新增五类不扩大自动托管白名单，RISK_EVENT 等高风险类型不能自动发送；群聊仍不进入单聊客户归因。无新增数据库表、迁移、事件类型或业务自动写入。
- 前端会话、监管与托管页复用统一意图标签；同步 AI 设计、API、字段字典和开发进度。真实模型分类质量与线上延迟仍需独立验收。

### 验证
- Worker 全量 70 项测试通过，覆盖五类新增意图、真实证据消息、完整 Prompt、伪造高风险字段及既有回复/知识链。
- CRM 定向 `verify` 通过：23 项相关单元测试、4 项真实 MySQL 集成测试通过；Checkstyle、SpotBugs、Spotless 为 0 问题。
- 前端全量 141 个文件、604 项测试通过，lint/typecheck/build 通过；OpenAPI 2495 个引用无缺失、159 个 operationId 无重复。Redis 继续单实例。

## [V51] 企微群聊身份、授权与会话归档 — 2026-11-13

### 实现
- 新增 `crm_wecom_room`、`crm_wecom_room_participant` 及 V51 迁移/回滚。管理员以 version、幂等键、CSRF、理由和变更前后成员快照管理完整群清单；退群保留 REMOVED 历史，停用只影响新归档。
- 官方适配器识别 roomid 与真实发送人，不再默认阻断群聊。仅当群已确认、发送人属于当前清单、存在当前可采员工且外部参与人同意有效时保存加密正文；否则记录明确缺口，不伪造成员或客户映射。
- 群消息的 customerId/externalUserid/staffUserId 为空，新增 conversationType、senderWecomUserid、senderStaffUserId 和 roomRosterVersion；不将多客户群聊归入单聊 AI 托管、投诉归因或单聊 AI 任务。
- 消息列表支持 conversationId 与可选 corpId 过滤；ALL/TEAM/SELF 在 SQL 内通过当前可采参与人检查。历史外部参与人撤回同意后不可借退群绕过读取限制，撤回与媒体沿用群上下文和授权。
- 新增 `GET /integrations/wecom/rooms` 与 `PUT /integrations/wecom/rooms/{roomId}`；归档事件新增 conversationType 和 roomRosterVersion，缺口契约补充群上下文、发送人和停用类型。同步 OpenAPI、字段字典、部署/测试文档与决策登记。
- 前端归档中心新增群聊授权工作台、完整清单编辑与状态展示；会话/监管页区分群聊与单聊，显示实际发送人，不把企微账号当作姓名。通用状态文案不再固定误标为“模拟”。

### 验证
- 后端定向 `verify` 通过：14 项单元测试、8 项真实 MySQL 集成测试；覆盖完整清单、成员变化、停用、幂等/CSRF/版本、全部外部参与人同意、越权、撤回乱序、媒体和 V51→V50 回滚。Checkstyle、SpotBugs、Spotless 均为 0 问题；本记录不把定向结果冒充全量后端验收。
- 前端全量 140 个文件、603 项测试通过，lint/typecheck/build 通过；群聊组件和会话页定向 19 项通过。
- OpenAPI 282 个 paths、2495 个本地引用全部解析、159 个 operationId 无重复；事件 JSON Schema 解析通过。本地后端已更新到 V51 且健康检查通过；实际桌面浏览器检查群聊空状态、页签和登记表单，无乱码且字段对齐，未提交虚构群配置或发送消息。
- 真实企业群成员清单来源、线上 SDK/客户端效果及生产权限未做供应商验收；Redis 继续单实例，无新增集群要求。

## [V50] 企微消息撤回同步与乱序补全 — 2026-11-13

### 实现
- 新增 `crm_wecom_recall_event` 及原消息 `recalled_at/recall_message_id`，提供 V50 迁移/回滚。官方 action=recall、msgtype=revoke 通过 revoke.pre_msgid 关联原消息，不作为普通文本入库。
- 撤回校验员工、external_userid 与 sender_type；只追加撤回控制事实并更新撤回元数据，不删除正文、私有对象、媒体或历史。重复事件按唯一键幂等，冲突不覆盖。
- 支持先收到撤回再收到原消息：PENDING_ORIGINAL 保存控制事件并记录 RECALL_ORIGINAL_PENDING 缺口，原消息合法入库后自动补全 APPLIED、更新撤回状态并关闭缺口。
- 同步响应新增 recalled 计数；消息列表/内容返回 recalledAt 与 recallMessageId，会话页提示撤回且原文仅在当前授权范围保留审计。
- 实际生效追加 `wecom.message.recalled.v1` 至 `crm.wecom.events.v1`，payload 只含 messageRef、recalledAt、recallMessageId，不含正文；事件 Schema 和缺口类型已同步。
- 更新 OpenAPI、字段字典、归档/API 文档与进度。无新增 Redis 集群需求；真实供应商撤回时序仍需环境验收。

### 验证
- V50 相关 14 项单元测试与 4 项真实 MySQL 集成测试通过，覆盖身份不一致、重复撤回、乱序补全、缺口关闭、Outbox 唯一性、内容读取、媒体回归与迁移回滚；Checkstyle/SpotBugs/Spotless 为 0 问题。
- 前端会话页 15 项定向测试通过，typecheck/lint/build 通过；此前全量 599 项通过。
- OpenAPI 2479 个引用无缺失，157 个 operationId 无重复；事件 JSON Schema 解析通过。未调用真实供应商撤回接口。

## [V49] 企微媒体分片 Worker 与多类型会话 — 2026-11-13

### 实现
- 新增 `crm_wecom_media_asset` 及迁移/回滚，保存每条消息的多个媒体引用、文件元数据、完整性校验与 Worker 状态，大二进制不写 MySQL。
- 官方适配器扩展为身份明确的单聊多类型消息：文本、Markdown、链接、位置、名片、文件、会议和混合/聊天记录保留真实摘要；递归提取嵌套 sdkfileid。群聊与撤回仍显式记缺口。
- 新增 MySQL 租约 Worker，官方 `GetMediaData` 分片下载、游标推进检查、文件/分片限制、声明大小与 MD5 校验、SHA256 及私有对象存储；支持失败重试、过期恢复和旧租约隔离。
- 新增授权媒体下载接口与现有会话页资产状态/下载入口。每次复查当前同意、成员覆盖和客户范围，不暴露 sdkfileid、对象 Key 或永久公开地址。
- 监控改为按当前适配器读取 `MOCK_ARCHIVE/OFFICIAL_ARCHIVE`，不再用 Mock 游标误导真实状态；清理旧“官方 SDK 禁用”页面提示。
- 新增 V49 Worker 与生产配置，保持单实例 Redis；同步 OpenAPI、字段字典、归档、API 与部署文档。真实媒体文件 ID 与供应商下载仍需环境验收。

### 验证
- V49 相关 10 项单元测试与 3 项真实 MySQL 集成测试通过，覆盖分片、MD5/SHA256、大小限制、游标、失败/租约、迁移回滚、消息入库、Outbox、授权下载及越权拒绝；静态规范 0 问题。
- 前端全量 139 个文件、599 项测试通过；最终会话页 15 项定向测试与 typecheck/lint/build 通过。
- OpenAPI 2479 个本地引用无缺失、157 个 operationId 无重复；Compose 校验通过。安全扫描无 V49 新增命中，既有 SHA1 与客诉测试密钥提示继续单独记录。

## [V48] 企微官方会话归档 SDK 单聊文本链路 — 2026-11-13

### 实现
- 新增 `OfficialWecomArchiveAdapter`、JNA 官方 C SDK 桥接与按公钥版本读取 RSA 私钥能力。严格校验响应、递增 seq、消息 ID 和真实租户/企业绑定，原生会话与缓冲区均释放，不记录 Secret、私钥、代理密码或随机密钥。
- 按企微官方协议完成 RSA PKCS1 随机密钥解密和 SDK 正文解密；支持 PKCS1/PKCS8 私钥及版本化 `private-{version}.pem`。复用既有客户同意、归档成员、客户身份、AES-GCM 对象存储与 Outbox 链路。
- 当前规范化范围为身份明确的单聊文本；群聊、撤回、媒体/其他类型及身份歧义显式记录缺口，不保存正文，不伪造完整类型支持。
- 真实模式不再路由到 Disabled；`allowedTenantId + corpId + 运行时开关` 共同控制采集。Mock/真实游标拆分为 `MOCK_ARCHIVE/OFFICIAL_ARCHIVE`，切换不复用序号。
- 生产 Compose 增加只读官方 SDK/私钥卷、配置参数和 JNA 所需 `/tmp` 执行权限。新增 JNA 5.18.1，复用已有 BouncyCastle，无新增数据库表或领域事件。
- 同步归档设计、部署说明、OpenAPI 描述、游标字段字典与进度。真实企业 GetChatData、媒体分片、撤回及完整类型仍待后续/供应商验收。

### 验证
- V48 相关 7 项单元测试与 8 项真实 MySQL/Kafka 集成测试通过，覆盖双向身份映射、RSA、游标隔离、授权、加密存储、Outbox、重复回放及既有 Mock 并发回归；Checkstyle/SpotBugs/Spotless 为 0 问题。
- 在 Linux amd64 + Java 21 生产镜像中实际加载官方 C SDK，合成配置初始化与释放成功，未请求真实聊天数据。Compose 配置校验通过。
- OSV 依赖审计 Maven 189 项、PyPI 25 项，已知漏洞 0。安全扫描中本次 RSA 规则已处理；仍有既有 `WecomJsSdkService` 官方 SHA1 签名提示及客诉测试密钥提示，未宣称为全部安全扫描通过。

## [V47] 企微 H5 项目分享卡片 — 2026-11-13

### 实现
- 新增 `crm_wecom_h5_card` 与只追加使用日志表、V47 迁移/回滚。分享保存项目、标题、摘要与公开正文快照；数据库触发器禁止覆盖内容，只允许 ACTIVE → DISABLED。
- 新增卡片生成、本人历史、停用、复制/SDK 结果记录及免登录公开读取接口。高随机 shareKey 不含业务 ID，公开响应不返回租户、客户、员工、企微身份或内部编号。
- 员工侧工具新增 H5 卡片编辑器、即时预览、公开页检查、复制、当前单聊发送、本人历史和链接停用；修改已生成内容会清除旧快照，避免发送旧链接时误解为新内容。
- 按企微官方文档使用 `ww.sendChatMessage` 的 `news` 结构（link/title/desc/imgUrl），发送前重新检查 single_chat_tools；未知结果不冒充成功，停用后仍可补记已发生的真实结果。
- 新增独立公开落地页 `/h5/cards/:shareKey` 和内置封面，纯文本渲染、noindex、失效提示与公开内容边界完整。生产链接复用受信侧边栏 HTTPS Origin，无需 Redis 集群。
- 同步 OpenAPI、集成设计、侧边栏、API、部署文档、字段字典和进度。真实企微客户端卡片展示与版本兼容性仍需供应商环境验收。

### 验证
- 后端 V47 定向 `verify` 通过：339 项单元测试、2 项 H5 API + 1 项迁移回滚真实 MySQL 集成测试，Checkstyle/SpotBugs/Spotless 为 0 问题。
- 前端全量 139 个文件、598 项测试通过；最终 typecheck/lint/build 通过，覆盖 news 参数、生成重试、编辑失效、复制、发送、停用、公开读取与路由权限。
- OpenAPI 2474 个本地引用无缺失，156 个 operationId 无重复。本地后端已加载 V47 并通过健康检查；未在真实企微客户端执行发送。

## [V46-ADMIN] 快捷话术后台管理工作台 — 2026-11-13

### 实现
- 新增 `/settings/quick-replies` 页面及系统配置入口，按 `quickreply:manage` 控制；提供话术搜索、分类筛选、新建、编辑、停用、分类与作用域管理、变量插入、真实客户预览及使用统计。
- 话术作用域继承分类，机构选项来自真实机构接口；新增不传 version，编辑和停用携带 version 与必填理由，全部写操作由统一 HTTP 层携带 CSRF 和幂等键。
- 管理列表只展示启用配置；停用不删除历史 AI 候选与使用日志。真实预览不产生复制、发送或业务写入。
- 复用 V44/V45 管理、统计接口，不新增后端 API、迁移或事件；同步话术设计、API 文档与进度。

### 验证
- 前端全量 136 个文件、587 项测试通过；修正测试类型后定向 24 项、typecheck/lint/build 再次通过。
- V46 后端最终 `./mvnw -pl apps/crm-api verify` 全量通过：339 项单元测试、338 项真实 MySQL/Kafka 集成测试，Checkstyle/SpotBugs/Spotless 为 0 问题。
- 使用本地 admin 会话实际打开管理页，真实读取 3 个话术与 12 个分类；修正按钮默认样式后复查布局正常。当前代码后端重启后统计接口不再报旧版本 404，V46 迁移成功且三个内置模板显示 AI 候选。未执行任何新增、修改或停用操作。

## [V46] 员工侧 AI 推荐回复与人工采纳闭环 — 2026-11-13

### 实现
- 新增 `crm_ai_reply_suggestion` 及 V46 迁移/回滚，保存场景、脱敏事实快照、模型、提示词版本、置信度、人工决策与乐观锁；AI 原文和员工最终文本使用 AES-GCM 加密，原文不可覆盖。
- AI Worker 新增 `/api/v1/replies/generate`，支持现有 openai_compatible 供应商与明确标识的 Mock；严格校验场景、事实引用、必填安全字段和数值，不执行聊天或措辞说明中的越权指令。
- CRM 新增候选生成、本人历史、详情及采纳/驳回 API；生成同时校验 AI 权限、话术作用域和客户数据范围，员工补充说明不是可信业务事实。生成与决策均有独立幂等键，状态仅允许 `PROPOSED → ACCEPTED/REJECTED`。
- 侧边栏新增“AI 助手”页签，支持场景选择、措辞要求、生成与同键重试、编辑、采纳、驳回和本人历史；采纳前不开放复制或发送，发送中禁止切换候选。
- 使用日志新增可空 `ai_suggestion_id`，只有本人已采纳且客户、模板、场景、原始与最终文本一致的候选可关联；复制、采纳和 SDK 发送结果保持为不同事件。
- 同步 OpenAPI、AI 设计、API 文档、开发进度与数据库字段字典。未使用真实模型或真实企微凭据，不声称供应商在线验收通过。

### 验证
- AI Worker 全量 59 项测试通过，覆盖真实供应商响应结构、事实白名单、缺失事实、提示词注入、内部认证和禁用配置。
- 后端 AI 回复/快捷话术/迁移组合通过：338 项单元测试、8 项真实 MySQL 集成测试，Checkstyle/SpotBugs/Spotless 为 0 问题。
- 前端 135 个文件、579 项测试通过，typecheck/lint/build 通过；OpenAPI 2430 个本地引用无缺失，151 个 operationId 无重复。

## [V45] 快捷话术收藏、最近使用与使用统计 — 2026-11-13

### 实现
- 新增个人收藏表及 V45 迁移/回滚；模板新增 `featured` 公司推荐标记并贯通管理 API、Mapper 与审计。
- 列表按个人收藏 > 最近使用 > AI 候选 > 公司推荐 > 分类排序，返回当前员工的收藏、最近使用时间和使用次数；收藏/取消收藏均支持幂等且不改使用日志。
- 新增使用统计接口，直接聚合只追加日志，支持日期和员工范围；普通员工仅本人，管理员可查看团队聚合。`successRate` 仅以 SDK 结果为分母，复制与客户已读均不计入。
- 侧边栏模板支持独立收藏按钮及推荐、AI、最近使用和使用次数展示；OpenAPI、字段字典和设计文档已同步。

### 验证
- `QuickReplyEngagementApiIT` 与 V45 回滚集成测试通过，覆盖收藏幂等、最近使用、推荐排序、成功率、员工数据范围和日志保留。
- 最终组合回归通过：后端 338 项单元测试与快捷话术/收藏/迁移 5 项集成测试；前端 134 个文件、573 项测试及 typecheck/lint/build 全部通过。OpenAPI 2396 个本地引用无缺失，静态检查为 0 问题。

## [V44-FRONTEND] 侧边栏快捷话术预览、编辑与当前会话发送 — 2026-11-13

### 实现
- 侧边栏新增“工具”页，分类、模板和变量全部来自 V44 后端，不在前端硬编码话术或业务事实。
- 选择模板后按当前客户真实数据预览，缺失必填变量时禁用复制/发送；员工可按模板权限编辑，再复制或进入当前客户单聊发送。
- 使用官方 `ww.sendChatMessage`，发送前重新检查 `single_chat_tools`；成功、失败、超时/异常分别记录 SDK_SUCCESS/SDK_FAILED/SDK_UNKNOWN。未知结果明确提示先核实，不引导盲目重发。
- 复制与发送均写脱敏使用日志，稳定幂等键支持失败重试；页面不宣称客户已读。真实企微客户端仍需供应商验收。

### 验证
- 新增话术面板、API 与 SDK 发送定向测试通过，覆盖编辑复制、当前单聊发送、缺失变量禁用及资料页禁发。
- 最终前端 134 个测试文件、572 项测试全部通过，typecheck/lint/build 通过。

## [V44] 快捷话术、动态变量与使用日志后端 — 2026-11-13

### 实现
- 新增话术分类、模板、变量白名单和使用日志四表及 V44 迁移/回滚；分类与模板支持新增、修改、停用、作用域、版本、幂等、脱敏理由和审计。
- 注册 15 个真实 CRM 变量 Resolver，复用客户、管家、权益、订单、预约、机构和正式核销 Application Service；不支持任意 SQL/脚本，继承原业务权限。无可靠医生事实时明确为空。
- 新增列表、分类、变量、预览和使用记录 API。预览返回 `missingVariables/canSend`；复制与 SDK 回调日志校验客户范围和企微映射，内容脱敏且只追加，SDK_SUCCESS 不冒充客户已读。
- 同步 OpenAPI、数据库字典和企微设计文档；侧边栏话术选择、编辑与当前会话发送前端在下一阶段接入。

### 验证
- `QuickReplyApiIT` 3 项与 `QuickReplyMigrationRollbackIT` 1 项通过，覆盖真实动态次数、缺失预约、分类/模板幂等与管理审计、权限、日志脱敏、重放和只追加约束。
- OpenAPI 2376 个本地引用无缺失，operationId 无重复。完整后端回归另行执行，结果以实际日志为准。

## [V43-FRONTEND] 企微窄屏侧边栏与聊天客户识别 — 2026-11-13

### 实现
- 新增独立 `/wecom/sidebar` 页面，不使用后台外壳；提供客户档案、订单/可用权益/剩余次数摘要、套餐明细和统一时间线，适配 320–520px。
- 使用官方新版 `wecom-jssdk-2.4.0.js` 与 `ww.register`，从服务端获取企业/应用双签名；只在 `single_chat_tools` 或 `contact_profile` 获取当前外部联系人，群聊、普通入口、浏览器与 SDK 异常分别处理。
- 未登录时进入现有 OAuth；权限不足不调用上下文。客户、套餐和时间线复用真实 API，辅助数据异常不阻断基础档案；普通浏览器不加载 SDK、不伪造当前会话。
- 生产 Compose 增加侧边栏开关、AgentID、固定 OAuth 回调、侧边栏地址与受信 Origin，默认关闭；真实企微供应商验收仍需有效环境。

### 验证
- 新增 SDK、API、页面 9 项定向测试通过；最终前端 132 个测试文件、565 项测试全部通过，typecheck/lint/build 通过。
- 真实浏览器确认独立普通入口及降级文案正确；未在真实企微客户端执行，不声称聊天工具栏供应商验收通过。

## [V43] 企微侧边栏 OAuth、JS-SDK 与当前客户上下文 — 2026-11-13

### 实现
- 新增企微官方 OAuth `getuserinfo`、企业 JS 票据和 `agent_config` 应用票据客户端；JS 签名按官方固定顺序 SHA-1，Secret、token 和 ticket 不下发。
- OAuth 使用单实例 Redis 一次性 state，成员必须唯一映射 ACTIVE CRM 员工；回调复用现有 JWT + 服务端会话与 Cookie，不接受前端员工 ID。JS 票据按 CorpID/类型缓存并单实例同步获取，签名 URL 限制 HTTPS/本地与受信 Origin。
- 新增 `wecom:sidebar:view` 权限与 V43 迁移/回滚；上下文返回 READY/CUSTOMER_UNLINKED/WAITING_CONTEXT、员工、CRM 客户、订单数、可用权益数和剩余服务次数，执行租户、数据范围及订单/权益权限检查。
- 更新 OpenAPI 与企微开发进度。真实企业凭据、可信域名、OAuth/JS-SDK 供应商验收和侧边栏前端仍需后续完成，本阶段不声称真实企微联调。

### 验证
- 官方客户端端点与签名样例 2 项单元测试通过；`WecomSidebarApiIT` 4 项真实 MySQL/Redis 集成测试通过，覆盖上下文、越权、CSRF、受信 URL、双票据缓存、OAuth 会话、state 重放、未映射与多租户歧义。
- 组合回归 `./mvnw -pl apps/crm-api -Dit.test=WecomSidebarApiIT,WecomAcceptanceIT,WecomContactSyncApiIT verify` 通过：338 项单元测试、14 项企微相关集成测试，Checkstyle/SpotBugs/Spotless 均为 0 问题；OpenAPI 2280 个本地引用无缺失，operationId 无重复。

## [V42] 客户按实际消费金额自动分级 — 2026-11-13

### 实现
- 新增 `V42__customer_tier_actual_consumption.sql` 与回滚脚本：规则等级增加可空 `min_consumption_fen`，快照增加可空 `actual_consumption_fen`；不回填、不覆盖旧评分或等级历史。
- 分级只使用滚动近 12 个月的实际消费金额：有效订单实付减成功退款，负数按 0；恰好达到门槛即定级。失败/申请中退款不扣减，沟通频次、下单频次、指标权重与得分不再参与。
- 新规则 `metrics=[]`，管理员配置 S/A/B/C/D 的展示名称、顺序及最低消费金额，D 级门槛为 0；保留自动升级、人工降级复核、人工调整/锁定、数据权限、幂等、版本和审计。旧评分版本可查看，缺消费门槛时不建议新等级或自动变更。
- 设置页移除计算指标、权重、指标门槛和得分；金额以元编辑并通过字符串精确转整数分。试算及客户详情展示实际消费、订单实付、成功退款与建议等级，保留自动规则代码和五级中文默认名称。
- 同步 OpenAPI、PRD、状态机、字段字典、功能清单、权限矩阵和 Q12 决策；没有新增 API 路径或事件。

### 验证
- 金额边界、8000 元示例、退款归 0、闰日窗口、旧规则不再自动生效、真实 MySQL 快照及 Kafka 重算均有回归。最终 `./scripts/verify.sh` 全部通过：后端 **336 项单元测试 + 325 项真实 MySQL/Kafka 集成测试**，前端 **129 个文件、556 项测试**及 typecheck/lint/build，AI Worker **49 项测试**；Checkstyle、SpotBugs、Spotless 均为 0 问题。
- 本地 V42 迁移及独立后端启动成功。管理员实际保存并启用合成金额规则，数据库五级门槛正确且 `metrics=[]`；单客户试算返回实际消费 8000 元、建议 B 级，试算不落快照、不改等级。
- 合成客户（ID 1011）订单实付 10000 元、成功退款 2000 元，正式重算后从 D 自动升至 B；消费快照 `actual_consumption_fen=800000`、`score=NULL`，追加 D→B 的 `AUTO_UPGRADE` 历史及快照、自动升级两条审计。旧评分规则可读取但不会自动改等级。
- OpenAPI 实际解析、本地引用及 operationId 唯一性校验通过，`git diff --check` 通过。以上仅使用本地合成数据，不是生产金额政策、真实支付或真实企微验收；未执行生产部署。

## [V41] 多条人工共情话术库与关键词行对齐 — 2026-11-13

### 实现
- 新增 `crm_complaint_empathy_script` 及 V41 迁移/回滚：场景标题与内容独立保存，同租户标题唯一，ACTIVE 只能单向停用且禁止物理删除；原策略单话术自动迁移为“默认共情话术”，旧 `empathyScript` 响应字段保留兼容。
- 新增话术列表、创建、修改、停用接口，执行 `complaint:config`、租户隔离、CSRF、幂等、version、脱敏理由和审计；正式客诉详情新增 `empathyScripts` 启用列表，停用不影响历史客诉。
- 设置页改为可新增、修改、停用多条场景话术；处理页可下拉选择并人工复制所选话术，不自动发送、不改写订单、退款或权益。关键词与理由统一标签和输入框高度，操作按钮与输入框对齐，窄屏保持单列。
- 更新 OpenAPI、PRD、功能清单与数据库字段字典，无新增领域事件。

### 验证
- 新增后端集成测试覆盖多条、重复标题、版本冲突、幂等、审计、跨租户与禁止删除；`ComplaintApiIT` 新用例及 `ComplaintMigrationRollbackIT` 通过。前端话术库、处理页选择复制和原配置定向测试通过。
- 本地 V41 迁移成功，真实管理员页面新增第二条“进度跟进”，数据库保留两条独立记录及创建审计；正式客诉详情实际显示“共 2 条”，选择第二条后完整内容正确并出现复制成功状态。
- OpenAPI 本地引用与重复 operationId 校验通过。最终 `./scripts/verify.sh` 全部通过：后端 334 项单元测试与 324 项真实 MySQL/Kafka 集成测试，前端 129 个文件、560 项测试及 typecheck/lint/build，AI Worker 49 项测试。

## [UI-CUSTOMER-TIER-DEFAULTS] 分级规则代码与等级名称默认填写 — 2026-11-13

### 实现
- 新建分级规则时自动生成符合现有校验的唯一规则代码，仍可人工修改；保存后编码不可变，API 结构不变。
- S/A/B/C/D 默认显示名称为核心客户、重要客户、成长客户、普通客户、培育客户，全部可编辑；区域说明明确名称只用于展示，不影响分级计算，排序与最低得分才影响规则执行。
- 增加自动代码、默认名称和用途文案回归，保留已有金额转换、按钮可见性和规则操作测试。

### 验证
- 本次相关分级与按钮测试 25/25 通过，typecheck 和变更文件 lint 通过；真实管理员页面确认自动代码及五级中文默认值。
- 最新完整前端回归另有 2 条客诉话术测试失败（`complaint-empathy-script-settings.test.ts` 缺少“首次响应”、`complaints-view.test.ts` 缺少话术选择器），均不在本次修改范围，未为通过门禁修改无关模块；本次测试失败阶段之后未执行生产构建。

## [UI-BUTTON-VISIBILITY] 全局按钮文字可见性修复 — 2026-11-13

### 实现
- 修复 `style.css` 与 `prototype.css` 对 Element Plus 主按钮的两层全局覆盖：普通主按钮保留品牌实色，`plain/is-text/is-link/is-dashed` 不再被强制套用主色背景。
- 文字按钮明确使用透明背景，避免默认状态与悬停状态不一致；不改变按钮业务动作、权限或表单行为。
- 新增 `button-style-visibility.test.ts`，静态锁定两个全局入口对按钮变体的排除规则。

### 验证
- 回归测试先复现旧规则失败，修复后通过；实际浏览器确认分级页“添加第一项指标”和机构一页纸“查看一页纸”均恢复文字显示。
- 最终前端 typecheck、lint、生产构建及 128 个测试文件、556 项测试全部通过；未修改后端、API 或数据库，未重跑后端/Worker 测试。

## [UI-COMPLAINT-SETTINGS] 客诉关键词页视觉与编码修复 — 2026-11-13

### 实现
- 重做检测策略、关键词和企微通知接收人布局，补齐表单标签、状态与版本展示、操作理由、空状态和响应式样式；保留原权限、版本、幂等、审计与人工核查规则，无 API、状态机或事件契约变更。
- 通过正常策略保存接口修复本地默认共情话术的乱码并追加审计；本地、生产默认 JDBC 连接及 M01 恢复脚本显式使用 Unicode/UTF-8，避免中文默认数据再次错误编码。
- 明确 `staff-2`、`staff-3` 等 MOCK 企微成员 ID 是开发模拟数据，不是员工姓名；保留映射业务原值，未伪装成真实员工资料。

### 验证
- 前端 127 个测试文件、553 项测试全部通过，`typecheck`、`lint`、生产构建通过。
- `./mvnw -pl apps/crm-api -Dit.test=ComplaintApiIT verify` 通过：334 项单元测试及 3 项客诉真实 MySQL/Kafka 集成测试成功，Checkstyle、SpotBugs、Spotless 均为 0 问题。
- 真实管理员桌面浏览器完成话术保存，重启后端后仍显示正确中文；MySQL 十六进制内容确认 UTF-8 正常，新增 `COMPLAINT_POLICY_UPDATE` 审计。生产 Compose 静态校验、恢复脚本 `bash -n` 及本地健康检查通过，未执行生产部署或真实手机验收。

## [UI-CUSTOMER-TIER-SETTINGS] 分级规则页业务化整改 — 2026-11-13

### 实现
- 重做 `CustomerTierSettingsView`：紧凑版本导航、明确的基本信息/等级/指标/试算分区、可操作的空状态及手机单列布局，保留既有 API、权限、版本和幂等行为。
- 页面状态统一为草稿/使用中/已停用，按钮改为启用/停用规则；移除 GTE/LTE、asOf/ISO-8601 等技术展示，指标名称、北京时间及日期时间控件全部业务化。
- 试算金额以元和两位小数展示；金额门槛编辑使用元，读取分转元、提交通过字符串精确转整数分，次数仍校验非负整数，不改变后端金额事实。
- 企微映射补充 MOCK 成员 ID 属于开发模拟数据、不是员工姓名的明确说明。

### 验证
- 定向分级页面测试 21/21 通过；最终前端 typecheck、lint、生产构建及 127 个测试文件、553 项测试全部通过。
- 真实管理员浏览器完成桌面与 iPhone 14 Pro 仿真复核；无技术英文直出，空状态、表单标签和操作入口可用。仿真不代表实机/多浏览器矩阵。
- 本次仅前端展示与文案调整，未重跑后端或 Worker 测试，无 API、数据库、状态机或事件契约变更。

## [CUSTOMER-OPERATIONS] 客户运营自动化与客诉闭环 — 2026-11-12

### 实现
- **V28 通知与企微应用消息**：新增站内通知、未读数、已读、投递查询/重试、Outbox/Kafka/Inbox/DLQ、独立 Official/Mock/Disabled 适配和只追加投递尝试；真实 Secret 仅环境注入，UNKNOWN 不盲重发。
- **V30 跟进与派单**：默认 14 天及意向覆盖、跟进创建幂等、下单满三个自然月、权益到期筛选、月度预览/批次执行/失败重试；复用任务状态机，不重开终态任务，不直接改客户归属。
- **V31 客诉闭环**：系统管理关键词/策略/共情话术，合法企微与人工跟进检测、主管三级解析、站内+企微提醒；命中先待核查，人工确认、认领、处理、关闭或驳回，历史只追加。
- **V32 自动分级**：滚动近 12 个月独立支付流水、订单实付和成功退款指标，结构化 S/A/B/C/D 版本规则、试算、自动升级、人工降级复核及人工锁定；无生产默认阈值，通用客户写接口不再直接覆盖等级。
- **V33 分组与时间线**：城市/等级/关注/已购/净核销动态分组，成员实时与数据范围求交；统一时间线以游标聚合跟进、任务、归属、交易、履约、退款、等级和客诉，不返回聊天全文。
- **V37 批量标签**：筛选/分组/操作批次固定快照，ADD/REMOVE 逐项执行、权限重验、失败重试和独立反向任务，不循环调用全量替换，历史未知标签保留。
- **V40 通知映射**：管理员可新增/修改 CRM 员工与企微成员映射，message_enabled 与归档 enabled 独立；唯一约束、幂等、版本、理由和审计完整，缺失/多义/停用映射阻断外部发送。
- 新增全部 Controller/Service/Repository/DTO/Mapper、Vue 页面、角色菜单和通知中心；同步 OpenAPI（252 paths/302 methods）、事件契约、字段字典、PRD、状态机、功能清单、验收矩阵和 TASKS。

### 实际验证
- 最终 `./scripts/verify.sh` **全部通过**：后端 **334 项单测 + 323 项真实 MySQL/Kafka 集成测试**，前端 **127 个文件、533 项测试**及 typecheck/lint/build，AI Worker **49 项测试**。Checkstyle、SpotBugs、Spotless 均为 0 问题。
- 真实页面保存默认 14 天策略并生成对应日期；合成跟进命中“投诉”，主管收到站内通知，人工确认后转正式客诉。新增主管映射后，第二条合成投诉的投递为 `IN_APP=SUCCEEDED`、`WECOM_APP=MOCKED`。
- 主管动态分组预览真实返回当前团队 6 位客户；OpenAPI 本地引用和 operationId 校验通过。验收仅使用合成数据和 Mock 企微，未调用真实供应商。

### 边界
- 真实企微 Official 授权、真实成员收讫、生产部署和真实数据迁移未验证；MOCKED 不代表真实送达或已读。生产启用仍需供应商授权、凭据、容量与安全验收。
- 本地后端使用 `target/runtime/` 下独立固定运行副本，避免后续构建覆盖运行中的主 JAR；最终健康检查、登录页及验证码接口均为 200，前后端保持可用。

## [DEV-API] 本地后端不可变运行副本 — 2026-11-12

- 新增 `scripts/dev-api.sh`：先构建并复制带时间戳的独立 JAR，再停止旧进程、启动新副本并等待健康检查，避免 Maven 覆盖运行中文件导致大量 `ClassNotFoundException`。PID 和日志位于 `apps/crm-api/target/runtime/`。
- 脚本只停止自己启动且命令行校验通过的进程；非管理进程占用 8080 时拒绝启动，不强制杀进程。支持 `status`、`stop` 和透传 Spring 参数，`API_SKIP_BUILD=1` 可复用最新构建产物。
- 已实际验证 `bash -n`、非管理进程占用拒绝、Maven 构建、独立副本启动及 `http://localhost:8080/actuator/health` 返回 `UP`；README 已改用统一启动方式。

## [V34-V39] 套餐分组、月结结算日与授权聊天托管 — 2026-11-12

### 实现与契约整合
- **V34 套餐分组与独立售价**：套餐版本新增结构化 `groups[]` 和 `projects[].referencePriceFen`，快照返回组及参考价；订单 `items[].selections[]` 保存实际组与项目，另存次数、套餐独立售价、单品参考合计和参考优惠。支付只发实际所选项目，旧无组 CHOOSE_N 仍不能发权益。多订单行缺权重统一保留 PENDING_Q02，不使用参考价兜底；V34 回滚在非空选择快照时拒绝删除历史。无新增端点或事件。
- **V35/V38 月结结算日**：新增 `GET/PUT /api/v1/institution-settlement-default`，支持 1–28/LAST_DAY、机构 `settlementDay` 继承/覆盖及月结批次 `settlementDay/settlementDaySource/settlementDate` 快照。机构覆盖走 MARKET→FINANCE 双审；首次正式配置 version=1，拒绝未配置 version=0 的 stale 覆盖。员工结算与对账必须有机构 ALL 范围；已存在批次只在业务请求完全相同时登记当前幂等键。Q04/Q05 金额和收款口径不变。
- **V36/V39 合法消息自动托管**：新增策略 GET/PUT、紧急停用、状态、runs 及客户控制接口、五张业务表和发送启动栅栏。只对合法企微客户消息在全部授权、同意、范围、内容、频控和时段门禁通过时自动生成并发送；预约确认等未经业务路径确认的声明在生成和发送双门禁阻断。官方发送同时服从静态与租户运行时开关。
- V39 在短事务内授权、事务外调用 HTTP，停用先封锁新授权并等待在途结束，超时只报处理中；客户暂停/接管追加 COMPLIANCE/SKIPPED attempt。长事件 ID 遵循 192 字符契约，MOCKED 不代表客户收到，UNKNOWN 不自动重发。V34/V36/V39 回滚分别保护订单选择、托管历史和在途发送，非空时在任何 DROP 前拒绝。
- 同步 OpenAPI、PRD、状态机、系统功能清单、字段字典、角色权限、AI 设计及待确认决策；保留有赞本轮暂缓范围，未改业务实现。

### 验证与当前边界
- 套餐 10 个前端测试文件、32 项定向测试通过，覆盖组规则、参考价/优惠计算、目录快照、下单、订单详情及既有订单回归；机构与 AI 6 个前端测试文件、17 项定向测试通过。
- 最终 `./scripts/verify.sh` 全部通过：后端 334 项单测、322 项真实 MySQL/Kafka 集成测试，前端 126 个文件 530 项测试及 typecheck/lint/build，AI Worker 49 项测试；Checkstyle、SpotBugs、Spotless 均为 0 问题。日志：`tmp/verify-package-settlement-ai-hardening-final.log`。
- OpenAPI 最终为 250 个 paths、299 个方法，2196 个本地引用无缺失，无重复 operationId；`git diff --check` 通过。
- 真实管理员浏览器确认套餐可添加 A/B 两组并配置选择数、机构显示系统默认/自定义结算日、AI 托管默认关闭且外部发送禁用。真实企微官方发送、真实模型供应商、生产启用和客户收讫均未验证，未开启真实自动发送。

## [UI-CUSTOMER-TIER] 客户等级定义与五级选项 — 2026-11-11

### 实现
- 明确 `tier_code` 是客户等级 S/A/B/C/D，不是客户熟悉度；列表、详情、编辑和导出字段统一使用“客户等级”，补齐 D 级筛选与字典选项。机构商户等级不变。
- 开发字典显示为 S级/A级/B级/C级/D级，保留历史等级回显与后端字典校验。消费金额示例仅用于解释等级，不擅自设置自动分级阈值；Q12 继续记录正式阈值待确认。
- 为恢复本地服务，V31 客诉迁移的角色写入改为 `INSERT IGNORE`，权限写入补充连接与 `ON DUPLICATE KEY UPDATE` 的语法边界；未删除或重建业务表。

### 验证
- 客户列表、字典表单、客户详情和导出相关的 4 个前端测试文件共 17 项通过；`typecheck`、lint 和本次文件 `git diff --check` 通过。
- 真实管理员浏览器确认列表与编辑页均为“客户等级”，五级选项完整；D 级筛选实际返回 0 条空结果，随后恢复全部 9 条。未创建或修改客户。
- 实际 MySQL 字典为“客户等级”及 S/A/B/C/D 五项，后端健康检查为 `UP`。
- 全量前端未通过：107 个文件、453 项已执行测试通过，但自动分级和跟进自动化的 4 个测试文件引用了尚不存在的模块；生产构建同样被这些缺失模块阻断。最新并行客诉源码另有 4 处类型错误，因此不声称最新全量后端编译或全栈验证通过。

## [NOTIFICATION-BASELINE] 通知投递与独立企微应用消息 — 2026-11-11

### 实现
- 新增 `com.whale.crm.notification` 后端模块及 V28 通知、渠道投递、最终尝试表和回滚。其他领域可通过 `NotificationRequestService` 在同一事务写通知、投递和 Outbox；通知逻辑字段及最终尝试由触发器防止覆盖或删除。
- 新增本人通知列表、未读数、已读和管理端投递查询、重试接口。本人查询严格绑定认证用户；写接口使用 CSRF、非空白幂等键、乐观锁及脱敏审计，已读支持空请求体和可选版本，通知响应批量附带渠道摘要。
- 接入 `notification.requested.v1`、专用 Topic、Inbox 去重及 DLQ；worker 使用版本与租约原子抢占、有界退避和只追加尝试。网络超时及过期租约记为 `UNKNOWN` 且不自动重发，人工核实原因后才能重试。
- 新增独立 Official/Mock/Disabled 企微应用消息 Router、Token 缓存刷新、限流映射和成员反查，不读取归档开关。默认 Disabled，Mock 明确记为 `MOCKED`，Secret 仅由 `WECOM_MESSAGE_SECRET` 注入；未修改 OpenAPI 或前端，契约说明见 `docs/02_架构与技术/通知与企微应用消息.md`。

### 已实际验证
- 30 项定向单元测试通过，覆盖请求去重、最小事件 payload、消费契约、worker 状态机、Outbox 路由、Token 刷新、限流、UNKNOWN 及 Official/Mock/Disabled 路由。
- `NotificationApiIT`、`NotificationDeliveryLeaseIT`、`NotificationBrokerReliabilityIT` 均使用真实 MySQL 8.4；最后一项同时使用真实 Kafka，验证端到端 API、CSRF/越权/幂等/审计、不可变触发器、12 路并发租约、Inbox 重放去重及 DLQ。集成测试中的 Official 客户端均被替换并验证零交互，HTTP 适配器单测只访问本机临时服务。
- `RolePermissionApiIT` 6 项及 `ProductionReferenceDataIT` 1 项通过，开发/生产权限种子有效。`./mvnw -pl apps/crm-api -DskipTests verify` 通过，Checkstyle、SpotBugs、Spotless 均为零问题；未声称执行全量测试套件。

## [RBAC-OPERATIONS] 新增运营与运营主管角色 — 2026-11-11

### 实现
- 沿用现有 `MANAGER` / `MANAGER_LEAD`，新增 `OPERATION` 运营与 `OPERATION_LEAD` 运营主管，不重复创建管家角色。V29 按租户补齐角色、权限模板和活动审核权限，生产不创建默认人员账号。
- 运营负责产品、活动、素材、渠道及有赞异常；运营主管增加素材审核、活动审核发布和全公司客户/机构数据范围。两者均无默认导出、支付确认、用户管理或集成配置权限，客户手机号继续按现有接口脱敏。
- 新增开发账号 `operation1` / `operationlead1` 用于验收；`campaign:review` 控制活动转为进行中，管理员及既有商务权限保持兼容。角色中文名称、颜色、范围提示及权限文档已同步。
- 工作区通知模块已占用 V28，本功能顺延为 V29，未修改通知模块业务实现。

### 验证
- 前端 107 个测试文件 451 项测试、typecheck、lint 和生产构建通过，覆盖角色标签、范围提示及活动审核入口。
- 实际 MySQL 服务已确认共 10 个角色：运营 15 项权限、运营主管 17 项权限；普通运营创建草稿后被拒绝发布及访问用户管理，运营主管发布成功，验收活动已归档。后端迁移与主代码编译通过。
- 干净构建定向复验通过：`DataScopeServiceTest` 1 项、`RolePermissionApiIT` 6 项、`CampaignApiIT` 5 项；角色断言已适配并行通知模块的本人通知权限（开发环境运营 16 项、运营主管 18 项），Checkstyle 与 Spotless 通过，本轮 SpotBugs 跳过。
- 完整 `./scripts/verify.sh` 仍未通过：并行通知模块尚余 5 项已知 SpotBugs 问题（通知响应可变集合暴露 2 项、投递 Worker 构造异常风险 2 项、企微令牌刷新异常捕获 1 项），本次未修改其业务实现。

## [CHANNEL-PROFILE] 合作渠道完整资料与格式校验 — 2026-11-10

### 实现
- V26 为 `crm_partner_channel` 增量新增博主分佣、真实姓名、联系方式、身份证、结算方式、合作起止日期及收款账户资料；渠道编号改由后端自动生成 CH+ULID，新增表单不再要求手动输入，编辑仍不可修改。保留状态机与订单归因历史，新增唯一键/索引说明和批准后的回滚脚本。
- V27 新增博主/达人、分销、老带新三种合作类型及列表筛选；列表分别使用青绿、紫色、橙色标签。博主/达人仅允许电汇月结，分销与老带新均支持电汇月结或每月两次。前后端同步校验，不改变既有订单归因快照。
- 列表按业务字段展示，新增分组编辑表单；对接商务通过同租户员工关联显示姓名，表单使用商务姓名下拉而非手动员工 ID。手机号显示前 3 位/后 4 位，身份证显示前 6 位/后 4 位，收款账号仅保留末 4 位，列表不再展示完整号码。分佣限制 0–100%，结束日期不得早于开始日期，结算方式读取 `settlementTypes` 字典。暂停合作完整保留新增资料。
- 前后端均校验 11 位中国大陆手机号及 18 位身份证出生日期/校验码，拒绝 12 位手机号、8 位身份证和错误校验码。联系方式、身份证、收款账户名称与账号使用 AES-GCM 密文存储，渠道审计不记录敏感明文。
- OpenAPI 与数据库/页面字段字典已同步，无新增事件或真实供应商接入。

### 验证
- 后端渠道服务 8 项单测及 CampaignApiIT 真实 MySQL 集成测试通过，覆盖合法保存、加密、审计脱敏、权限、非法格式、合作类型筛选及类型/结算匹配；Checkstyle、SpotBugs、Spotless 定向验证通过。
- 前端渠道 4 个测试文件 8 项定向测试通过，覆盖 12 位手机号、8 位身份证、错误校验码、合法保存、列表字段及分销每月两次选项。OpenAPI 191 个 paths、1569 个本地引用无缺失。
- 最终 `./scripts/verify.sh` 全部通过：后端 259 项单测、272 项真实 MySQL 集成测试，前端 103 个文件 428 项测试/typecheck/lint/build，Worker 49 项测试；Checkstyle、SpotBugs 和 Spotless 无问题。
- 真实管理员浏览器验收渠道列表全部新增字段及分组表单；非法手机号与 8 位身份证均显示中文校验并拦截保存。合作类型版本复核 5 条合成数据均为博主/达人且显示商务姓名，新增表单三种类型和默认电汇月结正常，未提交测试表单。

## [P1-3-UI] 企微 AI 操作按钮对齐修复 — 2026-11-09

### 实现
- `WecomArchiveView` 清除 Element Plus 相邻按钮默认左边距，修复“重新生成 AI 分析”与确认、驳回、复制按钮左右边界不一致；保留原有全宽布局、权限和 AI 人工确认状态机，无 API、数据库或事件契约变更。
- 新增按钮样式回归测试，先复现缺少左边距重置导致的失败，再完成最小样式修复。

### 验证
- `wecom-view.test.ts` 14 项通过；完整前端 typecheck、lint、生产构建及 101 个测试文件 422 项测试通过。
- 真实后端与 Mock Worker 页面验收覆盖桌面右栏和 iPhone 14 Pro 仿真单列布局，四个按钮左右边界均一致；仿真不代表实机或多浏览器矩阵。后端与 Worker 测试未因本次纯前端样式修复重跑。

## [UI-RISK-COPY] 风控台账业务化中文展示 — 2026-11-09

### 实现
- 风险列表与详情统一映射有赞同步、客户待匹配、商品匹配失败、支付不一致及对账异常等中文业务名称，未知编码不再直接暴露给客户。
- JSON 脱敏证据只提取实际问题原因，无法识别时显示核对提示；列表列名改为“问题说明”，详情使用“已隐藏敏感信息”说明，不改变服务端证据、权限与状态机。

### 验证
- 前端 typecheck、lint、生产构建通过，101 个测试文件 423 项测试全部通过；新增列表及详情 JSON/英文编码回归测试。
- 真实管理员浏览器核对截图对应风险记录，已显示“有赞同步 / 客户待匹配 / 客户尚未人工确认映射”，不再显示 JSON 或相关英文编码。

## [SECURITY-FIX] 登录保护、依赖升级与启动顺序修复 — 2026-11-08

### 实现
- 新增一次性算术图片验证码：随机题目、PNG base64、随机 ID，5分钟有效，验证即失效，响应不含答案且禁止缓存；登录失败自动刷新。Redis 仅保存摘要和短期计数，不作为账本。
- 新增 `GET /api/v1/auth/captcha`，登录必须同时提交 `captchaId/captchaAnswer`。同一账号及 IP 连续失败 5 次、同一 IP 失败 30 次后限流 15 分钟，验证码领取限制为每 IP 每分钟 30 次；`RATE_LIMITED` 返回 429 和 `Retry-After: 900`，存储不可用时明确返回 `AUTH_PROTECTION_UNAVAILABLE`，不跳过保护。
- 登录成功只清零当前账号的失败计数，不再允许其他账号通过成功登录重置整个 IP 的失败预算；测试夹具按精确摘要键清理测试计数，不改变生产清零语义。
- 管理员重置用户密码后立即吊销该用户全部登录会话；停用账号仍按原逻辑吊销。旧令牌不可继续使用，新密码需要重新登录。
- 待核销统计和待办跳转统一使用真实 `SERVICE_DONE` 状态，替换错误的 `SERVICE_COMPLETED`。
- 升级到 Spring Boot 4.0.9 / Spring Framework 7.0.10，修复原 Spring Web MVC 两项 Critical 漏洞；同步升级 MyBatis 4.0.1、Springdoc 3.0.1、Testcontainers 2.x，并补齐 webmvc-test、Kafka、Flyway starter。保留 Jackson 2 业务对象及时间格式兼容配置。
- HTTP 层统一复用 Jackson 2 ObjectMapper，避免 Boot 默认 Jackson 3 将现有 JsonNode 输出成内部属性或无法反序列化；机构资料变更、审计快照、客户合并及知识发布沿用原接口结构。
- 登录限流和审计使用容器解析后的连接地址，不再直接信任可伪造的 X-Forwarded-For；生产环境继续由 native 转发头策略处理受信代理。旧文件安全校验失败增加服务端诊断日志，不向客户端暴露内部异常。
- ClamAV 关闭并发病毒库重载以降低更新时内存峰值，仍执行病毒库完整性校验；重载期间短暂阻塞新扫描。知识发布测试改为受控扫描结果，不依赖本机守护进程偶然可用，扫描协议与恶意文件阻断测试不删除。
- 本地开发与 Testcontainers Kafka 堆内存限制为 512 MB，避免多套本地容器并行时反复 OOM；真实 Broker、消息去重、重试及 DLQ 验证范围不变。
- 首次有赞消息恢复改为 ApplicationRunner 调度，Flyway 在调度任务前完成迁移，避免启动恢复任务查询空库。周期重试、Outbox + Kafka 和原业务状态机不变。
- OpenAPI 增加验证码模型与登录失败响应，错误码字典补充登录保护不可用；补录已有字典类型条目接口并校正 dictCode 参数名。契约共 190 个路径、227 个方法。无新增业务表或领域事件。

### 已实际验证
- 最终 `./scripts/verify.sh` 全部通过：后端 258 项单元测试、270 项真实 MySQL/Kafka 集成测试，前端 101 个文件、421 项测试及 typecheck/lint/build，AI Worker 49 项测试；Checkstyle、SpotBugs、Spotless 均通过。日志：`apps/crm-api/target/security-fix-full-verify-final-unlimited.log`。
- `CaptchaServiceTest` 2 项、`LoginAttemptServiceTest` 3 项、`AuthApiIT` 12 项通过，覆盖一次性验证码、账号/IP 限流、成功登录仅清当前账号计数、IP 防伪造、密码重置后旧会话失效及新密码登录。
- 机构 JSON 与报表定向 27 项、字典真实鉴权 6 项、知识发布及真实 Kafka 9 项通过；`ReportingApiIT` 新增真实 SERVICE_DONE 预约计入待核销的数据库断言。
- 真实浏览器完成错误验证码拒绝、正确登录及连续错误 429；最终版本真实接口伪造 X-Forwarded-For 后，审计仍记录实际连接地址 127.0.0.1。
- 项目标准静态安全扫描通过：Gitleaks 0、Semgrep 0，合成泄露样本能被检出，证据：`tmp/security-fix-static-final/summary.json`。依赖漏洞复查：187 个 Maven 包和 25 个 Python 包均为 0 个匹配漏洞。
- OpenAPI 静态与运行契约一致：190 个路径、227 个方法，1562 个本地引用无缺失，无重复 operationId；CaptchaResponse 在运行时文档中存在。
- 全量回归后恢复真实 ClamAV，`PING` 与 `/init` 文件扫描返回 OK；后端健康检查为 UP，前端登录页可访问。开发/测试 Kafka 堆内存已限制，最终扫描时 ClamAV 约 969 MiB、开发 Kafka 约 339 MiB。

### 验证边界
- 知识发布集成测试使用受控 CLEAN 扫描结果，不依赖本机扫描守护进程；真实 ClamAV 单独验证，不能以 Mock 结果替代真实供应商或生产扫描验收。
- 未执行真实供应商接入、生产渗透、公网 HTTPS、生产容量和数据库恢复演练；本次未重新构建 Linux 安装包。中途超时、依赖服务 OOM 或测试失败的轮次不计为通过，仅上述最终全量日志计为通过。

## [UI-DASHBOARD-READABILITY] 大屏导航精简与字体放大 — 2026-11-06

- 移除大屏顶部整组重复区域导航，保留同一页面中的交易走势、机构履约、待办和原有业务操作。
- 大屏标题、筛选项、图表、排行、待办及底部说明按现有字号增加 2px；截图所示新增客户、支付订单、到院客户和正式核销四项指标在此基础上再增加 4px，其他区域不继续放大。保留配色、响应式布局与 Outfit 数字字体；无新增接口、权限或业务状态变化。
- 大屏相关 17 项测试、前端 typecheck、lint 和生产构建通过；真实浏览器检查首屏及下方业务区域，未发现文字重叠或操作入口截断。

## [P3-OPERATIONS] 管家工作台与机构展示资料补全 — 2026-11-06

### 实现
- 管家工作台“本月业绩”复用现有销售归属报表，按 Asia/Shanghai 自然月展示净支付、支付和成功退款；“剩余服务”通过后端全量汇总展示有效可用次数及涉及客户数，不再显示待接入，也不以第一页数据代替全量。
- 新增 `GET /api/v1/entitlements/summary`，仅统计当前客户数据范围内 AVAILABLE、余额大于零且处于有效期的权益；无范围时返回零，不执行越权汇总。
- 新增 V25 机构展示资料表及回滚脚本，支持医生、仪器和案例的新增、编辑、下架、恢复、分页筛选和真实数量汇总；资源类型不可修改，更新使用乐观锁，创建与更新具备幂等和审计。
- 新增机构资料查询、汇总、创建和更新 4 组接口；机构列表新增 `merchantKeyword` 组合搜索。写操作仅限具备 `institution:write` 的员工，机构账号只读，跨机构读取返回 404。
- 私有文件新增 `MERCHANT_RESOURCE` 类别；可选附件必须属于同机构、通过 CLEAN 扫描并满足机构读取范围。下架仅改变展示状态，不删除资料、文件或审计历史。
- 商户一页纸列表和详情展示真实医生、仪器、案例数量与明细，具备权限的运营人员可直接维护；未填写时只显示真实空状态，不生成虚构机构资料。
- OpenAPI 更新至 188 个路径、225 个方法；本地引用及重复 operationId 检查通过。未新增领域事件或权限码，未改变支付、核销、退款及机构敏感资料审批状态机。

### 已实际验证
- `OrderPaymentApiIT#entitlementSummaryCountsOnlyUsableServicesInCurrentCustomerScope` 通过，覆盖本人与其他管家范围、有效次数及过期排除。
- `InstitutionMerchantResourceApiIT` 2 项真实 MySQL 测试通过，覆盖三类资料创建、幂等、编辑、乐观锁、上下架、数量汇总、跨机构拒绝、附件扫描和机构归属校验。
- 前端 `HomeView`、`merchant-resources`、`institution-permissions` 共 15 项定向测试通过；最终前端全量 101 个文件、421 项测试通过，typecheck、lint 和生产构建通过。日志：`apps/crm-api/target/frontend-full-verification.log`。
- AI Worker 49 项测试通过。日志：`apps/crm-api/target/worker-verification.log`。
- 初次全量验证曾因 Docker 管理接口及开发 MySQL 超时中断；恢复 Docker 后重新执行 `./scripts/verify.sh` 全部通过：后端 253 项单测、265 项真实 MySQL/Kafka 集成测试，前端 101 个文件 421 项测试、typecheck/lint/build，AI Worker 49 项测试，Checkstyle、SpotBugs、Spotless 均无问题。日志：`apps/crm-api/target/verify-system-start.log`。
- 真实浏览器完成管家登录及首页指标检查，本月净支付、支付/退款与有效剩余服务均来自接口，无待接入占位。商务账号使用合成资料完成新增、编辑、下架、恢复、组合搜索及一页纸详情展示，下架后数量与搜索结果正确变化；同时修正了弹窗挂载位置导致资料样式未生效的问题。未使用真实客户隐私，不代表真实供应商接入验收。

## [P3-DEPLOY-2.0.1] 安装包与核心业务复查 — 2026-11-04

### 实现
- ClamAV 只增加必要的出站更新网络，不暴露宿主机端口；验收改为分别核验 main/daily/bytecode 的后台更新日志、病毒库版本和扫描健康状态，不再与后台 freshclam 争用日志锁。
- 新增生产可重复种子 `R__seed_reference_prod.sql`，只补齐订单类型、机构业务类型/性质/等级和结算方式等系统参考代码，不包含开发账号或业务演示数据；重复执行不覆盖运营已调整的名称、排序和启停状态。
- 前端锁定 glob 14.0.3、minimatch 11.0.4，并保留修复后的 follow-redirects 1.16.2；测试并发固定为 4，不增加单项超时或跳过测试。
- 安装验收补充真实 API 核心业务、独立审批人、跨机构和越权拒绝、人工退款核验、Mock 知识同步、显式备份、升级后复验、启停及数据卷保留检查。机构映射与员工数据范围通过隔离测试夹具初始化，不代表已完成对应运营配置功能。
- 精简源码包补充遗漏的 `gitleaks.toml`，并将它纳入归档必要文件检查。

### 已实际验证
- 前端 99 个文件、409 项测试全部通过；typecheck、lint、生产构建通过，npm audit 为 0 项已知漏洞。
- `ProductionProfileTest` 2 项通过；`ProductionReferenceDataIT` 使用真实 MySQL 8.4.8 验证干净生产库迁移、核心字典和重复执行保留运营修改，1 项通过。
- 精简源码包已生成 2892 个文件及 SHA-256；全新解压副本的后端 660 个主源码文件、123 个测试源码文件编译通过，前端重新安装依赖、类型检查和 npm audit 通过，AI Worker 语法及 Shell 语法检查通过。
- 在隔离环境的真实 API 调试中，机构双审激活、SKU、客户下单、人工支付确认与幂等发权益、管家核销 403、机构正式核销、跨机构 404、部分退款与权益回收均通过；素材审核和 Mock Dataset 发布、同步、停用通过，不代表真实 Dify 联通。
- 第一轮完整安装验收完成了服务启动、管理员初始化和登录页访问，但随后因验收脚本手动 freshclam 与后台更新争用锁而失败，未进入业务验收；该轮不计为通过。
- 第二轮完成安装、管理员登录、三类病毒库后台更新检查、ClamAV PONG、文件上传/扫描和专用下载；随后因测试夹具将 SQL 字符串 ALL 误作关键字而失败。已修正夹具文本传值，第二轮仍不计为完整通过。
- 最终候选包在 `tmp/installer-e2e-v9` 的全新数据卷完成 SHA-256 校验、离线镜像载入、生产初始化、登录、文件上传/扫描/专用下载、核心业务及 Mock 知识同步；显式备份和升级前备份的 gzip 完整性通过。升级后再次完整通过业务验收，管理员未重置，停止后数据卷保留，重新启动后再次通过运行验收。日志：`tmp/installer-e2e-v9.log`。本次只验证备份生成和完整性，未执行恢复演练。

### 最终验证与交付边界
- `./scripts/verify.sh` 最终通过：后端 251 项单测、261 项真实 MySQL/Kafka 集成测试，前端 99 个文件 409 项测试及 typecheck/lint/build，AI Worker 49 项测试；Checkstyle、SpotBugs、Spotless 均为 0 问题。日志：`tmp/verify-2.0.1-final.log`。
- 交付 `release/whale-crm-2.0.1-linux-amd64.tar.gz`、`release/whale-crm-source-2.0.1.tar.gz` 及各自 SHA-256 文件。没有新增业务 API 或事件；新增的是生产参考数据可重复种子，无新增业务表。
- 真实公网 HTTPS、真实有赞/企微/Dify 授权联调、生产高可用和数据库恢复演练未验证。本结果不能替代这些上线验收。
- 机构账号映射和员工数据范围尚无完整管理接口或运营配置入口；本次使用隔离测试夹具，正式上线前需补齐功能或明确受控的运维配置流程。
- `spring-webmvc 6.2.19` 的 [GHSA-j9f9-w8pj-32f8](https://github.com/advisories/GHSA-j9f9-w8pj-32f8) 与 [GHSA-pc63-qcmh-9cmg](https://github.com/advisories/GHSA-pc63-qcmh-9cmg) 仍为 Critical，官方公告修复线为 7.0.9；未混入与 Spring Boot 3.5.x 不兼容的框架版本，也未抑制漏洞。

## [SOURCE-PACKAGE] 精简源码包 — 2026-10-29

### 实现与验证
- 新增 `scripts/package-source.sh`，按可继续研发的最小源码集打包应用源码、测试源码、数据库迁移、接口契约、部署脚本和开发文档，不包含原型来源目录。公开配置模板保留，真实 `.env`、凭据、密钥、依赖、编译输出、镜像、运行数据、日志和系统缓存文件均排除。
- 输出 `release/whale-crm-source-2.0.0.tar.gz`（约 1.5 MB）及 SHA-256 校验文件，共 2884 个文件；归档白名单和必要文件检查通过。
- 从压缩包全新解压后，后端 660 个主源码文件和 122 个测试源码文件编译通过；前端 `npm ci` 和类型检查通过；AI Worker 源码与测试语法检查通过；部署与打包脚本 Bash 语法检查通过。未宣称重新执行全量测试。

## [P3-DEPLOY] Linux 离线安装包与生产部署基线 — 2026-10-29

### 实现
- 新增 `infra/production/` 离线安装配置与 `scripts/build-installer.sh`，输出 x86_64 Linux 安装包及 SHA-256 校验文件。应用镜像使用非 root 用户；MySQL、Redis、Kafka 不向宿主机开放端口，Caddy 提供 HTTPS 入口，支持独立文件域名和升级前数据库备份。
- 新增生产 profile、生产 RBAC 种子及一次性管理员初始化命令。初始化使用 BCrypt、审计和客户/机构数据范围，不加载开发账号；重复执行不重置密码，只有实际创建账号才保存初始凭据。JWT、AES、盲索引、数据库和内部调用密钥在安装时随机生成，受限权限文件不进入镜像或安装包。
- 文件存储支持独立公开签名地址和区域配置；ClamAV 经应用网络命名空间内的 `127.0.0.1` 代理访问。正式安装要求系统与文件两个 HTTPS 域名，真实供应商接入继续默认关闭。
- 前端开发环境标识只在开发构建显示，生产登录页和后台不再展示。构建入口清理旧暂存目录，按固定镜像清单和文件白名单防止失效文件混入；验收脚本支持独立 Compose 项目、冷启动、登录、文件链路、升级备份和停止验证。

### 实际验证
- 全栈验证通过：后端 250 项单测、260 项集成测试，前端 99 个文件 409 项测试、typecheck/lint/build，AI Worker 49 项测试；Checkstyle、SpotBugs、Spotless 均为 0 问题。生产标识调整后再次完成前端 409 项测试和 lint。
- 生产管理员命令在真实 MySQL 完成创建与重复执行检查，管理员角色和两项数据范围正确。权限、集成配置及初始化相关 10 项定向真实数据库测试通过。
- `infra/production/tests/verify-package.sh` 在独立数据卷完成最终压缩包校验、离线安装、真实登录、客户与知识接口查询、合成文本上传、ClamAV CLEAN、素材修订专用下载内容一致性、升级前备份和停止验收；升级后再次通过上述业务验收。
- 浏览器验证生产登录和后台正常，登录页与后台均无“开发环境”标识。Compose 配置、Caddy 配置、Nginx 配置、Shell 语法和归档 SHA-256 均通过检查。

### 交付边界
- 交付 `release/whale-crm-2.0.0-linux-amd64.tar.gz` 与同名 `.sha256`。这是单机部署基线，不是 Windows 安装程序，也不替代生产高可用、容量、渗透、恢复演练和真实供应商验收。
- 验证使用本地 HTTP 与合成数据；真实公网 DNS、HTTPS 证书签发、供应商授权及生产数据迁移尚未验收。真实上线仍需提供系统域名、文件域名、开放 80/443 并完成上线门禁。

## [INTEGRATION-RUNTIME] 有赞与企微全局开关页面配置 — 2026-10-29

### 实现
- 新增 V24 每租户集成运行配置表、`integration:runtime:manage` 管理员权限及 `GET/PUT /api/v1/integrations/runtime-config`。保存具备 CSRF、幂等、乐观锁和脱敏审计，未保存页面配置时沿用环境变量默认值。
- 有赞适配器、官方消息、连接测试和增量任务，以及企微客户联系和会话归档路由改为按租户实时读取开关，页面保存后无需重启。环境变量不再承担页面运行时的唯一控制入口。
- 有赞页面提供 `FEATURE_REAL_YOUZAN` 配置，企微归档页面提供 `FEATURE_REAL_WECOM` 与 `WECOM_OFFICIAL_ENABLED` 配置；展示配置来源并保留授权、凭证和 Q10/Q11 提示，不宣称开启即完成真实供应商联通。
- OpenAPI 增加运行时配置接口和模型；本地开发种子补充管理员权限。企微会话归档官方 Finance SDK 仍未接入，真实供应商授权和沙箱验收范围不变。

### 验证
- 定向后端 5 项单元测试、3 项真实 MySQL 集成测试通过，覆盖动态路由、权限、CSRF、审计、幂等、版本冲突和租户隔离；前端配置组件 2 项定向测试、typecheck 与 lint 通过。
- OpenAPI 183 个 paths、1517 个本地引用校验无缺失。真实管理员浏览器完成有赞开关开启保存、页面配置来源读取和恢复关闭；企微两个开关加载正常，最终均保持关闭，未调用真实供应商。
- 最终 `./scripts/verify.sh` 全部通过：后端 250 项单测、260 项集成测试，前端 99 个文件 409 项测试、typecheck/lint/build，Worker 49 项测试；Checkstyle、SpotBugs、Spotless 均为 0 问题。

## [UI-KNOWLEDGE] 知识资料运营界面优化 — 2026-10-28

### 实现
- 重做知识资料发布区与资料列表：发布素材、版本选择和发布按钮紧凑对齐，状态筛选与刷新合入表格工具栏，桌面列宽与手机单列/空状态同步优化。
- 移除面向研发的锁版本说明，资料状态与最近同步状态复用共享语义配色；保留真实数据、发布/重试/停用权限、接口参数和状态机，无新增表、迁移、API 或依赖。

### 验证
- 前端全量 98 个测试文件 407 项通过；知识资料与标签配色定向 10 项通过；`npm run lint`、`npm run build`（含类型检查）通过。
- 真实浏览器检查 `/knowledge/documents`：刷新、状态筛选、空结果及恢复全部正常；1280 桌面完整显示全部列，390 窄屏发布区单列且操作保留。浏览器仅执行查询，未发布或停用真实资料。
- 本次仅修改前端及测试，未重跑后端 Maven、MySQL/Kafka 集成测试。

## [UI-DASHBOARD] 运营数据大屏内测第一版 — 2026-10-28

### 实现
- 新增独立 `/operations/dashboard` 与 `GET /api/v1/reports/operations-dashboard`，在同一报表应用服务内聚合经营、业绩和活动结果，保持租户/角色/数据范围、整数分、真实日期与数据水位；无新增表、迁移、权限码或供应商接入。
- 使用按需加载的 ECharts 展示支付、净支付、支付订单、成功退款、到院客户和正式核销，以及日趋势、履约阶段计数、机构核销与活动净支付排行。阶段图不计算 Q13 转化率，净支付不称净利润，空事实不补零值日期。
- 支持今日/本周/本月/自定义日期、来源、收款方、授权机构筛选，60 秒自动刷新、手动刷新、返回运营中心与浏览器全屏。筛选变化清空旧口径，后台刷新保留成功数据，过期响应不能覆盖新筛选，卸载停止定时器并销毁图表。
- 独立路由只隐藏 AppShell，不设为公开页面，继续要求登录和 `report:read`。原登录页和既有 CRM 页面保持不变；机构选项只来自已授权报表行，不额外开放机构主档。
- 前端请求编号由旧 UUID 改为标准 ULID；内测 Vite 服务已从错误的 8081 代理目标恢复到 8080 最新后端。

### 验证
- 最终 `./scripts/verify.sh` 全部通过：后端 239 项单测、256 项集成测试，前端 98 个文件 405 项测试/typecheck/lint/build，Worker 49 项测试；日志为 `tmp/dashboard-final-verify.log`。
- 后端聚合定向验证为 2 项单测、8 项真实 MySQL 集成测试；覆盖真实金额/日趋势/机构/活动、匿名 401、无权限 403、非法日期、租户与本人/团队范围，以及无范围时不执行 SQL。最终全栈复验的后端 239 项单测、256 项集成测试已通过，Checkstyle/SpotBugs 为 0。
- 大屏相关 6 个前端测试文件 56 项通过，ULID 与 HTTP 定向 9 项通过；OpenAPI 182 个 paths、1505 个本地引用及重复 operationId 检查通过，npm audit 为 0 个已知漏洞。
- 真实管理员浏览器验收：大屏与原运营报表均为支付 ¥7244.00、退款 ¥100.00、净支付 ¥7144.00，订单与机构核销一致；今日空态、周筛选、真实机构筛选和自动刷新正常。1920×1080 一屏无滚动条，1366×768 纵向阅读及 iPhone 14 Pro 仿真通过，截图保存在 `tmp/operations-dashboard-1920.png`、`tmp/operations-dashboard-mobile.png`。
- 浏览器全屏按钮已触发系统权限提示，只允许用户手动确认；未将自动化无法代替的权限确认写成全屏验收通过。手机结果为仿真，不冒充实机。

### 首版边界
- 客户分层、异常提醒、管家排名、环比及 Q13 转化率留待后续；不伪造演示数据。首版仅用于本地内测，不改变 Spring 漏洞、真实供应商授权及生产上线门禁状态。

## [UI-COPY] 中文业务文案与时区精简 — 2026-10-28

### 实现
- 业绩状态不再直接显示 `UNCONFIGURED`、`NOT PAYROLL`，改为未配置、非工资核算依据等中文；目标、完成率和佣金状态使用统一中文映射。“下钻”统一改为“查看明细”，`dailyItems` 改为每日明细，运营说明不再暴露字段名。
- 移除业绩与运营页面的时区下拉框，以及导出、风控、活动和付款表单中的 `Asia/Shanghai` 展示；后端查询与 Instant/本地时间转换仍保留 `Asia/Shanghai`，未改变日期计算或接口参数。
- 知识资料使用“知识数据集”和中文同步任务状态；知识库配置改为数据集 API 密钥、模拟模式和模型调用量。任务退回改为待处理任务池，退款改为模拟退款，机构标题改为机构端，企微添加来源与错误请求编号均中文化。

### 验证
- 相关页面 13 个测试文件、69 项测试通过；全量 94 个测试文件、390 项测试通过。`typecheck`、相关文件 lint 和生产构建通过。
- 真实浏览器复核业绩页：显示未配置、非工资核算依据、查看明细和每日明细，无时区控件；接口参数与业务数据未改变。

## [P3-QUALITY] Java 质量门禁与本地复验 — 2026-10-28

### 实现
- `apps/crm-api/pom.xml` 配置 Checkstyle 3.6.0、SpotBugs 4.10.5.0、Spotless 3.10.4，并绑定后端 `verify`；CI 复用该命令。Spotless 只检查 Java 四空格缩进、行尾空白和文件末尾换行，不做全项目格式重排。
- 为各模块 record DTO 的集合字段补齐保留 null 的防御性复制；导出范围和限制的访问器返回不可变副本。SpotBugs 从 219 项集合引用暴露清零，未扩大豁免范围，未改变接口结构、金额口径、状态机、数据权限或数据库。
- requestId 继续只透传标准 ULID，非法输入由服务端重新生成；两处旧测试夹具改用合法 ULID，保留原样回传断言。`scripts/load-smoke.sh` 兼容 ab 零失败时省略失败分类行，非零失败缺少分类仍判定失败。

### 实际验证
- 三项工具直接检查通过后，最终 `./scripts/verify.sh` 再次全部通过：后端 238 项单测、255 项真实 MySQL/Kafka 集成测试，前端 94 个文件 390 项测试及 typecheck/lint/build，Worker 49 项测试；Checkstyle/SpotBugs 均为 0，Spotless 检查 764 个 Java 文件无需改动。日志：`tmp/p3-quality-verify-final.log`。
- requestId 定向复验为 6 项单测、6 项真实 MySQL 集成测试通过，日志：`tmp/p3-quality-request-id-targeted.log`。
- 最终源码 Gitleaks 零泄漏、Semgrep 308 条 OWASP 规则零阻断，反向自检命中；证据：`tmp/p3-static-security-1793187795-b331622ece/summary.json`。
- 最新 OSV 审计覆盖 161 个 Maven 包、25 个 PyPI 包，仍只有 `spring-webmvc 6.2.19` 的 `GHSA-j9f9-w8pj-32f8`、`GHSA-pc63-qcmh-9cmg`，状态保持 FAIL；证据：`tmp/p3-dependency-audit-1793187060-c715c3efdc/summary.json`。官方公告和 Maven Central 已复核，未强行混入 Spring 7，也未抑制漏洞。
- 最新 JAR 在本机 8080 健康检查为 UP，真实登录成功；客户列表和对账各 200 次、并发 5，p95 分别为 29ms、32ms，传输错误、非 2xx 和动态长度差异均为 0。证据：`tmp/p3-load-1793187741-76f71497cf/summary.json`。

### 边界
- 本机质量门禁、限定源码扫描和只读负载均不构成生产安全、容量或 SLA 验收。Spring 两项漏洞、真实渗透、生产部署、灰度与回滚仍未关闭；真实供应商接入及生产上线继续受既有授权约束。

## [P3-STATIC] Secret 与 OWASP 静态扫描 — 2026-10-28

### 实现与实际验证
- 新增 `scripts/static_security_scan.sh` 和 `gitleaks.toml`，以固定 Gitleaks 8.30.1、Semgrep 1.181.1 容器扫描限定源码副本；排除秘密文件、依赖目录和临时凭据，关闭 Semgrep 遥测，报告目录权限 700，原始泄漏内容脱敏。CI 新增 `static-security` 门禁。
- 首轮发现 5 条 secret 线索和 14 条 SAST 阻断：CI Actions 全部改为官方 v4/v5 标签对应的完整提交 SHA；ClamAV 明文 TCP 仅允许本机地址，非本机地址在连接前拒绝，先添加失败回归后通过 17 项定向测试。
- 企微 CBC/SHA1、有赞 MD5 验签和 MinIO/S3 ETag 按官方协议保留并注明使用边界，不替换为不兼容算法；合成测试密钥仅按明确测试文件豁免，未全局忽略。扫描器反向自检使用运行时生成的虚构密钥，验证默认规则确实命中。
- 最终 Gitleaks 零泄漏、Semgrep `p/owasp-top-ten` 308 条规则零阻断，覆盖 830 个目标文件；反向自检命中，汇总状态 PASS。证据：`tmp/p3-static-security-1793181805-594f4327bc/summary.json`。
- 最终 `./scripts/verify.sh` 全部通过：后端 236 项单测、255 项集成测试，前端 390 项测试/typecheck/lint/build，Worker 49 项测试。最终 JAR 上真实上传文件 #16，MinIO 返回 ETag，本机 ClamAV 为 CLEAN，未因本机地址限制中断正常扫描。

### 边界
- 这是限定源码和 OWASP 规则基线，不覆盖秘密文件内容、完整渗透测试、生产安全签字或所有语言/框架专项审计。Spotless、Checkstyle/SpotBugs、Spring 两项依赖漏洞和生产门禁仍未完成。

## [P3-DEPENDENCY] Java/Python 依赖漏洞审计与修复 — 2026-10-28

### 实现与实际验证
- 新增 `scripts/dependency_audit.py`：用 Maven 解析依赖清单和 AI Worker 虚拟环境实际安装包调用 OSV 官方 API，保存原始查询、完整漏洞明细和报告。发现漏洞退出 1，查询/解析失败退出 2，不用宿主机 Python 代替 Worker 环境，也不以网络失败冒充通过；已加入 CI `dependency-audit`。
- Python 升级 FastAPI 0.162.0、Starlette 1.3.1、pytest 9.0.3，先在隔离 Python 3.12 环境验证，再同步 Worker 环境；两次 49 项测试通过，25 个 PyPI 包 OSV 无命中。系统 python3 为 3.9 时曾被版本过滤，未退回有漏洞版本。
- Java 按 OSV 修复线升级 Jackson 2.21.7、Netty 4.1.137.Final、Tomcat 10.1.59、Log4j 2.25.5、Guava 33.7.2-jre、LZ4 1.11.4、Snappy 1.1.10.9、BouncyCastle 1.85、Commons Lang 3.18.0；Boot 保持 3.5.16，未混入 Spring 7。
- `./scripts/verify.sh` 全部通过：后端 235 项单测、255 项集成测试，前端 94 个文件 390 项测试/typecheck/lint/build，Worker 49 项测试。真实 Kafka 分别使用 LZ4 和 Snappy 完成发布、重放去重及 DLQ，非仅检查配置。
- 补丁 JAR 的 M01 复验通过：增量恢复、11 张表 checksum、账本/核销/冲正对账、4 个对象哈希、真实登录/对账及私有签名下载均成功；最新报告见 `docs/05_测试与交付/M01_恢复演练报告.md`。

### 尚未关闭的漏洞与边界
- 最新 OSV 报告：`tmp/p3-dependency-audit-1793175573-094bd2a9d3/summary.json`，161 个 Maven 包、25 个 PyPI 包中仅剩 `spring-webmvc 6.2.19` 的 `GHSA-j9f9-w8pj-32f8`、`GHSA-pc63-qcmh-9cmg`；审计保持 FAIL，CI 未宣称通过。
- OSV 修复线为 Spring Framework 7.0.9，不能将其直接混入 Boot 3.5 的 Spring 6.2 依赖。主代码未发现 SSE 或 XsltView 使用，但这只是使用面检查，不是漏洞豁免或生产风险签字；需要另行评估框架升级/修复版本，不能靠删除扫描结果完成。
- 本审计是公开漏洞版本匹配，不覆盖源码 SAST、secret 扫描、恶意代码、渗透测试、生产环境或上线批准。

## [P3-LOCAL] 本机负载与安全门禁 — 2026-10-28

### 实际验证
- 新增 `scripts/load-smoke.sh`，只允许本机目标，真实登录后只读请求客户列表和结算对账。每个接口 200 次、并发 5，p95 分别为 27ms、31ms，传输错误和非 2xx 均为 0；每个接口有 1 次 ab 动态长度差异，已单独记录而未当作连接失败。证据：`tmp/p3-load-1793173011-34333958bc/summary.json`。
- `npm --prefix apps/crm-web audit --package-lock-only --ignore-scripts` 两次返回 0 个已知漏洞，同一门禁已加入 `.github/workflows/ci.yml`。该结果仅覆盖 npm，不代替 Java/Python 依赖扫描。
- 真实 HTTP 安全冒烟：匿名客户查询 401、缺 CSRF 导出 403、普通管家带 CSRF 导出仍 403、素材通用文件下载 403、未签名对象地址 403；拒绝审计已写入，未创建导出任务。证据：`tmp/p3-security-1793173260.json`。
- 最终 JAR 已在本机 8080 启动，真实浏览器确认最初账号密码登录、真实客户数据、语义色及表格列设置面板，无调试输出。前端 Vite 和 Mock AI Worker 使用既有本地服务。

### 未完成边界
- 后续已新增 Maven/Python 的 OSV 依赖审计及 Gitleaks/Semgrep 扫描，剩余 Spring Web MVC 两项漏洞未关闭；Spotless、Checkstyle/SpotBugs 仍未配置，不能将上述结果称为完整静态安全或渗透验收。
- 没有真实容量参数、预发/生产环境、生产凭据、供应商授权和灰度批准，生产容量、渗透、部署、灰度及回滚仍待验收。本机负载与 HTTP 冒烟不构成上线通过结论。

## [P3-M01] 数据库与对象存储隔离恢复演练 — 2026-10-28

### 实际验证
- 新增可重复执行的 `scripts/m01-recovery.sh`：开发源只允许默认本地容器或显式授权源；独立 MySQL/MinIO 使用合成凭据，临时复制账号、探针和容器在结束后清理，失败证据保留在 `tmp/m01-*`，不覆盖开发库或删除开发卷。
- `mysqldump --single-transaction --source-data=2` 备份后，使用 MySQL 原生 `START REPLICA UNTIL` 按 file/position 恢复增量。`binlog.000003:262074 → 262398` 的复制完成，IO/SQL 错误为空；Flyway 成功迁移最高为 V23，11 张核心表 checksum 与源一致。
- 权益余额与账本一致，核销负流水和 APPROVED 冲正补偿逐行匹配；4 个合成私有对象恢复后哈希一致。最终 JAR 连接隔离库/桶完成真实登录、对账查询、素材修订私有下载，文件 #10 SHA-256 与元数据一致，MIME 为 application/pdf，未签名对象地址 403。
- 本地全链路实测 34 秒，报告与证据见 [M01_恢复演练报告.md](docs/05_测试与交付/M01_恢复演练报告.md)。脚本已适配 MySQL 8.4 切点、真实认证就绪检查、mc 跨容器配置和空 sealed 前缀。

### 边界
- 这是本机合成数据的真实 PITR 演练，不是生产恢复、异地容灾或批准的 RPO/RTO/SLA。GTID、跨区复制、对象锁、定时备份、真实供应商和生产恢复签字未覆盖；其余 P3 门禁继续。

## [P3-K01] 真实 Kafka 可靠性验收 — 2026-10-28

### 实现与验证
- 知识同步 Consumer 强制校验必填信封、occurred_at、`correlation_id=event_id` 和仅含 jobId/documentId 的 payload；拒绝正文、密钥及未知字段。无新增业务 API、表或迁移。
- 使用独立 MySQL 8.4 与 `apache/kafka:4.1.2` 容器验证真实发布 API → Outbox Relay → Listener/Inbox → Worker。相同 Outbox 受控重放两次后仍仅一条 Inbox、Worker `attempt_count=1`、文档 ACTIVE；非法事件进入真实 DLQ，原始 key/value、topic/partition/offset 与异常头可追溯，未写 Inbox 或改变业务状态。
- 修复企微回调旧快照绕过重试退避的问题，认领同时校验 attempts 与 next_retry_at；先取得失败回归，再验证到期重试正常。测试 local 配置统一关闭后台导出/Outbox 调度并延长回调间隔，避免共享 MySQL 的其他上下文竞争，显式业务调用保留。
- 修复共享表格 Vue 3 事件监听、当前 Element Plus 不支持的属性、语义色类型/样式及空值索引；保留列设置、拖宽、真实业务按钮和原型配色。

### 实际结果与边界
- `./scripts/verify.sh` 全部通过：后端 235 项单测、254 项真实 MySQL 集成测试，前端 94 个文件 390 项测试/typecheck/lint/build，AI Worker 49 项测试。
- 仅覆盖 `knowledge.sync.requested.v1`；MinIO 和 Dify 在该集成测试中使用测试适配，不宣称真实 Dify、其他业务 Topic、生产审批回放或生产上线已验收。M01 恢复演练和其余 P3 门禁继续进行。

## [UI-TABLE] 全局表格列宽与显示列设置 — 2026-10-28

### 实现
- 新增 `AppTable` 共享封装并接入 54 个 Vue 文件、80 张业务表格；使用 Element Plus 原生表格、列和事件，保留固定列、展开行、空态、选择框及业务按钮。
- 全部表格支持表头拖动调宽及“列设置”复选显示/隐藏；至少保留一列，支持恢复默认。设置按当前用户与表格写入浏览器 `localStorage`，只保存列标识、显示状态和宽度，不保存行数据、手机号或会话正文，存储不可用时使用默认设置。
- 用户管理普通列改为 `min-width` 伸展分配剩余空间，减少固定宽度造成的右侧空白；小屏继续横向滚动。没有修改后端接口、数据权限、状态机或数据库。

### 实际验证与限制
- `typecheck`、全量 lint 通过；94 个测试文件、390 项测试全部通过。覆盖隐藏/恢复、宽度保存和重新挂载恢复、损坏配置容错、至少一列、选择框事件、用户页列宽与抽屉表格。
- 真实浏览器已验证用户管理的列设置面板、隐藏列及对应单元格、刷新后保持隐藏、恢复默认和拖动调宽；拖宽持久化已有组件测试，真实浏览器刷新保留未完整复核，不宣称此项已验收。
- 验收时发现的 `utils/tagTone.ts:38` 空值索引、详情抽屉语义色类型及不受支持的表格属性，已在后续 P3-K01 收尾中修复；最终 `npm run build` 和统一全栈验收均通过，未改变业务接口或原型语义。
- 临时浏览器诊断代码已移除。没有后端 API、Flyway 或事件契约变更。

## [UI-TAGS] 列表标签统一语义配色 — 2026-10-28

### 实现
- 新增 `utils/tagTone.ts`，按业务含义映射标签颜色：普通订单青绿、快闪 orange、大促 purple、未知类型灰；支付/成功绿、待处理 amber、异常红、信息蓝、停用灰。
- 配色复用到订单类型与状态、素材类型、客户分层与状态、用户角色与状态、任务优先级和活动批次；保留原标签文字与数据，不以颜色替代业务状态，也不改变接口或状态机。
- `prototype.css` 新增 `purple/teal/orange/pink` 色调并保留既有 `success/warning/danger/info/dark`，未使用高饱和渐变或仅靠颜色传达含义。

### 验证
- 新增 `tag-tone.test.ts` 覆盖映射、未知值和新增样式；订单相关 8 个测试文件共 50 项测试通过，本次文件 ESLint、`npm run typecheck`、`npx vite build` 通过。
- 真实浏览器确认未知订单类型灰、普通订单青绿、已支付绿、素材类型差异，以及管理员紫、管家青绿、主管蓝、财务 amber；角色列与操作按钮文字正常。

## [P0-2-UI] 套餐销售规格直接展示 — 2026-10-28

- 产品页通过现有 SKU 分页接口批量读取规格，按套餐直接显示真实规格、售价和上下架状态；不再用“点击选择查看”占位，无 SKU 时明确显示“暂未配置销售规格”。
- “选3项”明确解释为从候选子项目中选择，不等于使用次数；快照同步使用清晰文案。版本机构入口改为读取当前行套餐，避免依赖其他行的选中状态。
- 只调整展示，不改套餐规则、历史快照、后端 API、数据库或事件契约。新增失败回归后完成修复，2 项定向测试、前端全量 92 个文件 383 项测试、typecheck、lint 和生产构建全部通过。
- 真实浏览器确认素颜卡显示选3项及 ¥199.00，另一套餐多档规格和价格直接展开；快照显示“可从候选子项目中选择 3 项（不是使用次数）”。仅做只读验收，未新增或修改业务数据。

## [P2-UI] 报表时间与统计口径中文化 — 2026-10-28

- 报表上下文不再展示 UTC 边界和时区编码；数据水位及活动时间统一为本地 `YYYY-MM-DD HH:mm:ss`，活动管理移除时区下拉和 UTC 标题。
- 内部 `P2_FACT_V1` 改为“统计口径 V1”，并说明其为成交、退款、净支付和预约等计算规则版本，不是客户或订单版本。
- 新增报表上下文测试并更新旧 ISO 断言：相关 4 个测试文件、19 项通过；typecheck、lint、生产 build 通过。

## [P2-UI-FIX] 素材库操作按钮文字显示修复 — 2026-10-28

- 修复素材列表“预览”和“修改”只剩青绿色块的问题：文本按钮误用 `type="primary"`，导致文字颜色与主按钮背景一致。移除冲突类型，保留原有文字链接样式和全部业务行为。
- 回归测试新增按钮文案及无 `el-button--primary` 类断言；素材页 8 项测试、本次文件 ESLint 和 Vite 生产构建通过，真实浏览器确认“预览、修改、版本、下架”均正常显示。

## [0.1.1-SNAPSHOT] 文件安全与客户合并修复 — 2026-10-28

- 支付凭证要求支付确认权限和客户全量数据范围；上传扫描后发布服务端封存对象，历史文件重新校验扫描后封存，校验失败拒绝使用。
- 订单或权益存在预约、核销或退款关联时，自动合并及相关快照的自动撤销返回冲突，保留历史记录；相关创建与迁移通过资源锁协调。
- 无新增表、迁移、API 或事件签名；前端独立版本未变。主审独立 clean verify：233 项单测、252 项 MySQL 集成测试全部通过；前端 typecheck/lint、379 项测试及 build 通过，AI Worker 49 项通过。正式 OpenAPI 181 个路径、1482 个引用检查通过。
- 知识发布旧模拟存储已补齐封存对象读写和内容断言。本轮未重跑真实 Kafka/MinIO/ClamAV 与浏览器 E2E；详见 [修复验收记录](docs/05_测试与交付/53_修复验收_0.1.1.md)。

## [P2-E2E] 运营治理、原型纠偏与真实业务验收 — 2026-10-28

### 实现
- 素材页恢复最终原型的七列表格，显示真实机构/项目名称，保留版本审核与私有预览；新增 V23 主档下架字段，要求原因、主档乐观锁、CSRF 和幂等键。下架不修改审核修订或当前版本指针，普通管家不可见，授权管理角色仍可查阅历史。
- 风控响应按真实来源关联订单和客户，未知关联保持 null；页面增加客户列与有赞同步入口。SKU 恢复单表格，真实质量统计与检查项移入按需打开的抽屉。素材上传支持 MATERIAL 类别的 UTF-8 TXT/Markdown，合同等 PDF 专用类别不放宽。
- 知识资料按 read/publish 任一权限进入，写操作单独受 publish 控制，补齐状态筛选、分页和连续成功反馈；用户团队改为真实选项。顶栏导出、报表快捷入口和六列权限基线接入真实页面，最初账号密码登录页保持不变。
- 修复素材查询参数、重复迁移构建残留、缺失请求头误返 500、Markdown 别名与普通段落类型识别问题。OpenAPI 补齐知识 502/503、文本上传、导出字段白名单、素材下架和风控关联字段。

### 实际验证
- `./scripts/verify.sh` 全部通过：后端 232 项单测、242 项真实 MySQL 集成测试；前端 90 个文件 379 项测试；AI Worker 49 项测试。原型收尾后再次执行前端全量 test/typecheck/lint/build，仍为 379 项全部通过。OpenAPI 校验 181 个 paths、322 个 schemas、1482 个本地引用，未解析引用和重复 operationId 均为 0。
- 浏览器创建合成 PDF 素材，MinIO 预签名 PUT 返回真实 ETag，文件 #10 为 CLEAN；管理员送审、`business1` 审核后显示 APPROVED，私有预览打开 MinIO 短期签名 URL。普通管家只见已审核版本，无新增、修改、下架和导出入口，直接访问导出页被拦截。
- X01：`manager1` 创建和下载导出均为 403，REQUEST/DOWNLOAD 均有 DENIED 审计。X02：请求 TEAM 1+2 被拒绝，合法 TEAM 1 导出为 6 行，中文表头、行数、脱敏和 TEAM 1 范围一致；导出文件 CLEAN，未签名对象地址返回 403。实际 CSV 保存为 `tmp/p2-e2e-team1.csv`。
- 创建活动 `P2-E2E-20261028-01` 与 ACTIVE 批次 `E2E-WAVE-01`。D01 创建订单类型及订单后改名/停用，旧订单仍保留“验收活动单原始名称”，新订单使用停用类型返回 409 CONFLICT。
- 通过真实 Mock 有赞回调生成未匹配客户异常，风控完成 REGISTER → CLAIM → RESOLVE → CLOSE，历史完整且客户关联保持 null；`manager1`、机构用户访问全局风控均为 403。素材 #1 下架后普通管家可见数为 0，审核状态/版本保留，授权审核角色可继续私有预览。
- 合成 Markdown 文件 #15 经实际上传、ClamAV CLEAN、送审和独立审核后发布，任务 #1、文档 #1 经 Outbox/Kafka/Inbox/Mock Dataset 达到 ACTIVE/SUCCEEDED，随后 DISABLED。仅证明本地 Mock 链路，不包含真实 Dify、公开问答、多轮会话、PDF/OCR 或模型效果验收。
- 浏览器复核桌面素材表格、风控客户列、SKU 单表格和质量抽屉，并完成 iPhone 14 Pro 仿真；仿真不冒充实机。P3 的负载、恢复、安全、生产部署和灰度回滚未开始。

## [P2-KB-2-DISABLE] 知识资料停用状态机加固 — 2026-10-28

### 实现与边界
- 验收复核发现停用与同步并发时，晚到 AVAILABLE 结果可能覆盖 DISABLED。修复认领、执行、任务状态更新及最终激活条件，已停用资料不再进入 Worker，也不能由晚到结果重新标记为 ACTIVE。
- 合法晚到事件在记录 Inbox 后不再推进已停用任务；人工重试与再次发布均不能隐式启用 DISABLED 资料，前端同步隐藏重试入口。重新启用需另阶段确认 Dify enable 语义，不在本次范围内。
- OpenAPI 和 Kafka 设计文档已同步这一状态边界；无新增表、迁移、API 或事件类型，不启用真实 Dify。

### 实际验证
- 先添加失败回归：单测复现已停用文档仍进入同步/消费/发布流程，真实 MySQL 集成复现晚到成功覆盖停用，前端复现已停用仍展示重试。
- `./mvnw -pl apps/crm-api -Dtest=KnowledgeSyncExecutionServiceTest,KnowledgeSyncConsumerTest,KnowledgePublicationServiceTest,KnowledgeManagementServiceTest,KnowledgeSyncWorkerTest -Dit.test=KnowledgePublicationApiIT verify`：19 项单测、7 项真实 MySQL 集成测试通过，覆盖停用期间晚到成功、不可再认领/重试/发布。
- `npm --prefix apps/crm-web run test -- tests/knowledge-documents-view.test.ts`：5 项通过；typecheck、lint 通过。OpenAPI 180 个 paths、1473 个本地引用解析通过。
- 素材类型新增下架字段后，知识页测试 fixture 已补齐三个空值字段；`npm --prefix apps/crm-web run build` 复验通过，仅有既存大 chunk 提示。
- 修复后复跑 `./scripts/verify.sh`：后端 232 项单测、242 项 MySQL 集成测试全部通过；前端 90 个文件 379 项测试中 376 项通过，知识资料相关测试通过，仍只有其他模块 `operations-view.test.ts` 的 3 项 Pinia 初始化失败，脚本退出 1。
- 前端生产构建已单独复验通过，Worker 全量 49 项通过。统一验收仍不能宣称通过，真实 Dify 联调也未执行，P2-KB-2 保持进行中。

## [P2-KB-2-ACCEPTANCE] 契约核对与阶段验收结果 — 2026-10-28

### 契约与实际验证
- `./mvnw -pl apps/crm-api -Dit.test=MaterialApiIT verify`：3 项通过、BUILD SUCCESS，此前的非正数筛选参数校验阻塞已解除。
- `./scripts/verify.sh`：后端 BUILD SUCCESS，219 项单元测试和 236 项真实 MySQL 集成测试全部通过；前端 typecheck、lint 通过。90 个前端测试文件共 375 项测试中 372 项通过，其他模块 `operations-view.test.ts` 的 3 项因未初始化 Pinia 失败，脚本退出 1；未修改无关模块来绕过失败。
- 单独补跑 `npm --prefix apps/crm-web run build` 成功，仅有既存大 chunk 提示；`apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests -v` 为 49 项通过。
- OpenAPI 通过仓库现有 `js-yaml` 解析：180 个 paths、1473 个本地引用均可解析。事件契约补齐 `knowledge.sync.requested.v1`、`knowledge_document` 及仅含 `jobId/documentId` 的 payload；先确认旧契约检查失败，再用仓库现有 Ajv 做兼容校验，实际最小事件通过，正文、密钥和客户 ID 额外字段均被拒绝。

### 阶段边界
- 上述结果不表示统一验收全部通过，P2-KB-2 保持进行中。真实 MySQL/Kafka/MinIO/ClamAV + Mock Dataset 的 E2E 已完成；没有真实 Dify 凭据，未执行真实 Dify HTTP 或检索效果验收。
- 公开问答、多轮会话、PDF/OCR 和生产灰度未开始；Q19 的真实部署/模型/凭据/数据政策及 Q20 的资料范围/角色/保留期待未确认，`FEATURE_REAL_DIFY=false` 继续保持。

## [P0-1-MERGE-REVERSAL] 客户错误合并更正 — 2026-10-28

### 实现与边界
- 新增 V22 合并前资源快照和 `crm_merge_reversal`，已合并记录可走申请更正、管理员审批执行、人工完成及驳回；不删除原合并或审计历史，新增 `customer:merge_reverse`，开发环境仅 ADMIN 默认拥有。
- 确认合并只快照实际迁走的身份、标签、跟进、任务、积分、订单和权益；更正只按这些 ID 迁回，主客户原有、去重删除及合并后新增资源不被误迁，双方积分缓存按账本重算。去重删除的身份/标签保留人工复核警示。
- 历史无快照时只恢复从客户并进入 `MANUAL_REVIEW`，人工逐项处理确认后原合并单才改 `REVERSED`，不伪造完整恢复。支付金额、权益发放及核销事实不直接覆盖。
- Web 已提供对应操作、状态及警示；审批/完成/驳回使用更正单版本。双方客户均校验数据范围，写操作要求 CSRF、幂等键和审计；执行失败回滚业务变更，以独立事务记录脱敏错误及审计。
- 修复 `CONFIRMED → REVERSED` 被原 SQL 状态条件拒绝、重复标签进入快照导致主客户标签误迁的问题；权限目录测试更新为 62 项。OpenAPI、状态机、字段字典及任务清单已同步，无新增事件契约。

### 实际验证与剩余阻塞
- 后端 `test-compile` 通过；`CustomerMergeReversalApiIT` 4 项、`CustomerMergeApiIT` 3 项、`RolePermissionApiIT` 6 项真实 MySQL 集成测试通过。统一脚本中的后端全量 218 项单元测试、234 项集成测试全部通过。
- 前端 `typecheck`、全量 lint 通过；更正页面/API 定向 13 项测试通过。全量 89 个测试文件共 373 项测试中 370 项通过，剩余 `operations-view.test.ts` 3 项因其他页面测试未初始化 Pinia 失败。
- `scripts/verify.sh` 已执行但因上述其他前端测试失败而退出 1，后续 build/AI-worker 未由该脚本执行。单独 `npm run build` 被 `router/menu.ts`、`MaterialsView.vue` 等其他文件类型错误阻断，本次新增测试的类型问题已修复。
- 新增 OpenAPI 路径与 schema 分别通过 Ruby YAML 解析；整文件仍被已有 `payloadMaskedJson` 行内冒号语法错误阻断，未为通过验证修改无关模块。未执行真实浏览器 E2E；本功能无 Kafka 事件变更，未宣称真实浏览器或供应商联调通过。

## [P2-KB-2-E2E] 知识资料真实 Broker 端到端验证 — 2026-10-28

### 实现与验证
- 管理 API 5 项真实 MySQL 集成测试通过，覆盖资料列表、失败重试、远端停用顺序、权限与幂等。`clean` 仅清除陈旧构建产物后，全部 Flyway 迁移在隔离 MySQL 8.4 中启动成功。
- 使用真实 MinIO/ClamAV 上传合成 Markdown，经管理员创建/送审、另一账号 APPROVED 审核、显式发布、Outbox Relay、Kafka consumer/Inbox 和同步 Worker，最终文档 ACTIVE、任务 SUCCEEDED，随后停用为 DISABLED。
- 数据库确认 Outbox SENT、Inbox `knowledge.sync.requested.v1:1` 已处理、任务 attempt_count=1、Mock document 映射正确；浏览器展示真实素材、修订 #2、Mock Dataset 和停用状态。临时服务与容器均已清理。
- 为真实上传补齐 `text/plain`/`text/markdown`/`text/x-markdown` 接受与严格 UTF-8 检测，明确 Markdown→CSV→普通文本的优先级；保留原有 CSV `INVALID_CSV` 错误码，相关协议与新增上传契约共 10 项测试通过。
- 本结果仅证明 Mock Dataset 与 Kafka 链路，未调用真实 Dify，不包含公开问答、多轮会话、PDF/OCR 或生产验收；全量 `scripts/verify.sh` 结果另记录。

## [P0-1-UI] 用户管理多维筛选与页面重设计 — 2026-10-28

### 实现
- 用户列表新增 `UserSearchFilter`，支持关键词、状态、用户类型、团队和启用角色在后端组合筛选；SQL 显式限定租户，角色通过 `EXISTS` 匹配，未选择维度不限制结果。
- 新增 `GET /api/v1/users/teams` 返回当前租户启用团队选项，OpenAPI 同步组合参数与团队响应；未新增数据库表、状态机或事件契约。
- 用户页改为关键词、用户类型、所属团队、角色、状态五维筛选，全部默认显示“全部”；支持已选条件标签、一键重置、窄屏单列和统一文字操作按钮。角色列固定为 180px，剩余弹性空间由操作列承担，避免角色与状态之间出现大块空白。

### 验证
- 前端 `users-api.test.ts`、`users-view.test.ts` 共 3 项通过，覆盖默认全部、组合参数、重置及团队端点；本次文件 ESLint、`npm run typecheck`、`npx vite build` 通过，OpenAPI YAML 解析通过。
- 真实接口验证：`EMPLOYEE + 管家一组 + MANAGER + ACTIVE` 仅返回 `manager1`，`EMPLOYEE + 管家二组 + MANAGER_LEAD` 仅返回 `lead2`，机构用户返回 3 条；真实浏览器确认组合结果、五个筛选项和角色列。
- 本地验证使用 `CRM_API_ORIGIN=http://127.0.0.1:8081`，避免其他进程自动接管 8080；默认代理仍为 8080。后续完整构建包已包含筛选代码，服务恢复已改用正式构建产物，不再依赖手工补入的固定副本。
- 全量后端编译/测试仍受其他模块阻断：合并仓储缺少 `getTenantId`，AI 知识测试构造器签名不一致；因此未宣称全量 Maven 测试通过。

## [P2-KB-2-MANAGEMENT] 知识资料管理与状态页面 — 2026-10-28

### 实现与边界
- 新增资料列表/最新任务状态、失败重试和停用 API：FAILED/RETRY 可重置为 PENDING 且 attemptCount=0；有远端 documentId 时先停用 Dify 再更新 CRM，未创建远端文档的等待任务可直接停用。所有写接口要求知识发布权限、CSRF 和幂等键。
- 扩展 Dataset 配置与同步开关页面，新增知识资料管理页，支持选择 APPROVED 素材修订发布、查看状态、按真实 ID 重试和停用；不展示正文或密钥，不修改素材审核历史。
- OpenAPI 同步资料/任务响应、列表分页、重试和停用契约并解析通过；真实 Broker 与公开问答仍未完成。

### 实际验证与阻塞
- 管理/消费单测 7 项通过；管理 API MySQL 集成启动被 `V17__customer_merge_reversal.sql` 与 `V17__p2_operations_governance.sql` 重名阻断，未改名、repair 或绕过。
- 知识资料相关前端 13 项定向测试、全量 87 个文件 364 项测试和 typecheck 通过；lint/build 均被 `CustomerMergeView.vue` 的 5 个未使用声明阻断，未修改无关模块。
- 真实 Broker E2E 和 `scripts/verify.sh` 未执行，P2-KB-2 保持进行中，不能据管理页面宣称完整同步验收。

## [P2-KB-2-CONSUMER] Kafka consumer 与 Inbox 幂等推进 — 2026-10-27

### 实现与边界
- 新增 KnowledgeSyncConsumer 消费 `crm.knowledge.sync.v1`，严格校验 eventId、版本、aggregateId 和 documentId，只将合法 WAITING 任务推进为 PENDING；默认 `KNOWLEDGE_SYNC_KAFKA_ENABLED=false`。
- 在事务中写入 `crm_inbox_event` 和任务状态，`tenant_id + consumer_group + event_id` 唯一约束保证重复事件不重复推进；事务成功后才提交 offset。
- 专用 ListenerContainerFactory 使用 StringDeserializer、手动确认、有限重试和 `crm.dlq.knowledge.sync.v1`；事件不含正文、密钥或业务写指令。
- 真实 Broker 全链路、停用 API 和管理页面尚未完成；未宣称真实 Dify 或完整阶段验收。

### 实际验证
- 消费者/执行/Worker 单测共 9 项通过，覆盖正常推进、重复 Inbox、documentId 不匹配和 offset 确认边界。
- 发布/消费/执行真实 MySQL 集成 4 项通过，新增同一事件消费两次只插入一条 Inbox 且任务只推进一次。

## [P2-KB-2-EXECUTION] 知识同步 Worker 与索引切换 — 2026-10-27

### 实现与边界
- 扩展 KnowledgeRepository/MyBatis 租约和状态 SQL，新增定时 KnowledgeSyncWorker 与 ExecutionService，区分新任务和 INDEXING 轮询认领；默认 `KNOWLEDGE_SYNC_WORKER_ENABLED=false`。
- KnowledgeWorkerClient 接入 Dataset sync/status/disable 内部接口，严格校验 requestId、provider、文档 ID、批次和状态；Dataset Key 仅在服务端解密传递，不传 App Key。
- Mock AVAILABLE 在同一 MySQL 事务中完成任务和文档激活；真实 INDEXING 保留旧 ACTIVE 修订，AVAILABLE 后才切换。支持有界退避、失败终态、来源哈希复核和稳定错误码，外部 Dify 调用不放入数据库事务。
- Kafka consumer/Inbox、停用 API 和管理页面尚未实现；未执行真实 Dify 同步或完整链路验收。

### 实际验证
- 执行与 Worker 单测 6 项通过，覆盖即时激活、旧版本保留、索引成功切换、临时错误重试、来源哈希变化及两类租约认领。
- 发布/执行真实 MySQL 集成 3 项通过，新增验证 `WAITING → PENDING → RUNNING → SUCCEEDED` 与文档 ACTIVE/revision/dify_document_id 切换。

## [UI-HOME] 管理员运营工作台重设计 — 2026-10-27

### 调整
- 管理员首页由“研发进度看板”改为运营工作台：移除四个“待接入”大卡片、分阶段提示及研发整改状态表，不再以占位指标填充首页，也不伪造客户或订单统计。
- 以真实 OPEN 任务总数和日期构成主视觉，提供待办与客户处理入口；工作区展示团队待办、截止日期、优先级和六个常用业务导航。管家与机构分支、现有接口及数据权限逻辑均未修改。
- 深蓝与青绿品牌色延续现有原型，采用非对称工作区、紧凑任务表格、真实空态和窄屏单列布局；清除相邻卡片默认上边距，确保团队待办与快捷入口顶边对齐。

### 验证
- 更新 `HomeView.test.ts`：验证真实待办、快捷导航、无研发进度或未接入占位卡、错误与空态；相关 2 个文件共 8 项测试通过，本次文件 ESLint、`npm run typecheck`、`npx vite build` 通过。
- 真实浏览器确认首页显示“1 项待办”、团队任务和快捷入口，原四个待接入卡片及研发整改表均已移除。

## [P0-1-FIX] 客户列表标签与来源渠道映射修复 — 2026-10-27

- 补齐 `CustomerListRowMap` 的来源、渠道、消费能力、生日、标签聚合及下次跟进时间映射，修复数据库有值但列表接口统一返回空的问题，不修改原始客户资料。
- `./mvnw -pl apps/crm-api -Dtest=CustomerApiIT test`：8 项通过，包含新增字段回归。重启后端后实际接口返回 `EXTERNAL_CONTENT/XHS`、`[HIGH_INTENT,VIP]` 和下次跟进时间。

## [P2-KB-2-PUBLICATION] CRM Dataset 配置与素材发布小步 — 2026-10-27

### 实现与边界
- V21 为 Dify 配置增加独立 Dataset ID、AES-GCM Dataset Key 和 `sync_enabled`，与 Chatflow App Key 分离；新增 `crm_knowledge_document`、`crm_knowledge_sync_job` 及默认仅 ADMIN 的 `ai:knowledge:read/publish` 权限。
- 新增 `POST /api/v1/ai/knowledge/documents/publications`：仅接受有数据范围的 APPROVED 修订和 CLEAN MATERIAL UTF-8 TXT/Markdown（最大 1 MiB），通过 Material/File Application Service 读取，拒绝越权、非审核版本、错误 MIME、非 UTF-8、超限和同步未启用。
- 发布仅创建 WAITING 映射/任务、最小 Outbox `knowledge.sync.requested.v1`（仅 jobId/documentId）和脱敏审计；同 revision/contentHash 不重复创建任务或 Outbox。新增显式 `crm.knowledge.sync.v1` topic，禁止落入订单 topic。
- Kafka consumer/Inbox、同步 Worker、索引切换、重试/停用和页面尚未实现；当前任务不代表已同步或已索引，真实 Dify 未调用。

### 实际验证
- 定向单测 34 项通过，覆盖独立 Dataset 密钥、发布/重复内容/版本冲突、素材权限和严格 UTF-8 文本边界。
- 真实 MySQL 集成 5 项通过，覆盖 V20/V21 配置密钥、发布幂等回放、重复内容、非 ADMIN/未审核拒绝及最小 Outbox；测试使用独立合成历史，不删除或覆盖已审核素材。
- OpenAPI 已同步配置和发布契约；完整同步链与全量验收未执行，P2-KB-2 保持进行中。

## [UI-COPY] 去除运营界面装饰性版本号 — 2026-10-27

### 调整
- 清理 12 个前端文件中的 15 处展示版本标记：合作渠道、机构资料变更审批、工作台、订单、预约、机构、商户一页纸、SKU、字典、企业微信、产品页，以及侧栏品牌区和首页提示标签。
- 页面标题不再附带 `· V1.7/V1.8/V1.9/V2.0`，侧栏与页脚不再显示 `CRM V2.0`；保留套餐规格、乐观锁、审批数据版本及 `/api/v1` 等功能性标识，未改接口、数据库或业务状态机。

### 验证
- 新增 `ui-version-labels.test.ts` 全局扫描页面标题、品牌区和提示标签，确认装饰性版本号零残留；工作台断言同步更新。
- 相关 13 个测试文件共 49 项测试通过，本次文件 ESLint、`npm run typecheck`、`npx vite build` 通过。
- 真实浏览器确认“合作渠道 / 达人”“机构资料变更审批”和侧栏“开发环境”均无版本号。

## [P0-1-UI] 有赞订单详情抽屉与 Raw 权限收敛 — 2026-10-27

### 实现与契约
- 抽屉按订单与同步状态、金额与支付核验、来源与时间、异常信息重新分区；桌面宽 820px、移动端 96vw，卡片响应式单列，长标识与 JSON 键值强制断行。
- 原始脱敏详情仅 `integration:youzan:manage` 可见且默认折叠；合法 JSON 缩进格式化，非法 JSON 保留原文，复制提供成功/失败反馈，空数据不提供复制入口。
- 后端订单列表和详情统一裁剪 `payloadMaskedJson`：普通 `order:read` 返回 null，manage 管理员可见；无数据库、状态机或事件契约变更。
- 前端测试改用 Vite Raw 导入，并在现有 `@types/node` 依赖下补齐 tsconfig Node 类型，使标准生产构建可执行。

### 实际验证
- 前端全量 82 个测试文件、349 项通过，有赞定向 19 项通过；`typecheck/lint/build` 全部通过，构建仅有既存大 chunk 提示。
- 后端有赞 Service/契约/脱敏 6 项单元测试通过。`YouzanOrderQueryIT` 因范围外 V21 Flyway 迁移 SQL 语法错误在 Spring 上下文启动前失败，未执行到有赞断言。

## [P2-KB-2-WORKER] Dify Dataset 适配小步 — 2026-10-27

### 实现与边界
- AI Worker 新增严格 Dataset 内部契约及 `POST /api/v1/knowledge/documents/sync|status|disable`，提供 Mock/Dify create/update-by-text、索引状态和文档停用适配；保留既有 Chatflow 契约，内部令牌与 requestId 统一处理。
- 真实请求限制 Dataset Key、默认 HTTPS、禁止重定向并受 `FEATURE_REAL_DIFY` 阻断；路径 ID 转义，非 2xx、超时、无效响应及跨 document 状态均转换为稳定脱敏错误。CRM 内部权限 metadata 不进入 Dify create/update 请求。
- 本步未实现 CRM 资料发布、配置扩展、Outbox/Kafka/Inbox、管理页面或真实资料同步，不宣称 P2-KB-2 完成。

### 实际验证
- `apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests -v`：49 项全部通过，其中新增 Dataset 12 项，覆盖创建、更新、索引完成/失败、停用、Mock、真实开关零 HTTP、严格输入和错误脱敏。
- 真实 Dify 版本兼容性、账号、网络、资料质量与 Q19/Q20 仍未验证；下一步为 CRM 发布任务与消息链。

## [P0-1-TASK-WORKFLOW] 任务动作工作流契约与文档同步 — 2026-10-27

### 实现与契约
- 对照实际 `TaskController`、任务应用服务、DTO 和 `V19__task_action_workflow.sql` 同步文档；V18 已由订单快照迁移占用。本次只修改 OpenAPI、CRUD/权限矩阵和本记录，未改业务代码、迁移或事件契约。
- OpenAPI 新增 `POST /api/v1/tasks/{id}/claim|reassign|return|complete` 与 `GET /api/v1/tasks/{id}/actions`。四个写动作均明确 `task:write`、CSRF、必传 `Idempotency-Key` 和 `version`；转派接收 `assigneeUserId/reason/version`，退回接收 `reason/version`，接手和完成接收 `version`，原因不得为空白。
- Task 响应补充 `originalAssigneeUserId/taskTeamId/claimedAt/completedAt`，`assigneeUserId` 明确可空；动作日志响应覆盖 `action/fromStatus/toStatus/fromAssigneeUserId/toAssigneeUserId/actorUserId/reason/createdAt`。
- 明确 `OPEN → PROCESSING` 接手、非终态转派、`PROCESSING → OPEN` 退回和 `PROCESSING → DONE` 完成；通用 PATCH 禁止 `status`，旧 `/assignee` 统一走 `REASSIGN` 且要求原因和幂等键。SELF 可查看同组未分配任务池，任务动作日志只追加，首次负责人不可变。

### 实际验证
- 最终复跑 `./mvnw -pl apps/crm-api -am verify`：BUILD SUCCESS，191 项单元测试、217 项 MySQL/Testcontainers 集成测试全部通过；其中任务工作流 `TaskApiIT` 3 项、`TaskActionWorkflowApiIT` 5 项通过。
- 最终复跑前端 `typecheck/lint/test/build` 全部通过；81 个测试文件、339 项测试全部成功，生产构建仅有既存大 chunk 提示。
- 本次使用仓库现有 `js-yaml` 实际执行 OpenAPI 解析、本地引用和任务契约断言：170 个 paths、1384 个 `$ref` 全部解析成功，38 个 operationId 无重复；四个动作的 CSRF/幂等参数、请求必填字段、响应字段、可空负责人、actions GET 和 UpdateTask 禁止 status 检查通过。

## [P2-KB-1] Dify 知识问答适配与独立配置 — 2026-10-27

### 实现与契约
- AI Worker 新增严格知识问答内部契约和 `POST /api/v1/knowledge/chat`，提供 Mock 与 Dify Chatflow blocking Adapter；规范化 answer/message/conversation/citations，拒绝额外字段和无效输出，上游错误不泄漏 Key 或原始报文。
- 真实 Dify 默认由 `FEATURE_REAL_DIFY=false` 阻断，内网 HTTP 需 `AI_DIFY_ALLOW_HTTP` 显式允许；不跟随重定向。Q19/Q20 未确认前不接真实部署、客户资料或 Dataset。
- CRM 新增 V20 `crm_dify_knowledge_config`、独立 Repository/Mapper/Service/Controller、`ai:knowledge:config` 权限和脱敏审计；支持 AES-GCM Key 存储、留空保留、乐观锁、保存/测试幂等及 CSRF。外部接口仅 `GET/PUT /api/v1/ai/knowledge/config` 和 `POST /api/v1/ai/knowledge/config/test`。
- Vue 新增 `/settings/ai/knowledge` 管理员配置页，支持 disabled/mock/dify、保存、保存并测试、连接状态和错误展示；不改变既有 OpenAI-compatible 证据抽取配置。OpenAPI、错误码、ADR、字段字典、AI 设计和 Q19/Q20 已同步，无新增 Kafka 事件。

### 实际验证
- AI Worker 全量 37 项通过，覆盖真实开关零 HTTP、blocking 请求、引用转换、内部认证、严格 Schema、脱敏失败和 URL 边界；真实外部 Dify 未调用。
- CRM 定向 Service 5 项和 MySQL/Testcontainers 集成 3 项通过，覆盖加密、审计、幂等、版本冲突、CSRF、租户隔离和 ADMIN/非 ADMIN 权限。最终全量后端 191 项单测通过；217 项集成测试中 3 项失败，分别为任务池 2 项和既有角色权限计数 1 项，均不在本次 Dify 模块。
- 前端知识配置定向 8 项通过；全量 81 个文件 336 项测试、typecheck、lint 通过。标准 build 仍被 `campaigns-view.test.ts`、`channel-api.test.ts`、`points-panel.test.ts` 各 1 个类型错误阻断。
- OpenAPI 使用仓库现有 `js-yaml` 解析通过，共 140 个 paths；`scripts/verify.sh` 因上述 3 项后端集成失败未通过，不宣称全量验收完成。
- 使用独立 MySQL 8.4、CRM API、AI Worker 和 Vue 完成真实 Mock E2E：默认 disabled，保存 Mock 后 CONNECTED，测试幂等回放版本保持 1，非 ADMIN 返回 403；浏览器保存并测试成功，版本按保存+测试推进到 3。临时进程和容器均已清理。

### 剩余边界
- 本阶段没有资料发布、Dataset 同步、公开知识问答、多轮会话或真实灰度；Mock/模拟 HTTP 不代表真实 Dify 联通、检索质量、成本或合规验收。Q19/Q20 和仓库其他模块全量失败未解除前，阶段保持进行中。

## [LOCAL-RECOVERY] 订单查询与本地运行稳定性修复 — 2026-10-27

### 修复
- 订单页签 500 的直接原因是运行中的旧 `TradingMapper.xml` 查询 `campaign_batch_name_snapshot`，而当前源码、订单领域记录及数据库统一使用 `batch_name_snapshot`；更新运行包并重启加载，未新增重复快照字段或修改订单事实。
- `V19__task_action_workflow.sql` 后续新增的 `trg_task_original_assignee_immutable` 尚未落入本地库，先按原文补齐并确认 3 个 V19 触发器齐全，再执行 Flyway 官方 `repair` 对齐校验和。repair 曾给可重复开发种子添加 `DELETE` 历史标记，未删除业务数据，随后已使用同时包含 `db/migration`、`db/dev` 的路径复核。
- 避免构建过程覆盖运行中的 JAR 导致 `NoClassDefFoundError`，校验 ZIP 完整性和嵌套 Logback 类后，以固定副本 `target/runtime/crm-api-local.jar` 启动；该副本仅用于本地运行，后续完整构建通过后仍需重新生成。
- 前端 Vite 的 `/api`、`/actuator` 代理目标由 `localhost` 固定为 `127.0.0.1:8080`，避免本机 `localhost` 解析差异引发代理 500。

### 验证
- 后端健康、真实登录、订单列表接口均为 200；订单响应 `code=OK`、`total=0`。前端代理健康与登录同为 200，跨域预检通过。
- 真实浏览器完成“登录 → 客户中心 → 沈梦琪 → 订单与退款”，页面显示“暂无订单或退款记录”，不再出现“系统内部错误”。前端 `typecheck` 与 `vite.config.ts` ESLint 通过，前后端服务保持运行。

## [LOCAL-RECOVERY] 本地开发服务恢复 — 2026-10-27

### 恢复
- `infra/compose/docker-compose.dev.yml` 仅在本地 MySQL 增加 `log-bin-trust-function-creators=1`，允许开发账号在 binlog 下执行审计触发器迁移；重建容器时保留既有数据卷，未修改生产配置。
- 对已部分执行的 `V17__p2_operations_governance.sql` 逐项核对 8 张表、7 个新增字段和 7 个触发器，手工补齐剩余触发器后才将失败记录恢复为成功；未跳过迁移、未重建业务表。
- 原可运行 JAR 缺少 `ExportJobMapper`、`ExportDataMapper` 及 XML；完整源码又被进行中的任务模块 6 个编译错误阻断。本次仅用稳定的导出域源码编译并补齐 JAR 的 `BOOT-INF/classes`，未新增临时 Bean、未修改任务模块源码。该补包只用于本地恢复，后续完整构建通过后必须重新生成 JAR，不能视为标准构建的替代。

### 验证
- 真实后端健康检查返回 200，`admin` 真实登录成功，客户中心及沈梦琪服务记录正常展示，新按钮样式同步确认；未使用模拟接口。
- 前端 `127.0.0.1:5173`、后端 `127.0.0.1:8080` 保持运行。完整 `mvn package/verify` 仍受进行中源码编译错误阻断，不宣称全量测试通过。

## [P0-1-UI] 服务记录工具栏按钮优化 — 2026-10-27

### 调整
- `FollowupPanel` 将工具栏布局收回组件自身，修复共享样式未加载时按钮上下堆叠的问题；查询与登记跟进在右上方同组排列，并适配窄屏。
- 查询改为白底描边次级按钮与搜索图标，登记跟进保留青绿色主按钮并增加加号图标，统一高度、圆角、间距和悬停态。查询、登记、权限判断与提交流程均未改变，无接口、数据库、状态机或契约变更。

### 验证
- 新增 `followup-panel.test.ts` 2 项测试，覆盖按钮层级、图标、查询调用、登记弹窗及无写权限隐藏；服务记录相关 3 个文件共 8 项测试通过，本次文件 ESLint、`npm run typecheck`、`npx vite build` 通过。
- 使用生产构建完成按钮渲染、查询点击、登记弹窗及取消验证，未提交业务记录；后续已恢复真实后端并完成真实登录、客户中心和服务记录验收，恢复过程见 `[LOCAL-RECOVERY]`。

## [P1-3-WEB-CONFIG] 大模型 Web 配置文档与契约同步 — 2026-10-27

### 实现与契约
- 同步已实现的 `GET/PUT /api/v1/ai/config`、`POST /api/v1/ai/config/test` 及前端 `/settings/ai`：三接口要求 `ai:config`，V15 默认仅 ADMIN；PUT 携带幂等键和 `version` 乐观锁，连接测试携带幂等键，写接口同时要求 CSRF。
- OpenAPI 新增配置请求/响应 Schema，覆盖 `disabled/mock/openai_compatible`、1–300 秒超时、连接状态与可空字段；API Key 仅可写入，省略、`null` 或空白保留原密钥，响应只返回 `apiKeyConfigured`。
- `43_AI智能管家设计.md` 明确 V15 `crm_ai_provider_config` 的 AES-256-GCM 密钥存储、审计/日志/响应不回显，及 Worker 动态 `provider_config`：无 Web 记录回退环境，有记录时 Web 开关与供应商参数优先。
- 连接测试明确为实际抽取调用，OpenAI 兼容供应商可能产生 Token 费用和限流；不创建 AI 任务或业务待办，HTTP 200 仍须检查 `CONNECTED/FAILED`。未宣称真实供应商鉴权、成本、质量、合规或生产联调通过。
- 本次仅修改 OpenAPI、设计文档和本记录，未改业务代码、数据库迁移或事件契约，无新增领域事件。

### 实际验证
- 使用仓库现有 `js-yaml` 执行 OpenAPI 解析与结构断言：138 个路径、959 个本地引用全部通过；新增 3 个操作的幂等/CSRF 参数、响应引用、枚举、必填/可空字段、密钥不回显及 1–300 秒约束通过。
- Java AI 配置、任务执行与 Worker HTTP 测试 16 项通过；Python Worker 全量 19 项通过；前端 AI/企微相关 6 个文件、28 项测试通过，前端 typecheck 通过。
- 真实浏览器以 ADMIN 打开 `/settings/ai`，选择“模拟”并开启执行，保存后连接状态为 CONNECTED；数据库 V15 记录为 mock/enabled=1，api_key_cipher 为空。`manager1` 直接请求配置接口返回 403。
- 本次只验证模拟供应商，未调用真实大模型，未验证真实供应商鉴权、费用、质量或生产合规。
- 全量验证仍受其他并行模块阻断：Java `mvn verify` 在 reporting 测试源码 5 个编译错误处停止；前端全量 284 项中 4 项失败，lint 有素材测试 4 个警告，生产 build 有 Campaign/Materials/Performance 等范围外类型错误。新版应用启动还遇到 exporting 缺少 ExportJobRepository 实现，页面验收使用临时测试 Bean，未修改仓库或将其作为生产方案。

## [INTEGRATION-SCHEDULE] 有赞与企微可编辑定时同步 — 2026-10-27

### 实现
- 有赞 `next_run_at` 支持 Web 修改下次执行时间，兼容旧策略并在认领后按间隔推进；保留历史导入、重叠窗口、店铺租约和手动同步。
- 企微新增 `crm_wecom_sync_policy` 与调度器：客户资料/会话归档独立开关、间隔和下次时间，原子认领避免多实例重复；一类失败不阻断另一类，旧固定 cron 默认禁用。
- 两类页面均可修改启用开关、间隔和下次执行时间，展示最近调度/错误；保存后无需重启。新增 V16 迁移、OpenAPI 与交接文档。

### 验证与边界
- 前端定时策略测试 2 项通过，覆盖两类页面实际修改下次时间并提交 ISO；前端 typecheck 通过，OpenAPI YAML 解析通过。
- 后端主代码 compile 通过；完整 Maven verify 受其他 Reporting 模块缺失类影响，不能标记为全量通过。真实有赞/企微供应商仍未联调。

## [P0-1-UI] 客户基础档案运营展示优化 — 2026-10-27

### 调整
- 客户详情“基础档案”按已确认示意稿改为双列“业务字段名 + 内容”，移除展开箭头、入库状态及字段标识、原表、目标字段、来源公式等研发映射信息；保留客户归属转交与历史功能。
- 来源、渠道、城市、客户状态使用现有字典显示中文，档案时间复用页面统一格式；无可用值显示“暂未维护”，不以其他字段补造值。接口、权限、数据库、状态机、OpenAPI 与事件契约均未变更。

### 验证
- 新增 `customer-profile.test.ts` 3 项展示与失败态测试；客户相关 5 个测试文件共 17 项通过，`npm run typecheck`、本次文件 ESLint、`npx vite build` 通过。
- 使用生产构建和本地真实后端完成“客户中心 → 客户详情 → 基础档案”操作，客户 `C0000001001` 的 23 项档案正常展示，中文业务值和脱敏手机号正确，无研发映射信息。
- 全量前端 `npm test` 为 278 项通过、4 项失败，失败在活动、素材、经营报表、风险页面；全量 lint 仍有素材测试 4 个警告，标准 `npm run build` 仍有其他模块 TypeScript 错误。后端 `mvn verify` 在 `WecomSyncPolicyApiIT`、`YouzanConnectionApiIT` 测试源码编译错误处停止，未进入集成测试；上述无关阻塞不计为本次通过项，也不宣称 `scripts/verify.sh` 全量通过。

## [P1-1R-HARDENING] 有赞安全与可靠性修复 — 2026-10-26

### 修复
- sync/reconcile 权限提升为 `integration:youzan:manage`，避免 BUSINESS 通过同步间接触发支付确认；Outbox 改用固定长度游标哈希，规避事件 ID 超长。
- 官方消息重复但未完成可重新调度，启动/周期恢复扫描覆盖重启丢失；第 100 页仍有数据时强制二分时间窗，禁止静默漏单。
- Token 失效单飞刷新并重试一次；店铺级调用间隔和有界 429 退避；店铺租约支持每页续期、异常释放和过期任务恢复。
- 扩充导购/销售员/外部身份/留言等脱敏；官方回调限制 1MB 和 64 层；系统操作员按当前租户选择 ACTIVE ADMIN。
- 新增 `V14__p1_1r_youzan_reliability.sql` 店铺租约字段及索引，未修改已执行的 V10 历史。

### 验证
- 有赞及权限定向测试 35 项通过，另补管理层缩窗续跑测试；MySQL/Testcontainers 有赞验收 7 项、店铺配置/权限/租约测试 3 项通过；前端有赞页面 18 项测试通过。
- 最终 clean 后顺序执行 `scripts/verify.sh` 全部通过：后端 170 项单元测试 + 90 项集成测试、前端 57 个文件 235 项测试及 typecheck/lint/build、AI Worker 16 项；OpenAPI YAML 解析通过。
- 真实店铺仍未联调，不宣称生产可用。

## [P1-1R] 有赞真实接入功能预建 — 2026-10-26

### 实现
- 新增店铺授权配置、AES-GCM 凭证/Token 存储、Token 缓存刷新、连接测试、`integration:youzan:manage` 权限及审计；真实功能默认关闭，未取得 Q11 凭证前不宣称生产联通。
- 新增官方自用型 Token、订单列表 4.0.2、订单详情默认 4.0.2 客户端与 Adapter；金额元字符串精确转分，客户使用 yz_open_id，SKU 使用 item_id:sku_id。
- 新增官方 `Event-Sign` 消息入口，验签落库后延迟查询订单详情；原始报文与内部标准事件分别脱敏/加密，官方测试消息不得进入订单处理。
- 新增全量时间窗分段、增量重叠窗口、同步策略、自动调度、任务记录及前端“店铺接入/同步策略/同步任务”页面；保留原 Mock 流程。
- Flyway `V10__p1_1r_youzan_real_connection.sql` 新增店铺/策略/任务表、原始官方报文列及真实时间窗游标；同步更新 OpenAPI 与交接文档。

### 验证
- `scripts/verify.sh` 全部通过：后端 158 项单元测试 + 66 项集成测试、前端 52 个文件 212 项测试、AI Worker 16 项；前端 typecheck/lint/build 同步通过。
- 有赞 MySQL/Testcontainers 覆盖配置加密与权限、并发去重、支付幂等、异常重试、原文脱敏和迁移；另以本地模拟官方 HTTP 验证“店铺配置→Token→订单列表→订单详情”1 条完整链路。
- OpenAPI YAML 解析通过；未执行真实有赞店铺联调，退款仍属 P1-4，不在本阶段完成范围。

## [P0-1] 认证授权与 RBAC（BACKEND_P0_1A） — 2026-10-23

### 新增
- 认证链：`POST /api/v1/auth/login`（用户名+密码，BCrypt 校验，失败统一 401「用户名或密码错误」防枚举）、`POST /api/v1/auth/logout`（吊销服务端会话+清 Cookie+审计）、`GET /api/v1/auth/me`（角色/权限实时读库，不缓存）。登录成功设置 `CRM_TOKEN`（HttpOnly JWT HS256，2h）+ `CRM_CSRF`（可读随机串）双 Cookie；令牌只存 SHA-256 哈希于 `sys_login_session`，服务端可吊销。
- CSRF 双提交防护：`CsrfValidationFilter` 对 `/api/` 下全部写方法强制 `X-CSRF-Token == CRM_CSRF`（登录除外），失败 403 `CSRF_TOKEN_INVALID`（已入 `contracts/errors/error-codes.md`）。
- RBAC：Flyway `V2__p0_1_auth_rbac.sql` 精确复制参考 schema 的 sys_org/sys_team/sys_user/sys_role/sys_permission/sys_user_role/sys_role_permission/sys_data_scope/crm_institution_user/crm_audit_log/crm_idempotency_record/crm_outbox_event/crm_inbox_event，另按 docs/12 建议新增 `sys_login_session`。开发种子 `db/dev/R__seed_auth_dev.sql`（8 角色 + 53 权限目录 + 矩阵映射 + 8 用户，密码统一 `Dev@12345` BCrypt，仅开发环境）。
- 用户管理 API（`user:read`/`user:write` 后端强制）：`GET/POST /api/v1/users`、`PATCH /users/{id}`（body.version 乐观锁 409）、`PUT /users/{id}/roles`、`POST /users/{id}/disable`（吊销会话、禁止自停用）；角色权限：`GET/POST /api/v1/roles`、`PUT /roles/{id}/permissions`、`GET /api/v1/permissions`。
- 字典管理 API（`dictionary:write`）：`GET /api/v1/dictionary-types`（含全部状态选项）、`POST /api/v1/dictionary-items`、`PATCH /dictionary-items/{id}`（乐观锁）、`POST /dictionary-items/{id}/disable`（ACTIVE→INACTIVE，options 不再返回、历史可查）；只读 `GET /api/v1/dictionaries/{code}/options` 改为 `dictionary:read`（全部登录角色具备）。
- 审计：`crm_audit_log` 落地登录/登出/登录失败、用户创建/修改/停用/角色分配、角色创建/权限变更、字典写操作（actor/action/target/before/after 脱敏快照/traceId= requestId/clientIp）；`GET /api/v1/audit-logs`（`audit:read`）。快照为白名单字段 Map，不含密码哈希。
- 数据范围模型：`CurrentUser`（认证过滤器每请求实时解析角色/权限/机构映射）+ `DataScopeService`（ALL/TEAM/SELF/INSTITUTION/NONE；INSTITUTION 用户由 crm_institution_user 收敛本机构），供后续领域模块调用。
- 敏感字段工具：`AesGcmCipher`（AES-256-GCM，随机 IV）+ `BlindIndex`（HMAC-SHA256 盲索引），开发密钥仅占位（`app.security.crypto.*`，生产走密钥管理）；本阶段表无敏感明文字段，无密文列落库。
- `TenantContext` 改为从认证主体解析租户；`PageResponse{items,total,page,size}` 统一分页结构（size≤100 强制）。

### 测试
- 后端新增：`AuthApiIT`（登录成功/失败同口径、me、logout 吊销、停用即时失效、防泄漏）、`CsrfProtectionIT`（缺失/伪造 403、登录豁免）、`UserAdminApiIT`（分页/筛选/创建 409/乐观锁 409/角色分配/停用/越权 403/未认证 401）、`RolePermissionApiIT`（53 权限目录、角色矩阵数量、权限变更去角色后同一令牌立即 403、恢复即恢复）、`DictionaryAdminApiIT`（CRUD/停用后 options 不可见但管理端历史可查）、`AuditLogApiIT`（写操作留痕+脱敏+授权边界）、`DataScopeServiceIT`（种子范围语义）、`AesGcmCipherTest`/`JwtServiceTest`（纯单元）。
- `DictionaryApiIT` 迁移到共享 Testcontainers 基座（`AbstractMysqlIT`），适配 dictionary:read 授权。

### 未做（按阶段边界）
- 客户/订单/机构等域逻辑（P0-2 起）；登录失败锁定与 MFA（P3 加固）；`sys_role_field_policy` 字段级策略表（随领域字段落地）。
- 前端登录页/路由守卫/菜单过滤由 FRONTEND_P0_1B 交付。

## [P0-0] 工程骨架 — 2026-10-23

### 新增
- Monorepo 骨架：`apps/crm-api`（Java 21 / Spring Boot 3.5.16 / MyBatis 3.0.5 / Flyway）、`apps/crm-web`（Vue3 + TS + Vite + Element Plus + Pinia + Router）、`apps/ai-worker`（FastAPI 骨架）。
- 平台基础设施：统一响应/错误信封（`ApiResponse`）、稳定错误码（`ErrorCode` + `contracts/errors/error-codes.md`）、`X-Request-Id`/ULID 追踪过滤器、Spring Security 默认拒绝（业务接口当前一律 401，认证 P0-1 接入）、CORS（默认仅 `http://localhost:5173`）、actuator 健康检查。
- 字典样表链路：`sys_dictionary_type` / `sys_dictionary_item`（Flyway `V1__p0_0_dictionaries.sql`）+ 开发种子（`db/dev/R__seed_dictionaries_dev.sql`，仅 local profile）+ 只读接口 `GET /api/v1/dictionaries/{dictCode}/options`。
- 前端骨架页面：工作台（后端健康状态）、字典演示页（401 优雅提示"认证将在 P0-1 接入"）、登录占位页（提交禁用，标注未对接）、404。
- ai-worker：`GET /health`、`POST /api/v1/insights/jobs` 返回 501 `NOT_IMPLEMENTED`（AI 能力 P1-3 实现）。
- 契约：`contracts/openapi/openapi.v1.yaml`（骨架）、`contracts/events/event-envelope.*`、`contracts/errors/error-codes.md`。
- 工程：`infra/compose/docker-compose.dev.yml`（MySQL 8.4 / Redis 7.4 / Kafka 4.1.2 KRaft / MinIO）、Maven Wrapper 3.9.16、GitHub Actions CI、`scripts/dev-up.sh` / `scripts/verify.sh`。

### 测试
- 后端：`GlobalExceptionHandlerTest`（统一错误/requestId 单测）、`DictionaryApiIT`（Testcontainers MySQL 8.4：迁移+种子+401+租户隔离）。
- 前端：http 拦截器错误规范化、视图渲染（Vitest）。
- ai-worker：pytest（health/501/requestId）。

### 未做（按阶段边界）
- 无认证/登录（P0-1）；无订单/权益/核销逻辑（P0-3/P0-4）；有赞/企微/AI 写回均未对接（Mock 或禁用）。
- `TenantContext` 暂返回默认租户 1，P0-1 必须从认证主体解析。

## [P0-1] 认证与权限 — 2026-10-23（前端部分，FRONTEND_P0_1）

### 新增（apps/crm-web）
- 认证链路：登录页真实对接 `POST /auth/login`（401 展示后端"用户名或密码错误"，不区分用户不存在/密码错误）；`POST /auth/logout`；刷新后 `GET /auth/me` 依据 HttpOnly Cookie 恢复会话。
- `src/api/http.ts`：写方法（POST/PUT/PATCH/DELETE，除 `/auth/login`）自动注入 `X-CSRF-Token`（值取 `CRM_CSRF` Cookie）；401 透传后端 message，缺失时回退统一"请重新登录"提示。
- `src/stores/auth.ts`：登录主体/权限码/角色判断（`hasPermission`/`hasRole`），权限数据仅来自 `/auth/me` 实时返回。
- 路由守卫（`src/router/guards.ts`）：未登录访问受保护路由 → `/login?redirect=...`；缺权限 → `/403`；登录页 public 且已登录时回首页；`src/router/menu.ts` 按权限过滤侧边菜单（菜单隐藏不替代后端鉴权）。
- 页面：用户管理（查询/分页/新建/编辑乐观锁/分配角色/停用）、角色权限（新建角色、按资源分组配置权限）、字典管理（类型列表、选项新建/编辑/停用，保留停用历史项）、审计日志（筛选/分页/展开脱敏快照）、403 页。写操作按钮按 `user:write`/`dictionary:write` 权限渲染。
- 工作台增加"当前登录"卡片（姓名/角色/权限数）。

### 调整（apps/crm-web）
- 移除 P0-0 字典演示页（`DictionaryDemoView.vue`）与对应菜单，由正式字典管理页替代；登录占位文案、"认证将在 P0-1 接入"提示文案全部下线。
- 用户状态枚举对齐后端 `ACTIVE/DISABLED`；列表用户含联表 `teamName`。

### 测试（apps/crm-web，Vitest 32 通过）
- 新增：`tests/csrf.test.ts`（写方法注入/GET 不注入/登录豁免/无 Cookie 不注入）、`tests/auth-store.test.ts`（登录成功与 401 失败/登出/会话恢复）、`tests/router-guard.test.ts`（未登录重定向/403/放行/已登录回首页）、`tests/menu.test.ts`（权限菜单过滤）。
- 更新：`tests/http.test.ts`（401 透传后端 message + 无 message 回退）、`tests/HomeView.test.ts`（文案随认证接入更新）。

## [P0-1b] 客户域、23 字段映射与参考风格前端 — 2026-10-24

### 实现
- 客户主档/身份/标签/积分账本/跟进/任务、服务端客户编号、可空手机号（AES-GCM 密文 + HMAC 盲索引 + 默认脱敏）、分页筛选与乐观锁；创建/积分写操作支持幂等键，积分只追加流水并同事务重算缓存。
- 查重扫描只生成候选；`SUGGESTED → REVIEWING → CONFIRMED / REJECTED` 人工审核后才事务迁移身份、标签、跟进、任务和积分流水，从客户软归档，不按昵称自动合并。
- 转交 `PENDING → APPROVED / EFFECTIVE / REJECTED`：申请不直接改归属，审批检查 baseVersion，生效时同事务更新归属并追加不可覆盖历史；跨团队仅 ADMIN。
- 独立 `crm_legacy_field_mapping` 维护原 23 字段的原名、原表、目标字段和来源公式；`legacy-profile` 返回真实值或 PENDING_IMPORT/PENDING_DOMAIN，不伪造订单统计。
- 可选管家名录仅含 ACTIVE 的 MANAGER/MANAGER_LEAD；管理员建档必须明确选择合格管家，主管限本组、管家限本人。
- 前端按用户指定的 `docs/08_原型与来源/V2.0_交互原型.html` 完成深青导航/青绿主色/双栏真实登录/宽抽屉客户档案；客户、合并、跟进、任务、归属页面全部接真实 API。桌面与移动仿真验收，移动端固定操作列，保留键盘导航与 reduced-motion。

### 数据与 API
- Flyway：`V3__p0_1_customer.sql`；兼容迁移 `V3.1__p0_1_customer_legacy_mapping.sql`。开发种子仅使用明确虚构数据。
- 新增 `/customers`、`/customer-owner-options`、`/customers/{id}/legacy-profile`、`/customer-merge-candidates(/scan)`、`/customer-merges`、`/customer-ownership-transfers`、`/followups`、`/tasks` 及对应详情/标签/积分/归属子路径；统一 tenantId + RBAC + Service 行级数据范围和脱敏审计。OpenAPI 已同步。
- 回滚脚本仅用于开发应急：`db/rollback/V3__p0_1_customer_rollback.sql`、`V3.1__p0_1_customer_legacy_mapping_rollback.sql`，未在验收库执行。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；18 项单元测试 + 72 项 MySQL 8.4 Testcontainers 集成测试，共 90 项，含 C01-C04、并发/幂等、23 字段、管家范围、合并积分和时区/CORS。
- 前端 `typecheck/lint/test/build` 全通过；15 个测试文件、79 项 Vitest 通过。
- 真实浏览器（Kimi Desktop + Vite + jar + dev MySQL）：管理员登录；无手机号建档；23 字段逐项展示；扫描出候选 → 开始审核 → 确认合并；主档案积分 3+5=8、两条流水保留、从档案 404；转交王丽→李强经申请/批准生效并保留历史；390px 移动仿真下操作可直接触达。移动仿真不冒充实机、多浏览器测试。

### 阶段边界
- 原 legacy 数据导入、订单派生统计分别留待数据迁移与 P0-3；企微/有赞身份接入留待 P1。未调用任何真实第三方接口，未发生生产发布。

## [P0-2] 产品、套餐多规格与 SKU 版本快照 — 2026-10-24

### 实现
- `V4__p0_2_product_catalog.sql`：机构、子项目、套餐、版本、版本子项目、SKU、SKU 机构、活动/批次/渠道基础表；`R__seed_product_dev.sql` 仅含明确虚构配置。
- 项目/套餐 CRUD、版本草稿与项目配置、`DRAFT → ACTIVE → ARCHIVED`、SKU 创建/编辑/上架/停用、按 SKU 与机构查询。编码唯一、金额整数分、乐观锁、审计快照与失效状态校验。
- 3/6/10 次各自独立 SKU/版本快照；已激活版本禁止覆盖，未上架 SKU 在 ACTIVE 版本下仅可补配机构，规格与价格仍冻结。
- Q01：选 N 项在业务未确认前只能保存模板，`entitlementIssuable=false`，上架返回 422 `PENDING_BUSINESS_RULE`；文本不能冒充业务确认。Q02：多项目分摊未配置时标记并阻断后续结算，不阻断目录维护。
- 机构操作员获最小 `product:read`；SQL 层按机构过滤 SKU/快照/机构选项，敏感银行与税号不出参。
- 前端按参考原型完成套餐与版本、标准子项目、SKU 管理页面；真实版本快照抽屉、动态项目配置、机构多选、Q01/Q02 提示与权限按钮。

### API 与数据
- 新增 `/projects`、`/packages`、`/packages/{id}/versions`、`/package-versions/{id}(/snapshot/projects/activate/archive)`、`/skus(/activate/disable/institutions)`、`/institutions`。OpenAPI 路径与模型已同步并通过 YAML/端点/schema 校验。
- 开发应急回滚：`apps/crm-api/src/main/resources/db/rollback/V4__p0_2_product_catalog_rollback.sql`，未在验收库执行。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；21 项单元测试 + 78 项 MySQL 8.4 Testcontainers 集成测试，共 99 项，含 P01/P02、Q01/Q02、版本冻结、机构隔离、重复编码与乐观锁。
- 前端 `typecheck/lint/test/build` 全通过；17 个测试文件、86 项 Vitest 通过。
- 真实浏览器与 HTTP：版本快照展示 Q01/Q02；选 3 项 SKU 上架被 HTTP 422 阻断且保持停用；新建 `SKU-E2E-003`（固定 3 次、181100 分、北京机构）并上架成功；机构账号仅见 `SKU-WG-001-03`、`SKU-E2E-003`、`SKU-MS-001-S3`，跨机构快照 404。

### 阶段边界
- 订单成交快照、可信支付确认、权益发放和 Outbox 属于 P0-3；Q01/Q02 尚未由业务签定，未伪造确认结论或第三方接入。

## [P0-3] 订单、可信支付、权益账本与可靠事件 — 2026-10-24

### 实现
- 订单 `DRAFT → PENDING_PAYMENT → PAID`，未支付可取消并追加历史；成交时冻结 SKU、套餐版本、项目、次数、机构及有效期快照。已支付事实不通过编辑覆盖，订单统计、首单时间、客户合并迁移接入真实交易域。
- 仅授权角色可通过 `BANK_TRANSFER/OFFLINE` 和人工凭证引用确认支付；`verificationMethod=MANUAL_REVIEW`，不伪造支付网关。支付、PAID 状态、项目权益、GRANT 流水、状态历史、首单时间、审计和 Outbox 处于同一 MySQL 事务。
- 支付 `Idempotency-Key` 必传；锁内复核客户范围、请求哈希及原支付事实。同键同请求不重复入账，同键不同请求或交易号跨订单返回冲突；100 路并发只形成一笔支付和一套权益。开发 `finance1` 仅有订单读/支付确认权限及明确客户范围，不能建单；管家不能确认支付。
- 权益以项目批次和只追加账本维护余额，行锁防止负数及重复扣减；同键核销重放验证完整业务请求。当前没有管家或通用正式核销 HTTP 接口，机构正式核销留待 P0-4。
- Q01 未确认仍禁止下单。Q02 未确认时，包括单行订单均保存 `PENDING_Q02` 和 0 待分摊金额；已明确权重时按最大余数法保存项目级整数分快照，不推算机构结算。
- `V5__p0_3_order_payment_entitlement.sql` 建立订单、支付、权益、账本和状态历史；追加 `V5_1__p0_3_outbox_relay_reliability.sql` 扩展事件 ID 与 Relay 字段，均提供开发应急回滚脚本，未在验收库执行回滚。
- Outbox 使用真实租户、数据库原子抢占、崩溃超时回收及带抖动的有界指数退避，不因重试次数到上限静默丢弃。显式声明订单/权益 Kafka topic，`acks=all`、幂等 producer；发送成功但标记失败仍遵循至少一次投递边界。

### 前端与契约
- 新增订单与支付、客户权益页面及宽抽屉：真实分页筛选、远程客户/SKU 搜索、草稿创建/提交/取消、人工支付确认、快照、支付流水、状态历史、权威余额与 GRANT 流水。
- 延续参考原型的深青导航、青绿主色和浅色卡片；390px 移动视口保留操作入口，交易摘要使用清晰的标签—值布局，宽表格可滚动。
- OpenAPI 补齐 7 个交易/权益路径，严格描述权限、幂等、人工凭证及错误响应；事件契约只覆盖真实发布事件。YAML 结构、219 个本地引用和 DTO/record 契约校验通过。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；25 项单元测试 + 95 项 MySQL 8.4 Testcontainers 集成测试，共 120 项。覆盖 O01/O02/E01/E02、同键不同请求、越权、晚阶段失败全回滚、Q02、Relay 抢占与故障恢复。
- 前端 `typecheck/lint/test/build` 全部通过；24 个测试文件、110 项 Vitest 通过。构建有现有大 chunk 提示，不影响构建结果。
- 开发 MySQL 成功执行 V5、V5.1；真实 Kafka topic 创建成功。本地联调订单 `J000000000001` 经管理员建单/提交、财务 `finance1` 人工确认：实付 181100 分、基础水光 GRANT +3、权威余额 3、支付审计与 Outbox SENT 一致；实际消费到 `order.paid.v1:1`，订单/支付 ID、金额和 `MANUAL_REVIEW` 相符。
- 同请求真实 HTTP 重放返回 200，同键不同凭证返回 409，支付/权益/GRANT/Outbox/审计仍各一份。财务无建单入口且访问权益页 403；归属管家仅见自己的订单/权益，无支付确认或正式核销入口。390px 移动仿真下订单详情、权益流水及关闭操作可用。

### 阶段边界
- 上述交易号、客户及凭证均为明确标注的本地联调数据，不代表真实银行到账或生产支付接入。Q01/Q02 尚未由业务签定。
- 本阶段完成 Outbox → Kafka 发布侧验收；尚无业务 Kafka consumer，未宣称业务 Inbox 去重或消费端已验收。机构履约核销、退款、结算分别进入 P0-4、P1-4。

## [P0-4] 预约、机构核销与追加冲正 — 2026-10-24

### 实现
- `V6__p0_4_appointment_redemption_reversal.sql` 新建预约、逐项目预约项、预约历史、核销批次/明细、核销历史、冲正申请及审核历史，提供开发应急回滚脚本，未在验收库执行回滚。
- 预约按 `PENDING_INSTITUTION → CONFIRMED → CHECKED_IN → SERVICE_DONE → REDEEMED` 流转，支持拒绝、取消与 NO_SHOW；创建计划不扣次数。多项目必须分别选择真实权益批次和计划次数，不能用套餐总数代替。
- 机构 FRONTDESK 可确认接诊/到院/服务，只有本机构 OPERATOR 可正式 POSTED。服务端每次检查实时机构映射与角色；管理员、管家及 FRONTDESK 不能直接扣减。`/auth/me` 仅返回当前用户自己的 `institutionRoles`，前端按角色控制入口。
- 正式核销在同一 MySQL 事务中写批次/项目行、REDEEM 负流水、余额快照、预约/订单状态、审计和 Outbox。按 ID 顺序锁权益，校验已支付快照、机构、项目、客户、有效期、实际服务时间与剩余次数；未到院、越权或余额不足不写入。部分计划未完成可继续履约，订单按净核销次数在 PAID/PARTIALLY_REDEEMED/FULFILLED 间变化，支付事实不变。
- 通用管家直扣入口已移除。核销幂等键及请求哈希在锁内复核，同键同请求返回同一 POSTED，不同请求冲突；失败回滚整个批次。机构选项使用 `appointmentId` 专用模式，仅返回本预约、本机构允许的批次，无需也不授予全局客户读取权限，跨机构 404。
- 冲正采用独立申请 `REQUESTED → APPROVED/REJECTED`，申请人不能审核本人申请。审批前核对原 REDEEM 来源与次数；生效时追加 CORRECTION_IN 与审核历史，不更新原 POSTED 或原负流水，恢复不得超过发放数，过期权益不悄悄恢复可用。申请阶段和空补偿响应不宣称权益已恢复。
- 金额保持 `PENDING_VERIFICATION`；Q02/Q04-Q06 未确认时不计算或展示已结算金额。
- 事件发布覆盖 appointment.created/status.changed、redemption.posted、redemption.reversal.requested/approved/rejected 及 entitlement.redeemed。显式声明 order/entitlement/appointment/redemption 四个 topic，approved 负载含真实补偿流水；事件 ID 契约与数据库统一为 192 字符。

### 前端与契约
- 新增预约与机构履约、核销与冲正页面：逐项目预约、状态操作、OPERATOR 二次确认核销、原记录只读、冲正申请/异人审核、真实补偿详情及机构/客户/项目筛选。
- 写操作携带 Idempotency-Key 与乐观锁，失败重试固定键；保持参考原型视觉、远程搜索、宽抽屉和 390px 移动布局。
- OpenAPI 补齐 17 个履约路径及两种预约选项模式；事件契约覆盖 8 类真实事件，无虚构 consumer 或外部供应商接口。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；28 项单元测试 + 106 项 MySQL 8.4 Testcontainers 集成测试，共 134 项。覆盖 V01-V05、多项目、同键/异键冲突、并发余额、订单状态链、机构选项隔离、晚失败回滚与冲正幂等。
- 前端 `typecheck/lint/test/build` 全部通过；31 个测试文件、135 项 Vitest 通过。测试套件关闭自动 Relay 以避免共享库的后台竞态，手动故障恢复测试仍保留。
- 开发 MySQL 成功执行 V6。真实浏览器以联调客户和虚构机构完成 `A000000000001`：管家创建 → 机构前台接诊/到院/服务 → `operator1` 核销 1 次；权益 3→2、订单 PAID→PARTIALLY_REDEEMED，原 POSTED 与 REDEEM -1 保留。
- `operator1` 申请冲正、财务 `finance1` 异人批准：申请阶段余额不变；审批产生真实流水 #3 CORRECTION_IN +1，权益恢复 3、订单恢复 PAID；原 POSTED version=0 不变。申请/审核/补偿历史及 Kafka 的 appointment.created、redemption.posted、entitlement.redeemed、reversal.requested/approved 均与 Outbox 精确匹配。
- 浏览器发现并修复机构选项 403：OPERATOR 现只能读取授权预约的批次，operator2 跨机构 404。390px 移动仿真下补偿详情、申请/审核信息、流水及关闭操作可用。

### 阶段边界
- 联调预约、实际服务时间及凭证均为明确标注的本地测试数据，不代表真实到院、医疗服务或结算。Q01/Q02/Q04-Q06 未替业务确认。
- 本阶段验证发布侧 Outbox → Kafka；尚无业务 Kafka consumer/Inbox 端到端验收。机构档案审批进入 P0-5，真实第三方接口、退款及结算留待后续阶段。

## [P0-5] 机构档案、合同资质与分级审批 — 2026-10-24

### 实现
- `V7__p0_5_institution_profile_documents_change.sql` 新增合同、资质、敏感变更申请与审批步骤；`V7_1__p0_5_private_file_storage.sql` 新增私有文件元数据与三类文件外键。均提供开发应急回滚脚本，未在验收库执行回滚。
- 完整机构档案分页/详情/创建/普通字段乐观锁更新；新机构为 PENDING，合作激活必须 MARKET→FINANCE。普通 PATCH 仅允许白名单字段，银行/税号/佣金/合同等只能走独立审批。机构账号仅可提交本院变更，其他机构404；管家一页纸不含财务字段，银行、税号和敏感审计只返回掩码，不返回明文或密文。
- 敏感变更保存原值/新值密文及脱敏摘要、baseVersion、原因和证据引用。银行/税号走 FINANCE，佣金/保证金/结算/合同走 BOTH 且市场先于财务；两位审核人必须独立且不同于申请人。错误角色/越序403，终结申请和版本冲突409。baseVersion 变化转 CONFLICT_REVALIDATE，复核前禁止覆盖较新值；REJECTED 不能生效，自审拒绝有独立审计。
- 合同只新增版本，激活经双审；旧版本保留文件并转 SUPERSEDED。资质创建/审核均要求对应类别的 CLEAN 文件，审批完成前不改变正式值。变更与文件操作保留审计、幂等键、租户和机构范围。
- 文件使用官方 `io.minio:minio:9.0.3` 及实际类所在 `okhttp-jvm:5.3.2`：私有桶、唯一 objectKey、短期 PUT/GET 预签名、真实 ETag/大小/MIME/SHA-256 校验。ClamAV 通过 INSTREAM 扫描，只有 OK 才 CLEAN；FOUND/失败不可引用。下载重新校验范围与分类并写审计，不把签名 URL 当永久公开地址。
- `infra/compose` 增加 ClamAV，Apple Silicon 显式使用 amd64 emulation，健康检查使用 `clamdscan --ping`。未配置的文件服务默认禁用，不把本地路径或模拟 ID 当上传成功。机构事件通过 Outbox 发布到 `crm.institution.events.v1`；无虚构 file.cleaned 事件或业务 consumer。

### 前端与契约
- 新增机构主档与审批、普通资料更新、敏感变更、双审/复核、合同版本与资质页面，以及真实 SHA-256、预签名 PUT、完成校验、扫描状态和短期下载。未 CLEAN 不传 fileId，不伪造审批或上传结果。
- 金额元↔整数分、费率百分比↔Decimal 精确转换，保留参考原型视觉、宽抽屉和390px移动布局；合同表格横向滚动可查看金额、状态和文件操作。
- OpenAPI 新增18个机构/文件路径，15类真实事件契约与 relay 对齐；95个路径、568个本地引用通过校验，事件ID统一192字符。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；35项单元测试 + 132项 MySQL 8.4 Testcontainers 集成测试，共167项，含 H01-H04、密文/掩码、审批顺序、自审、并发版本、跨机构范围、合同/资质与文件异常分支。
- 前端 `typecheck/lint/test/build` 全部通过；42个测试文件、161项 Vitest 通过。
- 开发库成功执行 V7/V7.1。真实 MinIO 上传合成 PDF 后完成对象校验、ClamAV CLEAN 和签名下载；标准 EICAR 内容流被真实标为 BLOCKED/CLAMAV_FOUND 且下载409。预签名 PUT 204 且浏览器可读取 ETag。
- 浏览器以 operator1 上传凭证 #5 CLEAN，申请银行账号 ****8866→****1234，正式版本0不变；市场越权审批403，财务批准后生效且版本1。响应无新账号明文，审批事件 `institution.change.reviewed.v1:1:1` 与真实 Kafka/Outbox 一致。
- 真实 API 验证佣金双审、越序403、baseVersion 冲突保留较新介绍、重新复核、禁止自审及 `INSTITUTION_SELF_REVIEW_DENIED` 审计。新机构双审后 ACTIVE。
- 合同 V1 激活后成为历史 SUPERSEDED，V2 成为 ACTIVE，文件引用不变；资质经真实文件上传和市场审核通过。390px 移动仿真下合同状态、金额、历史只读与关闭操作可用。

### 阶段边界
- 所有机构、银行、合同、资质及凭证均为合成联调数据，不是实际金融或医疗文件。Q01/Q02/Q04-Q06/Q08/Q18 未替业务确认，未启用真实第三方接入或生产发布。
- 当前完成私有文件与发布侧事件验收；桶版本策略、文件保留期限、水印、密钥轮换、生产病毒库运维和业务 Kafka consumer/Inbox 留待 P3 加固。

## [P1-1] 有赞 Mock Adapter、订单同步与补偿 — 2026-10-25

### 实现
- `V8__p1_1_youzan_order_integration.sql` 新增验签后原始事件、外部订单唯一映射、SKU 映射、人工客户链接、同步异常和补偿游标，含租户/业务唯一键、索引、注释与开发应急回滚；本地 `R__seed_youzan_dev.sql` 仅使用虚构店铺、客户与 SKU。
- `YouzanAdapter` 隔离供应商模型；local 使用 `MockYouzanAdapter`，其他情况保持禁用。开发 Mock HMAC 使用原始请求体、300 秒时间窗和常量时间比较，伪造/过期签名401且不落 Raw。Q11 未确认，`FEATURE_REAL_YOUZAN=false`，没有虚构官方 endpoint、字段或授权。
- 验签后先保存递归脱敏 JSON 与 AES-GCM 加密原文，再异步处理；只有 SKU/人工客户映射完整、订单快照有效、支付金额/时间/交易号可信才创建订单并确认支付。专用内部 `createExternalOrder` 和 `confirmProviderCallback` 复用既有事务，公开建单/人工支付接口不能伪造有赞来源。
- 原始事件、外部订单、支付交易号及 `payment.provider_callback` 幂等共同防止 webhook、补偿和并发重放重复建单、入账或发权益；旧 WAIT_PAY 不降低 PAID。SKU 未映射、客户未确认、金额不一致及技术异常均不伪造成功，保留可重试异常；人工映射修复后才解决。
- 订单查询按外部订单返回最新 Raw，包括未生成内部订单的失败记录，提供真实处理状态、外部更新时间/金额和脱敏详情；无内部订单/支付时相应字段为空。已映射订单继续校验客户数据范围，未链接 Raw 不向受限范围暴露。
- Outbox 写入支持唯一键冲突时保留既有事件，修复并发补偿产生 500；`youzan` 聚合路由至 `crm.youzan.events.v1`，支付仍走 `crm.order.events.v1`，没有虚构业务 consumer/Inbox。

### 前端与契约
- 新增有赞订单同步中心：Raw/内部订单与金额、脱敏详情、SKU 映射、带理由的人工客户链接、异常队列与补偿重试、Mock 单页同步及补偿对账。明确展示 MOCK、Q11 未确认、真实 API 禁用，不把回调当作真实资金到账。
- OpenAPI 覆盖有赞10个 path item、12个 operation；全文件105个路径、659个本地引用通过结构/Java record 校验。事件契约覆盖真实有赞同步事件及 `PROVIDER_CALLBACK`；新增 `42_有赞字段映射与交接.md`，区分 Mock 字段与 Q11 待确认材料。

### 实际验证
- `./mvnw -B -pl apps/crm-api -am verify`：BUILD SUCCESS；49项单元测试 + 144项 MySQL 8.4 Testcontainers 集成测试，共193项。含 O03/O04、6路并发 webhook/pull、签名/脱敏/金额、客户/租户/数据范围、晚 Outbox 失败回滚及最新 Raw 查询。
- 前端 `typecheck/lint/test/build` 全部通过；47个测试文件、179项 Vitest 通过，构建仅有既存大 chunk 提示。
- 开发库成功执行 V8。真实 HTTP 无效签名401且无 Raw；有效 `YZ-MOCK-PAID-001` 回调后订单 `J000000000002` 实付362200分、1笔支付、1条 GRANT 共6次，重复回调/补偿不增加事实。脱敏响应不含测试手机号或密文。
- 浏览器补偿拉取 `YZ-MOCK-MISSING-001` 时未建单/支付，显示 SKU 映射失败及脱敏原文；页面新增 `YZ-SKU-MISSING→301` 后重试，异常 RESOLVED，订单 `J000000000003` 实付181100分、GRANT 3次。重复补偿 duplicates=1、issues=0，两笔外部订单均只有一套事实。
- 实际 Kafka 消费匹配 `order.paid.v1:2/3` 及5条有赞同步事件，金额、订单ID和 `PROVIDER_CALLBACK` 与 MySQL/Outbox 一致。桌面与390px移动仿真下同步、异常、映射、Raw详情及关闭入口可用；移动仿真不冒充实机、多浏览器测试。

### 阶段边界
- 所有店铺、订单、支付与客户均为本地 Mock/虚构联调数据，未调用真实有赞，未证明真实授权或资金到账。Q11 的店铺类型、应用授权、官方字段、签名、分页限速和回调安全规则未确认。
- 本阶段验收 Outbox → Kafka 发布侧；业务 Kafka consumer/Inbox、自动补偿调度、生产密钥轮换及真实供应商故障联调留待 P3。退款与机构结算进入 P1-4。

## [原型一致性整改] 三类角色逐页验收与中文展示修正 — 2026-10-26

### 实现
- 以 `docs/08_原型与来源/V2.0_交互原型.html` 文件后部最终 override 为基准，逐页核对管理员页面，保留项目最初登录页，不采用原型登录页。
- 机构中心、机构详情和商户一页纸统一显示中文城市、业务类型、机构性质、商户等级与结算方式；机构审批的申请值、审核步骤、当前步骤、审计时间和续签合同状态改为可读中文，不再向业务页面直接输出原始 JSON。
- 有赞订单类型、人工链接方式与证据等级中文化；企微监管和归档元数据使用真实客户姓名及中文消息类型。管家任务优先级和机构角色名称不再显示英文编码。
- SKU 分类从已激活套餐版本的真实子项目快照派生；修复开发库项目分类中文乱码。真实销量、质量、AI、业绩、未读等未接入指标继续显示待接入/待评估，不填充演示数字。
- 未新增或修改后端 API、数据库迁移、权限规则和业务状态机；本次收尾改动为前端展示与项目记录。

### 实际验证
- 使用内置浏览器实际登录 `admin`、`manager1`、`inst1`，检查管理员各已实现页面、管家工作台和机构工作台；机构列表已显示「北京 / 医美 / 民营机构 / A类 / 月结」，续签显示「历史版本」，有赞显示「普通订单」。
- iPhone 14 Pro 尺寸仿真抽查机构工作台与机构资料页：移动导航、统计卡、筛选区和表格滚动可用；不代表已完成实机、多浏览器或完整设备矩阵测试。
- `npm --prefix apps/crm-web run typecheck`、`lint`、`test`、`build` 均实际执行成功；Vitest 为 50 个文件、207 项测试全部通过。生产构建仅有既存大 chunk 提示。

### 阶段边界
- 本次不将 P1-3、P1-4、P2、P3 标记完成；真实有赞/企微/AI 仍受原开关和待确认事项约束。
- 技术管理页保留字典保存值、角色编码、审计动作、Trace ID 等业务排障所需原始标识。


## [P1-2] 企业微信官方客户资料同步、回调与对账 — 2026-10-26

### 实现
- 新增 `V12__p1_2_wecom_contact_sync.sql` 与开发应急回滚：企微成员/CRM员工映射、客户官方资料与跟进状态、FULL/RECONCILE/CALLBACK 运行历史、验签回调指纹幂等与重试状态。V10/V11 已由其他并行模块使用，本次不占号或修改其迁移。
- 按企业微信官方文档实现 `gettoken`、`externalcontact/batch/get_by_user`、`externalcontact/get`：token 缓存/失效刷新、100成员/100条分页和单客户跟进人 cursor；使用已有 OkHttp，真实凭证仅由环境变量注入。
- 客户 FULL 只更新官方资料并保留人工 customer_id；RECONCILE 仅在全量成功后失效本轮缺失关系。回调先验签/AES/receiveid/安全XML校验，再以 SHA-256 指纹落库并立即 success；增改重新拉详情，删除只关闭跟进关系，每日对账补偿 API 操作无回调与事件丢失。
- 新增内部客户同步/运行查询 API 和公开官方回调入口；前端展示客户资料、来源、跟进状态、全量/对账与独立运行状态。客户资料成功不冒充会话存档授权或 DATA_GAP 已消除。
- 更新 `41_企业微信官方接入.md`、官方字段映射、Q10 阻断范围与 OpenAPI。Q10 未确认前真实会话存档继续关闭，未实现或宣称 Finance SDK/真实聊天/生产企微联调。

### 实际验证
- 企微官方 HTTP 与回调加解密单元测试 3 项通过；MySQL 8.4 Testcontainers 下本次全量/回调/空映射拒绝对账 3 项与既有归档回归 7 项，共 10 项集成测试通过。定向命令为 `./mvnw -pl apps/crm-api -am -Dtest=WecomCallbackCryptoServiceTest,OfficialWecomContactClientTest -Dit.test=WecomContactSyncApiIT,WecomAcceptanceIT -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false verify`，BUILD SUCCESS。
- 原始仓库 `./mvnw -pl apps/crm-api -am verify` 已执行：本次及既有企微测试通过；全量最终为 158 项集成测试中 2 项失败，失败均在 `RolePermissionApiIT`，为其他并行权限目录从55增至57后的既有断言，不是企微用例。
- 前端 `typecheck` 和 `lint` 通过；本次页面/API完成后 Vitest 50个文件、208项曾全部通过。并行 AI 页面完成后已更新过期断言和类型，最终全量为52个文件、212项全部通过，`typecheck/lint/build` 均成功。
- 未调用真实企业微信账号，未执行真实回调、公网或生产数据验收。真实上线仍需企业凭证、权限、员工映射、公网HTTPS回调及管理员联调。

## [P1-3] AI 证据抽取、异步任务与人工确认 — 2026-10-26

### 实现
- 新增 `V11__p1_3_ai_insight_jobs.sql` 及开发应急回滚：`crm_ai_job` 保存幂等、异步占用、重试、人工确认和版本；`crm_ai_insight` 只保存结构化建议与证据 ID，不保存聊天全文或思维链，数据库约束固定 `UNVERIFIED` 和禁止资金写入。`crm_task` 增加来源唯一键，防止重复生成 AI 待办。
- Java 新增 `com.whale.crm.ai`：实时数据范围/同意/成员复核、MyBatis 持久化任务、原子占用与崩溃超时回收、有界退避、OkHttp worker client、审计、范围化质量统计和 5 个 API。新增 `ai:write` 权限，管家与主管可创建和人工确认，机构账号默认拒绝。
- Python FastAPI 将 501 占位替换为无状态 `/api/v1/insights/extract`：内部令牌、输入限制、严格 Pydantic Schema、不可信聊天提示词、证据/项目/机构白名单、Mock 规则抽取器和 OpenAI-compatible 适配器。默认 `AI_PROVIDER=disabled`；真实供应商必须显式配置，不宣称模型效果或供应商合规已验收。
- 创建和执行任务时都重新校验权限与企微可读范围；权限失效、同意撤回或成员不再覆盖会在发送内容前终止。AI 不能调用支付、退款、合同、预约或正式核销写路径。
- 人工确认只允许编辑并生成一条普通 `FOLLOWUP` 待办，来源固定 `AI/AI_JOB:{id}`；同请求幂等回放不重复，终态或不同请求 409。回复草稿只能人工复制，不能自动发送。
- 前端三栏会话页接入真实创建、轮询、证据/项目中文名称、风险建议、确认/驳回与待办链接；主管监管接入真实质量统计。OpenAPI 增至 124 个路径并补齐 AI Schema 和错误码。

### 实际验证
- `apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests`：16 项通过，覆盖 AI01、AI02、提示注入、伪造证据、严格 Schema、内部认证、供应商禁用与 OpenAI-compatible 适配器。
- `./mvnw -pl apps/crm-api -am verify`：BUILD SUCCESS；65 项单元测试 + 158 项 MySQL 8.4 Testcontainers 集成测试，共 223 项。包含 AI 服务安全测试和真实 V11/Mapper/占用/洞察/质量统计测试。
- 前端 `typecheck/lint/test/build` 全部通过；52 个测试文件、212 项 Vitest 通过，构建仅有既存大 chunk 提示。OpenAPI YAML 解析与 AI 路径检查通过。
- 真实浏览器以 `admin` 创建 AI 任务并异步进入 PROPOSED，展示预约意向、基础水光、消息 #1 和 `mock-rules-v1`；人工确认后仅生成任务 #9003。SQL 复核 `fact_status=UNVERIFIED`、`allow_financial_write=0`、AI 来源 FOLLOWUP 数量 1、禁用事件数量 0。
- 运行中 Python 服务直接验证恶意指令被当作不可信聊天：只输出 APPOINTMENT_INTENT/UNVERIFIED，不允许资金写入。`manager1` 具有创建入口，`inst1` 直接访问企微/AI 为 403。iPhone 14 Pro 仿真下会话和 AI 面板单列、按钮可用；不冒充实机或多浏览器测试。

### 阶段边界
- 本次真实链路使用本地 Mock 抽取器；未调用外部真实大模型，未验证 precision/recall/F1、成本、延迟、供应商数据保留/跨境政策或生产灰度。启用真实供应商前必须另行审批和评测。
- 未新增业务 Kafka consumer，未发布支付、退款、核销、合同或银行账号事件；企微官方接入仍受 Q10，模型自动业务写回继续禁用。


## [界面中文化] 全系统业务标签与状态文案检查 — 2026-10-26

### 实现
- 按应用外壳、客户、产品交易、预约核销、机构合同、企业微信、有赞、退款和结算逐页检查可见文案，清理普通英文单词、英文枚举直出及不友好的技术字段。
- `Mock` 改为“模拟”，`POSTED` 改为“正式核销”，`CLEAN` 改为“扫描通过”，`baseVersion` 改为“基准版本”，企微游标、归档序号、每页条数等均使用中文；权限编码、API 字段和提交枚举原值不变。
- 状态、类型、来源、支付与退款渠道、审核步骤等增加统一中文映射和中文未知值兜底，避免未来枚举直接回显英文。CRM、SKU、AI、API、ID、PDF、SABC 等常用简写保留。
- 同步更新页面、展示函数及测试断言，不改变权限、业务流程、API 契约或数据值。

### 实际验证
- `npm run typecheck`、`npm run lint`：通过。
- `npm run test -- --reporter=dot`：57 个测试文件、235 项测试全部通过。
- `npm run build`：通过，仅有既存大 chunk 提示。

## [P1-4] 退款成功回执、权益回收与机构结算 — 2026-10-26

### 实现
- 新增 `V13__p1_4_refund_settlement.sql` 与回滚：退款主单、权益人工决策、渠道结果、只追加状态历史，以及结算批次、核销明细和付款凭证。
- 退款严格按 REQUESTED→APPROVED/REJECTED→PROCESSING→SUCCEEDED/FAILED；申请人与审批人必须不同。审批和执行中均不改变订单退款额或权益，只有人工财务复核或 Mock 验签成功结果才更新资产。
- 成功事务锁退款单、订单与权益，校验金额和余额，条件化增加 refunded_fen，追加 REFUND_REVOKE、更新快照、写审计及 refund.succeeded.v1/entitlement.refund_revoked.v1；并发、同键异请求、超额回收和晚失败均回滚。
- 月结/次卡次结仅接收真实 POSTED 核销，redemptionId 唯一入批并保存机构费率快照，不自动算钱。待核实禁止付款；人工核准等式后用 CLEAN 的 PAYMENT 凭证分次登记付款，银行流水去重并写 settlement.payment.recorded.v1。
- 只读对账覆盖权益账本、订单现金/退款、退款分摊、批次付款及未入批核销，不自动修账。机构财务只读本机构，其他角色按 refund/settlement 权限分离。
- 新增退款与结算页面、订单入口和移动卡片；金额待核实不显示 0 元。OpenAPI 135 路径、926 个本地引用及三个事件契约通过校验。

### 实际验证
- `./mvnw -pl apps/crm-api -am verify`：BUILD SUCCESS；77 项单元测试 + 170 项 MySQL 8.4 Testcontainers 集成测试，共 247 项，覆盖 R01/R02、并发、权限、回滚、唯一入批、核准与付款边界。
- 前端 `typecheck/lint/test/build` 全部通过；57 个测试文件、235 项 Vitest 通过，构建仅有既存大 chunk 提示。
- 真实浏览器以 manager1 申请回收1次/100元，finance1 异人批准：批准后成功退款 0、权益3、回收流水0。人工凭证成功后订单退款100元、权益降为2、仅一条 REFUND_REVOKE，两个事件均 SENT。
- 创建月结批次时待核实无付款入口；人工核准后分两笔400+600分付款，批次 PAID，两笔事件均 SENT。风险对账 0 差异，iPhone 14 Pro 仿真移动卡片正常。
- 联调中发现并修复结算误用机构合同/资质校验器的问题，付款凭证现严格使用 PAYMENT 类别；定向与全量回归均通过。

### 阶段边界
- 所有退款、付款与凭证均为本地合成联调数据，不代表真实银行退款、机构打款或生产支付接入。真实有赞退款字段、供应商回调和生产结算仍需 Q02/Q04/Q05/Q06 及渠道材料确认。
- 本阶段未将待核实佣金率自动转为应结金额，未宣称真实渠道、生产付款或业务 Kafka consumer/Inbox 已验收。
