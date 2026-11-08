# 鲸选美学 CRM（whale-crm）

医美行业 CRM 系统 V2.0，从 `docs/` 交付文档包分阶段研发。本仓库为**模块化单体**：单库强事务 + Outbox/Kafka 异步事件。

- 业务规则与强制约束：见根目录 [AGENTS.md](AGENTS.md) 与 [docs/README.md](docs/README.md)
- 分阶段任务卡：[docs/06_大模型开发指令/61_分阶段Prompt.md](docs/06_大模型开发指令/61_分阶段Prompt.md)
- 执行清单：[docs/TASKS.md](docs/TASKS.md)（P0 至 P2 本地阶段已完成，P3-K01 Kafka 与 M01 隔离恢复验收已通过，见 [CHANGELOG.md](CHANGELOG.md)；P1-2 真实官方接入待 Q10，其余 P3 门禁进行中）

## 技术基线

| 组件 | 版本 | 说明 |
|---|---|---|
| Java | 21 | crm-api 后端 |
| Spring Boot | 4.0.9 | 模块化单体，Maven 构建（仓库内置 mvnw） |
| MyBatis | starter 4.0.1 | 数据访问（不用 MyBatis-Plus） |
| MySQL | 8.4 LTS | InnoDB / utf8mb4，Flyway 版本化迁移 |
| Redis | 7.4 | 缓存/限流/临时幂等（不是账本） |
| Kafka | 4.1.2 KRaft | 异步事件（Outbox 模式，P0-3 起接入） |
| MinIO | latest | 私有文件桶（开发） |
| Vue | 3 + TS + Vite | crm-web 前端（Element Plus / Pinia / Router） |
| Python FastAPI | — | ai-worker（AI 证据与知识适配，默认 Mock/禁用） |

## 目录结构

```text
├── apps/
│   ├── crm-api/        # Spring Boot 后端（模块化单体）
│   ├── crm-web/        # Vue3 + TS 前端
│   └── ai-worker/      # FastAPI AI 辅助服务（骨架）
├── contracts/          # OpenAPI / Kafka 事件契约 / 错误码字典
├── docs/               # V2.0 交付文档包（业务与安全以此为准）
├── infra/compose/      # 本地开发中间件（MySQL/Redis/Kafka/MinIO）
└── scripts/            # 本地与 CI 校验脚本
```

## 快速开始（本地开发）

```bash
# 1. 启动中间件（首次会自动生成 infra/compose/.env，仅开发默认值）
./scripts/dev-up.sh

# 2. 后端（默认 local profile，端口 8080，Flyway 自动迁移+开发字典种子）
./mvnw -pl apps/crm-api spring-boot:run

# 3. 前端（端口 5173，代理 /api 与 /actuator 到 8080）
npm --prefix apps/crm-web install
npm --prefix apps/crm-web run dev

# 4. AI worker
python3 -m venv apps/ai-worker/.venv
apps/ai-worker/.venv/bin/pip install -r apps/ai-worker/requirements.txt
apps/ai-worker/.venv/bin/uvicorn app.main:create_app --factory --app-dir apps/ai-worker --port 8090
```

ClamAV 原生明文 TCP 仅允许本机地址；远程扫描应通过本机安全代理，不要将 `CLAMAV_HOST` 直接指向未保护的远程服务。

## Linux 离线安装包

`infra/production/` 提供生产配置、HTTPS 入口、管理员初始化、离线镜像安装和升级备份脚本。默认制作 x86_64 Linux 包，输出到 `release/`；服务器只需 Docker 与 Docker Compose，不需要 Java、Node.js 或 Python。

```bash
./scripts/build-installer.sh
```

安装、域名、文件上传下载及上线边界见 [安装说明](infra/production/README.md)。该包是单机部署基线，不替代生产安全、容量、高可用、恢复和真实供应商验收。

需要继续研发的精简源码包时，执行 `./scripts/package-source.sh`。源码、测试、迁移、契约和部署脚本会保留，依赖、构建产物、运行数据、原型来源目录及真实密钥不会打入包内；输出为 `release/whale-crm-source-2.0.0.tar.gz` 和 SHA-256 校验文件，与服务器安装包分开交付。

## 验证命令（每阶段必须真实执行）

```bash
./mvnw -pl apps/crm-api -am verify          # 后端单测 + Testcontainers 集成测试（需 Docker）
npm --prefix apps/crm-web run typecheck
npm --prefix apps/crm-web run lint
npm --prefix apps/crm-web run test
npm --prefix apps/crm-web run build
apps/ai-worker/.venv/bin/python -m pytest apps/ai-worker/tests
```

或一键执行：`./scripts/verify.sh`。后端 `verify` 同时执行 Checkstyle 3.6.0、SpotBugs 4.10.5.0 与 Spotless 3.10.4；配置文件在 `config/checkstyle/` 和 `config/spotbugs/`，CI 复用同一命令。Spotless 只检查 Java 缩进、行尾空白和文件末尾换行，不自动重排源码。

本地合成环境的扩展演练（不替代生产验收）：

