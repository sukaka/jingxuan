# 鲸选美学 2026 年终购买轨迹 H5 Demo（纸雕灯影版）

基于 `Lemo-Opuscar` 的 **`paper-lantern`（纸雕灯影 · Paper-cut Lightbox）** 风格规范打造的移动端交互式年终购买盘点 Demo。零构建依赖，不触碰 `apps/crm-api` 与 `apps/crm-web` 核心生产代码。

## 本地快速预览

在项目根目录或本目录启动任意静态服务即可体验（支持桌面端胡桃木纸雕灯箱外框与手机端 9:16 全屏沉浸）：

```bash
cd /Users/zhanglei/Documents/JingXuan/demos/year-end-h5
python3 -m http.server 8765
```

浏览器访问 `http://127.0.0.1:8765`。

## Demo 核心演示功能

1. **6 幕纸雕灯影沉浸体验**：
   - 第 1 幕：点亮专属灯箱 → 展示初次与鲸选相识日期与相伴天数；
   - 第 2 幕：四季购买轨迹 → 逐笔展示有赞订单日期与项目，点击可展开 SKU 快照详情；
   - 第 3 幕：深夜光影时刻 → 午夜城市独亮暖窗 + 24 小时购美时钟圆环；
   - 第 4 幕：年度挚爱项目 → 本命项目卡片 + 四大美学维度光带 / TOP3 切换；
   - 第 5 幕：履约蜕变与权益宝库 → 展示到院核销次数、剩余有效权益与一键通知专属管家预约；
   - 第 6 幕：年度美学人格 & 灯箱纪念海报 → 支持隐藏金额隐私模式与前端 Canvas 高清 PNG 海报下载。
2. **业务演示辅助控制条**：
   - 一键切换 2 位脱敏虚构客户（`林清晚 · S级老客` / `苏知夏 · A级新客`）；
   - 全局「金额隐私开关」，解决高客单医美客户晒朋友圈顾虑；
   - 「有赞口径」弹窗，面向业务团队清晰解释每屏字段来源与安全接入边界；
   - **「访问埋点」实时监控面板**：实时统计前台有效停留时长（切后台暂停）、看过几页与完读率、每一幕精确停留条、多客户累计统计汇总以及可直接对接后端的标准上报 JSON（`POST /api/v1/public/annual-report/telemetry` + `sendBeacon` 兜底）。

## 后续对接真实生产 API 改造点（待 Q11 放行）

1. 将 `js/mock-customers.js` 替换为只读后端聚合接口 `GET /api/v1/public/annual-report/{signedToken}`；
2. 将 `js/analytics-tracker.js` 的 `demoMode` 置为 `false`，直接上报至后端 `POST /api/v1/public/annual-report/telemetry`（按 `sessionId` 幂等落库 `crm_annual_report_visit_session`，供 CRM 运营看板与管家跟进筛选）；
3. 身份识别采用管家下发的时效签名 Token 或微信/有赞静默 OAuth（`yz_open_id` 映射 CRM `customerId`），禁止在 URL 暴露明文手机号或 `customerId`；
4. 金额严格沿用后端整数分口径（有效订单实付扣成功退款），预约按钮仅向专属管家创建跟进/预约意向任务，绝不自动确认订单或核销。
