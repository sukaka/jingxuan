/**
 * 鲸选美学 2026 年终购买轨迹 H5 — 前端访问行为埋点采集器（Demo + 生产可复用核心）
 *
 * 设计要点：
 * 1. 仅统计前台可见有效时长（监听 visibilitychange，切后台/锁屏立刻暂停计时并触发 sendBeacon 兜底）；
 * 2. 双口径记录浏览深度：viewedScenes（去重看过哪几页）+ maxSceneReached（最深到达第几页）；
 * 3. 按幕独立累加停留毫秒数（sceneDurationsMs[0..5]），便于识别高意向停留页面；
 * 4. 捕获高价值转化交互：点亮灯箱、展开订单详情、切换隐私模式、生成海报、点击预约管家；
 * 5. Demo 模式将采集摘要实时写入 localStorage，并生成可直接对接后端 POST /api/v1/public/annual-report/telemetry 的标准报文。
 */
(function (global) {
  'use strict';

  const STORAGE_KEY = 'jx_annual_h5_telemetry_v1';
  const TOTAL_SCENES = 6;
  const SCENE_NAMES = [
    '第1幕 · 启幕相识',
    '第2幕 · 四季轨迹',
    '第3幕 · 深夜光影',
    '第4幕 · 年度挚爱',
    '第5幕 · 权益宝库',
    '第6幕 · 纪念灯箱'
  ];
  const HEARTBEAT_MS = 10000;
  const MAX_DELTA_MS = 15000; // 单次增量上限，防止设备休眠时钟跳变导致虚高

  function genId(prefix) {
    return `${prefix}-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
  }

  function hashTokenDemo(customerId) {
    // 演示用脱敏令牌摘要（生产环境由服务端签发并仅存储 SHA-256 token_hash）
    let h = 2166136261;
    const str = `JX2026:${customerId}`;
    for (let i = 0; i < str.length; i++) {
      h ^= str.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return 'tok_' + (h >>> 0).toString(16).padStart(8, '0') + '_2026';
  }

  function formatDuration(ms) {
    const totalSec = Math.max(0, Math.floor(ms / 1000));
    if (totalSec < 60) return `${totalSec}s`;
    const min = Math.floor(totalSec / 60);
    const sec = totalSec % 60;
    return `${min}分${String(sec).padStart(2, '0')}秒`;
  }

  function safeLoadStore() {
    try {
      const raw = global.localStorage.getItem(STORAGE_KEY);
      if (!raw) return { customers: {} };
      const parsed = JSON.parse(raw);
      return parsed && typeof parsed.customers === 'object' ? parsed : { customers: {} };
    } catch (_) {
      return { customers: {} };
    }
  }

  function safeSaveStore(data) {
    try {
      global.localStorage.setItem(STORAGE_KEY, JSON.stringify(data));
    } catch (_) {
      // 无痕模式或存储受限时静默降级为纯内存
    }
  }

  class AnnualReportTracker {
    constructor(initialCustomer, options) {
      this.endpoint = (options && options.endpoint) || '/api/v1/public/annual-report/telemetry';
      this.demoMode = !options || options.demoMode !== false;
      this.store = safeLoadStore();
      this.eventLog = [];
      this.heartbeatTimer = null;
      this.isVisible = document.visibilityState !== 'hidden';

      this._startSession(initialCustomer, 0);
      this._bindLifecycle();
    }

    _startSession(customer, startSceneIndex) {
      const now = Date.now();
      this.session = {
        sessionId: genId('ses'),
        reportYear: 2026,
        tenantId: 'TENANT-JINGXUAN-01',
        customerId: customer.customerId,
        displayName: customer.displayName,
        tierLabel: customer.tierLabel,
        butlerName: customer.butlerName,
        shareTokenHash: hashTokenDemo(customer.customerId),
        isOwnerVerified: true, // 演示默认本人打开；正式环境由微信/有赞静默 OAuth 判定
        firstOpenAt: new Date(now).toISOString(),
        lastActiveAt: new Date(now).toISOString(),
        currentScene: startSceneIndex,
        maxSceneReached: startSceneIndex + 1,
        viewedScenes: [startSceneIndex],
        sceneDurationsMs: Array(TOTAL_SCENES).fill(0),
        totalVisibleDurationMs: 0,
        actions: {
          ignitedLamp: false,
          openedOrders: [],
          toggledPrivacy: false,
          generatedPoster: false,
          clickedBookButler: false
        }
      };

      this._lastTick = performance.now();
      this._ensureCustomerRecord(customer, true);
      this._flush('SESSION_OPEN', { sceneIndex: startSceneIndex });
    }

    _ensureCustomerRecord(customer, incrementOpen) {
      const id = customer.customerId;
      if (!this.store.customers[id]) {
        this.store.customers[id] = {
          customerId: id,
          displayName: customer.displayName,
          tierLabel: customer.tierLabel,
          butlerName: customer.butlerName,
          openCount: 0,
          totalVisibleDurationMs: 0,
          maxSceneReached: 1,
          viewedScenesUnion: [0],
          openedOrderCount: 0,
          generatedPoster: false,
          clickedBookButler: false,
          lastVisitAt: new Date().toISOString()
        };
      }
      const rec = this.store.customers[id];
      rec.displayName = customer.displayName;
      rec.tierLabel = customer.tierLabel;
      rec.butlerName = customer.butlerName;
      if (incrementOpen) {
        rec.openCount += 1;
      }
      rec.lastVisitAt = new Date().toISOString();
      // 记录本会话初始基线，便于增量合并而不重复叠加
      this._sessionCommittedDuration = 0;
      this._sessionCommittedOrders = 0;
      safeSaveStore(this.store);
    }

    _syncStoreIncremental() {
      const s = this.session;
      const rec = this.store.customers[s.customerId];
      if (!rec) return;

      const deltaDur = Math.max(0, s.totalVisibleDurationMs - (this._sessionCommittedDuration || 0));
      rec.totalVisibleDurationMs += deltaDur;
      this._sessionCommittedDuration = s.totalVisibleDurationMs;

      const deltaOrders = Math.max(0, s.actions.openedOrders.length - (this._sessionCommittedOrders || 0));
      rec.openedOrderCount += deltaOrders;
      this._sessionCommittedOrders = s.actions.openedOrders.length;

      rec.maxSceneReached = Math.max(rec.maxSceneReached, s.maxSceneReached);
      const union = new Set([...(rec.viewedScenesUnion || []), ...s.viewedScenes]);
      rec.viewedScenesUnion = Array.from(union).sort((a, b) => a - b);
      rec.generatedPoster = rec.generatedPoster || s.actions.generatedPoster;
      rec.clickedBookButler = rec.clickedBookButler || s.actions.clickedBookButler;
      rec.lastVisitAt = s.lastActiveAt;

      safeSaveStore(this.store);
    }

    _accumulateVisibleTime() {
      const nowPerf = performance.now();
      if (this.isVisible && this._lastTick != null) {
        const rawDelta = nowPerf - this._lastTick;
        if (rawDelta > 0) {
          const safeDelta = Math.min(rawDelta, MAX_DELTA_MS);
          const idx = this.session.currentScene;
          this.session.sceneDurationsMs[idx] += safeDelta;
          this.session.totalVisibleDurationMs += safeDelta;
          this.session.lastActiveAt = new Date().toISOString();
        }
      }
      this._lastTick = nowPerf;
    }

    _bindLifecycle() {
      document.addEventListener('visibilitychange', () => {
        if (document.visibilityState === 'hidden') {
          this._accumulateVisibleTime();
          this.isVisible = false;
          this._flush('VISIBILITY_HIDE', { transport: 'sendBeacon' }, true);
        } else {
          this.isVisible = true;
          this._lastTick = performance.now();
          this._flush('VISIBILITY_RESUME', { sceneIndex: this.session.currentScene });
        }
      });

      window.addEventListener('pagehide', () => {
        this._accumulateVisibleTime();
        this.isVisible = false;
        this._flush('PAGE_HIDE', { transport: 'sendBeacon' }, true);
      });

      this.heartbeatTimer = window.setInterval(() => {
        if (!this.isVisible) return;
        this._accumulateVisibleTime();
        this._flush('HEARTBEAT', { intervalSec: HEARTBEAT_MS / 1000 });
      }, HEARTBEAT_MS);
    }

    enterScene(sceneIndex) {
      this._accumulateVisibleTime();
      if (sceneIndex === this.session.currentScene) return;
      const fromScene = this.session.currentScene;
      this.session.currentScene = sceneIndex;
      if (!this.session.viewedScenes.includes(sceneIndex)) {
        this.session.viewedScenes.push(sceneIndex);
        this.session.viewedScenes.sort((a, b) => a - b);
      }
      this.session.maxSceneReached = Math.max(this.session.maxSceneReached, sceneIndex + 1);
      this._flush('SCENE_CHANGE', { fromScene: fromScene + 1, toScene: sceneIndex + 1 });
    }

    recordAction(actionType, meta) {
      this._accumulateVisibleTime();
      const act = this.session.actions;
      if (actionType === 'IGNITE_LAMP') act.ignitedLamp = true;
      if (actionType === 'TOGGLE_PRIVACY') act.toggledPrivacy = true;
      if (actionType === 'GENERATE_POSTER') act.generatedPoster = true;
      if (actionType === 'CLICK_BOOK_BUTLER') act.clickedBookButler = true;
      if (actionType === 'VIEW_ORDER' && meta && meta.externalOrderNo) {
        if (!act.openedOrders.includes(meta.externalOrderNo)) {
          act.openedOrders.push(meta.externalOrderNo);
        }
      }
      this._flush(`ACTION:${actionType}`, meta || {});
    }

    switchCustomer(newCustomer, sceneIndex) {
      this._accumulateVisibleTime();
      this._flush('SESSION_SWITCH_OUT', { nextCustomerId: newCustomer.customerId });
      this._startSession(newCustomer, sceneIndex);
    }

    buildPayload(trigger, detail) {
      const s = this.session;
      const sceneDurationsSec = {};
      s.sceneDurationsMs.forEach((ms, i) => {
        sceneDurationsSec[`scene_${i + 1}`] = Number((ms / 1000).toFixed(1));
      });

      return {
        schemaVersion: 'annual_telemetry.v1',
        trigger: trigger,
        sentAt: new Date().toISOString(),
        sessionId: s.sessionId,
        reportYear: s.reportYear,
        shareTokenHash: s.shareTokenHash,
        customerId: s.customerId,
        isOwnerVerified: s.isOwnerVerified,
        metrics: {
          visibleDurationMs: Math.round(s.totalVisibleDurationMs),
          viewedSceneCount: s.viewedScenes.length,
          maxSceneReached: s.maxSceneReached,
          completionRate: Number(((s.viewedScenes.length / TOTAL_SCENES) * 100).toFixed(0)),
          viewedScenes: s.viewedScenes.map((i) => i + 1),
          sceneDurationsSec: sceneDurationsSec
        },
        conversions: {
          ignitedLamp: s.actions.ignitedLamp,
          openedOrderCount: s.actions.openedOrders.length,
          openedOrders: s.actions.openedOrders.slice(),
          toggledPrivacy: s.actions.toggledPrivacy,
          generatedPoster: s.actions.generatedPoster,
          clickedBookButler: s.actions.clickedBookButler
        },
        detail: detail || {}
      };
    }

    _flush(trigger, detail, useBeacon) {
      this._syncStoreIncremental();
      const payload = this.buildPayload(trigger, detail);
      const transport = useBeacon ? 'sendBeacon(模拟)' : 'POST(模拟)';

      this.eventLog.unshift({
        time: new Date().toTimeString().slice(0, 8),
        trigger: trigger,
        transport: transport,
        scene: this.session.currentScene + 1,
        durationLabel: formatDuration(this.session.totalVisibleDurationMs),
        payload: payload
      });
      if (this.eventLog.length > 24) {
        this.eventLog.length = 24;
      }

      // 正式生产模式（demoMode === false）时直接走真实后端接口
      if (!this.demoMode) {
        const body = JSON.stringify(payload);
        if (useBeacon && navigator.sendBeacon) {
          navigator.sendBeacon(this.endpoint, new Blob([body], { type: 'application/json' }));
        } else {
          fetch(this.endpoint, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: body,
            keepalive: true
          }).catch(() => {});
        }
      }
      return payload;
    }

    getLiveSnapshot() {
      this._accumulateVisibleTime();
      this._syncStoreIncremental();
      const s = this.session;
      return {
        session: s,
        sceneNames: SCENE_NAMES,
        totalScenes: TOTAL_SCENES,
        isVisible: this.isVisible,
        totalDurationLabel: formatDuration(s.totalVisibleDurationMs),
        completionPct: Math.round((s.viewedScenes.length / TOTAL_SCENES) * 100),
        latestPayload: this.buildPayload('LIVE_PREVIEW', { transport: 'demo_preview' }),
        eventLog: this.eventLog
      };
    }

    getAllCustomerSummaries() {
      this._accumulateVisibleTime();
      this._syncStoreIncremental();
      return Object.values(this.store.customers).sort((a, b) =>
        String(b.lastVisitAt).localeCompare(String(a.lastVisitAt))
      );
    }

    resetDemoRecords(currentCustomer, currentSceneIndex) {
      this.store = { customers: {} };
      this.eventLog = [];
      safeSaveStore(this.store);
      this._startSession(currentCustomer, currentSceneIndex);
    }
  }

  global.JXAnalytics = {
    AnnualReportTracker: AnnualReportTracker,
    formatDuration: formatDuration,
    SCENE_NAMES: SCENE_NAMES
  };
})(window);