```bash
./scripts/m01-recovery.sh   # 独立 MySQL/MinIO 恢复，保留开发源与失败证据
./scripts/load-smoke.sh     # 需本机后端已启动；只读负载，默认每接口 200 次、并发 5
python3 scripts/dependency_audit.py  # Maven/Worker 实际依赖的 OSV 漏洞审计
./scripts/static_security_scan.sh  # Gitleaks + Semgrep 限定源码扫描，需 Docker
```

## 当前阶段状态

- [x] **P0-0 工程骨架**：统一错误、requestId、安全默认拒绝、CORS、Flyway、健康检查与 CI。
- [x] **P0-1 认证权限与客户域**：Cookie JWT + CSRF、RBAC/数据范围、字典、客户 23 字段映射、身份标签、积分账本、查重合并、转交审批、跟进任务。前端视觉以 `docs/08_原型与来源/V2.0_交互原型.html` 为准。
- [x] **P0-2 产品套餐与 SKU**：一卡多规格、机构适用、草稿/激活/归档、快照冻结与 Q01/Q02 安全边界。
- [x] **P0-3 订单、支付与权益**：可信人工支付确认、权威账本、幂等与事务 Outbox/Kafka 发布。
- [x] **P0-4 预约与机构核销**：机构逐级履约确认、OPERATOR 正式核销、异人冲正及权益补偿。
- [x] **P0-5 机构档案与审批**：机构主档、合同资质、敏感财务字段及市场/财务分级审批。
- [x] **P1-1 有赞适配准备**：V8、Mock Adapter、验签 Raw、商品/客户映射、订单支付幂等、异常补偿及同步中心；真实接入待 Q11。
- [ ] **P1-2 企业微信官方采集（进行中）**：Adapter 抽象、合规归档 Mock、身份匹配、消息私有引用与缺采监控；官方 SDK 待企业授权。
- [x] **P1-3 AI 证据抽取与人工确认（2026-10-26）**：V11 异步任务、严格 Schema、证据/权限复核、主管质量建议及人工确认普通待办；默认 Mock/禁用，真实模型供应商效果未宣称验收。
- [x] **P1-4 退款与机构结算（2026-10-26）**：V13 退款状态机、成功回执后现金与权益回收、月结/次卡次结、人工核准、CLEAN 凭证分次付款及只读对账；真实渠道与生产付款不作宣称。
- [x] **原型一致性整改（2026-10-26）**：已实现的管理员、管家、机构页面按最终原型完成逐页验收；登录页保留项目最初版本。前端 207 项测试、类型检查、Lint 和生产构建通过，未接入能力继续明确标记而不伪造数据。
- [x] **P2 数据运营与治理（2026-10-28，本地阶段验收）**：真实报表、活动批次、素材版本与下架、订单类型快照、授权异步导出、风控客户关联及审计已实现；完成 X01/X02、D01、风控闭环和浏览器验收。未确认的 Q13 指标继续显示待确认。
- [x] **P2-KB-1 Dify 知识配置（本地实现与 Mock 验收）**：独立 Worker Chatflow 适配、V20 加密配置、管理员页面和固定合成连接测试已完成；不包含正式知识问答或真实 Dify 验收，部署与数据治理待 Q19/Q20。
- [x] **P2-KB-2 资料发布与 Dataset 同步（本地链路验收）**：V21 发布映射、Worker、Kafka/Inbox、重试/停用和管理页面已实现；真实 MinIO/ClamAV + Kafka + Mock Dataset 已达到 ACTIVE/SUCCEEDED 并停用成功。公开问答、多轮会话、PDF/OCR 和真实 Dify 联调未完成，Q19/Q20 待确认，详见 `CHANGELOG.md`。
- [x] **运营数据大屏（内测第一版）**：`/operations/dashboard` 以真实经营、业绩和活动报表聚合适配展示六项指标、日趋势、履约阶段、机构与活动排行；支持授权筛选、自动刷新和全屏。保持 `report:read` 与后端数据范围，Q13 转化率、客户分层和异常提醒不在首版。
- [ ] **P3 上线加固**：K01 真实 Kafka、M01 隔离恢复及本机负载/安全冒烟已完成；Checkstyle、SpotBugs、Spotless 已绑定后端 `verify` 和 CI，Gitleaks/Semgrep 复扫通过，此前全栈验收为后端 239 项单测、256 项集成测试、前端 405 项测试、Worker 49 项测试。Spring Web MVC 已升级至修复版本并完成依赖漏洞复查；真实渗透、生产容量、生产部署、灰度与回滚仍待验收。最新验证记录见 `CHANGELOG.md`。

> 有赞 / 企微 / AI 写回 / 真实 Dify 均为 Mock 或禁用状态，由 `FEATURE_REAL_YOUZAN` / `FEATURE_REAL_WECOM` / `FEATURE_AI_WRITEBACK` / `FEATURE_REAL_DIFY` 开关控制，默认全部关闭。有赞与企微全局开关可由具备 `integration:runtime:manage` 的管理员在对应页面按租户配置并即时生效，环境变量作为默认值；页面开启不代表供应商授权或真实联调已完成。
