# CHANGELOG

本仓库按 `docs/06_大模型开发指令/61_分阶段Prompt.md` 分阶段推进，每个阶段一条记录。

## [CHANNEL-PROFILE] 合作渠道完整资料与格式校验 — 2026-11-10

### 实现
- V26 为 `crm_partner_channel` 增量新增博主分佣、真实姓名、联系方式、身份证、结算方式、合作起止日期及收款账户资料；渠道编号改由后端自动生成 CH+ULID，新增表单不再要求手动输入，编辑仍不可修改。保留状态机与订单归因历史，新增唯一键/索引说明和批准后的回滚脚本。
- V27 新增博主/达人、分销、老带新三种合作类型及列表筛选；列表分别使用青绿、紫色、橙色标签。博主/达人仅允许电汇月结，分销支持电汇月结或每月两次，老带新固定结算规则未确认前必须留空。前后端同步校验，不改变既有订单归因快照。
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
