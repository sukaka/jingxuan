/**
 * 鲸选美学 2026 年终购买轨迹 H5 — 主交互逻辑
 */
(function (global) {
  'use strict';

  const TOTAL_SCENES = 6;

  // 轻量 WebAudio 暖光提示音（纯合成五声音阶，零音频文件依赖）
  class LanternAudio {
    constructor() {
      this.enabled = true;
      this.ctx = null;
    }
    ensure() {
      if (!this.enabled) return null;
      if (!this.ctx) {
        const AudioCtx = window.AudioContext || window.webkitAudioContext;
        if (AudioCtx) this.ctx = new AudioCtx();
      }
      if (this.ctx && this.ctx.state === 'suspended') {
        this.ctx.resume().catch(() => {});
      }
      return this.ctx;
    }
    playChime(freqs) {
      const ctx = this.ensure();
      if (!ctx) return;
      const now = ctx.currentTime;
      freqs.forEach((f, i) => {
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'sine';
        osc.frequency.setValueAtTime(f, now + i * 0.085);
        gain.gain.setValueAtTime(0.0001, now + i * 0.085);
        gain.gain.exponentialRampToValueAtTime(0.045, now + i * 0.085 + 0.04);
        gain.gain.exponentialRampToValueAtTime(0.0001, now + i * 0.085 + 0.7);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start(now + i * 0.085);
        osc.stop(now + i * 0.085 + 0.72);
      });
    }
    ignite() { this.playChime([392, 493.88, 587.33, 783.99]); }
    turnPage() { this.playChime([523.25, 659.25]); }
    tap() { this.playChime([587.33]); }
  }

  const PERSONA_ORDER = ['lin', 'su', 'chen'];
  const TONE_ORDER = ['poetic', 'clinical', 'minimal'];

  const state = {
    personaKey: 'lin',
    toneKey: 'poetic',
    toneManualOverride: false,
    sceneIndex: 0,
    ignited: false,
    hideAmount: false,
    seasonFilter: 'ALL',
    favView: 'dimensions',
    telemetryTab: 'session'
  };

  const audio = new LanternAudio();
  const canvas = document.getElementById('paperCanvas');
  const engine = new global.PaperLightboxEngine(canvas);
  const tracker = new global.JXAnalytics.AnnualReportTracker(global.JXData.customers[state.personaKey], {
    demoMode: true
  });

  // DOM 缓存
  const el = {
    progress: document.getElementById('storyProgress'),
    scenes: Array.from(document.querySelectorAll('.scene')),
    btnPrev: document.getElementById('btnPrev'),
    btnNext: document.getElementById('btnNext'),
    sceneCounter: document.getElementById('sceneCounter'),
    btnIgnite: document.getElementById('btnIgnite'),
    coverIgniteBox: document.getElementById('coverIgniteBox'),
    coverRevealed: document.getElementById('coverRevealed'),
    timelineList: document.getElementById('timelineList'),
    seasonTabs: document.getElementById('seasonTabs'),
    btnPrivacy: document.getElementById('btnPrivacy'),
    btnSchema: document.getElementById('btnSchema'),
    btnTelemetry: document.getElementById('btnTelemetry'),
    toolbarTelemetryBadge: document.getElementById('toolbarTelemetryBadge'),
    btnSound: document.getElementById('btnSound'),
    miniPersona: document.getElementById('miniPersona'),
    miniTone: document.getElementById('miniTone'),
    miniPrivacy: document.getElementById('miniPrivacy'),
    miniTelemetry: document.getElementById('miniTelemetry'),
    orderSheet: document.getElementById('orderSheet'),
    bookingSheet: document.getElementById('bookingSheet'),
    posterModal: document.getElementById('posterModal'),
    schemaModal: document.getElementById('schemaModal'),
    telemetryModal: document.getElementById('telemetryModal'),
    cavity: document.getElementById('lightboxCavity')
  };

  function currentCustomer() {
    return global.JXData.customers[state.personaKey];
  }

  function currentCopy() {
    return global.JXCopyEngine.generateCopy(currentCustomer(), state.toneKey);
  }

  function buildProgressBar() {
    el.progress.innerHTML = '';
    for (let i = 0; i < TOTAL_SCENES; i++) {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'progress-seg';
      btn.setAttribute('aria-label', `前往第 ${i + 1} 幕`);
      btn.innerHTML = '<span></span>';
      btn.addEventListener('click', () => {
        if (i > 0 && !state.ignited) igniteCover(false);
        goToScene(i);
      });
      el.progress.appendChild(btn);
    }
  }

  function animateCounter(dom, targetValue) {
    const start = performance.now();
    const dur = 760;
    function frame(now) {
      const p = Math.min(1, (now - start) / dur);
      const eased = 1 - Math.pow(1 - p, 3);
      dom.textContent = String(Math.round(targetValue * eased));
      if (p < 1) requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  function igniteCover(playSound = true) {
    state.ignited = true;
    el.coverIgniteBox.hidden = true;
    el.coverRevealed.hidden = false;
    engine.igniteLamp();
    tracker.recordAction('IGNITE_LAMP');
    if (playSound) audio.ignite();
    animateCounter(document.getElementById('daysTogether'), currentCustomer().daysTogether);
    renderTelemetryBadge();
  }

  function goToScene(index) {
    const clamped = Math.max(0, Math.min(TOTAL_SCENES - 1, index));
    if (clamped === state.sceneIndex && el.scenes[clamped].classList.contains('is-active')) {
      return;
    }
    if (clamped > 0 && !state.ignited) {
      igniteCover(false);
    }
    state.sceneIndex = clamped;
    document.body.dataset.scene = String(clamped);
    engine.setScene(clamped);
    tracker.enterScene(clamped);
    audio.turnPage();
    renderTelemetryBadge();

    el.scenes.forEach((sec, idx) => {
      sec.classList.toggle('is-active', idx === clamped);
    });

    Array.from(el.progress.children).forEach((seg, idx) => {
      seg.classList.toggle('is-done', idx < clamped);
      seg.classList.toggle('is-active', idx === clamped);
    });

    el.btnPrev.disabled = clamped === 0;
    el.btnNext.disabled = clamped === TOTAL_SCENES - 1;
    el.btnNext.textContent = clamped === TOTAL_SCENES - 1 ? '已至末页' : '下一幕 →';
    el.sceneCounter.textContent = `0${clamped + 1} / 0${TOTAL_SCENES}`;

    // 切到第 4 幕时触发光带平滑生长
    if (clamped === 3) {
      renderFavoriteSection();
    }
  }

  // ---------- 渲染全部客户数据 + 个性化千人千面文案 ----------
  function renderCustomer() {
    const c = currentCustomer();
    const copy = currentCopy();
    const inferred = global.JXCopyEngine.inferTone(c);
    const fmt = (fen) => global.JXData.formatFenToYuan(fen, state.hideAmount);

    // 同步语调按钮与自动匹配提示
    document.querySelectorAll('.tone-btn').forEach((b) => {
      b.classList.toggle('is-active', b.dataset.tone === copy.toneKey);
    });
    if (el.miniTone) el.miniTone.textContent = global.JXCopyEngine.TONES[copy.toneKey].shortLabel;
    document.getElementById('toneOriginPill').textContent = state.toneManualOverride
      ? `✦ 手动预览：${copy.toneLabel}（同一份数据切换语调）`
      : `✦ ${copy.toneLabel} · ${inferred.reason}`;

    // 第 1 幕
    document.getElementById('coverTierTag').textContent = c.tierLabel;
    document.getElementById('coverMemberNo').textContent = c.memberNo;
    document.getElementById('coverSalutation').textContent = copy.salutation;
    document.getElementById('coverSubTitle').textContent = copy.coverSubTitle;
    document.getElementById('igniteBtnText').textContent = copy.igniteBtnText;
    document.getElementById('igniteHint').textContent = copy.igniteHint;
    document.getElementById('firstMeetDate').textContent = c.firstMeetDate;
    document.getElementById('daysTogether').textContent = String(c.daysTogether);
    document.getElementById('firstMeetNote').textContent = copy.firstMeetNote;
    document.getElementById('coverNextBtnText').textContent = copy.coverNextBtn;

    // 第 2 幕汇总与时间轴
    document.getElementById('scene1Title').textContent = copy.scene1Title;
    document.getElementById('statOrderCount').textContent = String(c.orders.length);
    document.getElementById('statTotalSpend').textContent = fmt(c.netSpendFen);
    document.getElementById('statCategoryCount').textContent = String(c.categoryCount);
    renderTimeline();

    // 第 3 幕深夜光影
    document.getElementById('scene2Title').textContent = copy.scene2Title;
    document.getElementById('midnightTime').textContent = c.midnight.time;
    document.getElementById('midnightDate').textContent = c.midnight.date;
    document.getElementById('midnightEventType').textContent = c.midnight.eventType;
    document.getElementById('midnightStory').textContent = copy.midnightStory;
    document.getElementById('midnightPeakLabel').textContent = c.midnight.peakLabel;
    renderCircadianDial(c.midnight.hourDistribution);

    // 第 4 幕年度挚爱
    document.getElementById('scene3Title').textContent = copy.scene3Title;
    renderFavoriteSection();

    // 第 5 幕履约与权益宝库
    document.getElementById('scene4Title').textContent = copy.scene4Title;
    document.getElementById('vaultRedeemCount').textContent = String(c.redeemCount);
    document.getElementById('vaultInstCount').textContent = String(c.institutionCount);
    document.getElementById('vaultButlerCount').textContent = String(c.butlerResponseCount);
    document.getElementById('vaultBadgeText').textContent = copy.vaultBadge;
    document.getElementById('vaultRemainTotal').textContent = String(c.remainingEntitlements.totalCount);
    document.getElementById('vaultExpireText').textContent = `有效期至 ${c.remainingEntitlements.expireDate}`;
    document.getElementById('vaultCtaText').textContent = copy.vaultCta;

    const vaultUl = document.getElementById('vaultItemsList');
    vaultUl.innerHTML = '';
    c.remainingEntitlements.items.forEach((item) => {
      const li = document.createElement('li');
      li.className = 'vault-item';
      li.innerHTML = `
        <div>
          <strong>${item.name}</strong>
          <span class="vault-item-sub">${item.inst}</span>
        </div>
        <span class="vault-item-cnt">${item.count}</span>
      `;
      vaultUl.appendChild(li);
    });

    // 第 6 幕纪念灯箱卡
    document.getElementById('keepsakeSeal').textContent = c.sealText;
    document.getElementById('keepsakeOwnerHeading').textContent = copy.posterHeading;
    document.getElementById('keepsakePersona').textContent = copy.personaTitle;
    document.getElementById('keepsakeQuote').textContent = copy.personaQuote;
    document.getElementById('kmDays').textContent = `${c.daysTogether} 天`;
    document.getElementById('kmOrders').textContent = `${c.orders.length} 笔`;
    document.getElementById('kmFav').textContent = c.favorite.projectName;
    document.getElementById('kmSpend').textContent = fmt(c.netSpendFen);
    document.getElementById('keepsakeButlerSign').textContent = copy.butlerSign;

    syncPrivacyLabels();
  }

  function renderTimeline() {
    const c = currentCustomer();
    const fmt = (fen) => global.JXData.formatFenToYuan(fen, state.hideAmount);
    const list = state.seasonFilter === 'ALL'
      ? c.orders
      : c.orders.filter((o) => o.season === state.seasonFilter);

    el.timelineList.innerHTML = '';
    if (!list.length) {
      el.timelineList.innerHTML = '<div class="paper-plaque" style="text-align:center;font-size:12px;">该季节暂无有赞下单记录，切换其他季节看看吧</div>';
      return;
    }

    list.forEach((order) => {
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'order-card';
      btn.innerHTML = `
        <div class="order-date-badge">
          <span class="order-date-md">${order.md}</span>
          <span class="order-date-season mono">${order.time}</span>
        </div>
        <div class="order-main">
          <div class="order-title">${order.title}</div>
          <div class="order-sub">
            <span class="order-tag">${order.season} · ${order.category}</span>
            <span>${order.channel}</span>
          </div>
        </div>
        <div class="order-right">
          <span class="order-amount">${fmt(order.amountFen)}</span>
          <span class="order-status ${order.isOpen ? 'is-open' : ''}">${order.statusText}</span>
        </div>
      `;
      btn.addEventListener('click', () => openOrderSheet(order));
      el.timelineList.appendChild(btn);
    });
  }

  function openOrderSheet(order) {
    audio.tap();
    tracker.recordAction('VIEW_ORDER', { externalOrderNo: order.externalOrderNo, title: order.title });
    const fmt = (fen) => global.JXData.formatFenToYuan(fen, state.hideAmount);
    document.getElementById('sheetOrderNo').textContent = `有赞单号 ${order.externalOrderNo}`;
    document.getElementById('sheetOrderTitle').textContent = order.title;

    document.getElementById('sheetMetaGrid').innerHTML = `
      <div class="meta-box"><span>下单支付时间</span><strong class="mono">${order.date} ${order.time}</strong></div>
      <div class="meta-box"><span>实付金额（整数分）</span><strong class="mono">${fmt(order.amountFen)}</strong></div>
      <div class="meta-box"><span>履约臻选机构</span><strong>${order.institution}</strong></div>
      <div class="meta-box"><span>订单状态</span><strong>${order.statusText}</strong></div>
    `;

    const ul = document.getElementById('sheetSkuList');
    ul.innerHTML = '';
    order.skus.forEach((sku) => {
      const li = document.createElement('li');
      li.className = 'sheet-sku-item';
      li.innerHTML = `<span>${sku.name}</span><strong class="mono">${sku.qty}</strong>`;
      ul.appendChild(li);
    });

    el.orderSheet.hidden = false;
  }

  function renderCircadianDial(points) {
    const ticksG = document.getElementById('clockTicks');
    const ptsG = document.getElementById('clockPoints');
    ticksG.innerHTML = '';
    ptsG.innerHTML = '';

    // 24 小时细刻度
    for (let h = 0; h < 24; h++) {
      const ang = (h / 24) * Math.PI * 2 - Math.PI / 2;
      const r1 = h % 6 === 0 ? 73 : 77;
      const r2 = 82;
      const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      line.setAttribute('x1', (110 + Math.cos(ang) * r1).toFixed(1));
      line.setAttribute('y1', (110 + Math.sin(ang) * r1).toFixed(1));
      line.setAttribute('x2', (110 + Math.cos(ang) * r2).toFixed(1));
      line.setAttribute('y2', (110 + Math.sin(ang) * r2).toFixed(1));
      line.setAttribute('stroke', h >= 21 || h <= 2 ? 'rgba(255, 198, 112, 0.58)' : 'rgba(255, 224, 178, 0.22)');
      line.setAttribute('stroke-width', h % 6 === 0 ? '1.8' : '1');
      ticksG.appendChild(line);
    }

    // 标出客户的订单/咨询时刻发光点
    points.forEach((p) => {
      const ang = (p.hour / 24) * Math.PI * 2 - Math.PI / 2;
      const r = 72;
      const cx = 110 + Math.cos(ang) * r;
      const cy = 110 + Math.sin(ang) * r;

      if (p.isLatest) {
        const halo = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
        halo.setAttribute('cx', cx.toFixed(1));
        halo.setAttribute('cy', cy.toFixed(1));
        halo.setAttribute('r', '11');
        halo.setAttribute('fill', 'rgba(255, 184, 98, 0.34)');
        ptsG.appendChild(halo);
      }

      const dot = document.createElementNS('http://www.w3.org/2000/svg', 'circle');
      dot.setAttribute('cx', cx.toFixed(1));
      dot.setAttribute('cy', cy.toFixed(1));
      dot.setAttribute('r', p.isLatest ? '5' : String(2.8 + p.weight * 1.8));
      dot.setAttribute('fill', p.isLatest ? '#fff3cf' : '#ffbf69');
      ptsG.appendChild(dot);
    });
  }

  function renderFavoriteSection() {
    const f = currentCustomer().favorite;
    const copy = currentCopy();
    document.getElementById('favBeatPercent').textContent = f.beatPercent;
    document.getElementById('favProjectName').textContent = f.projectName;
    document.getElementById('favProjectDesc').textContent = copy.favDesc;

    const tagsBox = document.getElementById('favProjectTags');
    tagsBox.innerHTML = f.tags.map((t) => `<span class="fav-chip"># ${t}</span>`).join('');

    const dimPanel = document.getElementById('favDimensionsPanel');
    dimPanel.innerHTML = f.dimensions.map((d) => `
      <div class="dim-row">
        <div class="dim-label-row">
          <span>${d.name}</span>
          <strong class="mono">${d.percent}%</strong>
        </div>
        <div class="dim-track">
          <div class="dim-fill" style="width: ${state.sceneIndex === 3 ? d.percent : 12}%"></div>
        </div>
      </div>
    `).join('');

    const topPanel = document.getElementById('favTop3Panel');
    topPanel.innerHTML = f.top3.map((row) => `
      <div class="top3-row">
        <span class="top3-rank">${row.rank}</span>
        <span class="top3-name">${row.name}</span>
        <strong class="top3-count">${row.count}</strong>
      </div>
    `).join('');

    dimPanel.hidden = state.favView !== 'dimensions';
    topPanel.hidden = state.favView !== 'top3';
  }

  function renderSchemaModal() {
    const box = document.getElementById('schemaList');
    box.innerHTML = global.JXData.schemaMapping.map((item) => `
      <div class="schema-item">
        <div class="schema-item-head">
          <span class="schema-item-title">${item.title}</span>
          <span class="schema-badge ${item.isNote ? 'is-note' : ''}">${item.badge}</span>
        </div>
        <p>${item.detail}</p>
      </div>
    `).join('');
  }

  function syncPrivacyLabels() {
    el.btnPrivacy.setAttribute('aria-pressed', String(state.hideAmount));
    el.btnPrivacy.querySelector('.pill-label').textContent = state.hideAmount ? '金额已隐藏' : '金额可见';
    el.miniPrivacy.textContent = state.hideAmount ? '已隐' : '隐私';
    document.getElementById('keepsakePrivacyNote').textContent = state.hideAmount
      ? '隐私模式已开 · 金额已自动隐藏'
      : '已显示金额 · 可切换隐私晒图';
    document.getElementById('endPrivacyLabel').textContent = state.hideAmount ? '显示消费金额' : '切换隐藏金额';
  }

  function togglePrivacy() {
    audio.tap();
    state.hideAmount = !state.hideAmount;
    tracker.recordAction('TOGGLE_PRIVACY', { hideAmount: state.hideAmount });
    renderCustomer();
    if (!el.posterModal.hidden) {
      refreshPosterPreview();
    }
  }

  function switchPersona(key) {
    if (key === state.personaKey) return;
    audio.tap();
    state.personaKey = key;
    state.seasonFilter = 'ALL';
    state.toneManualOverride = false;
    state.toneKey = global.JXCopyEngine.inferTone(currentCustomer()).toneKey;
    Array.from(el.seasonTabs.children).forEach((b) => {
      b.classList.toggle('is-active', b.dataset.season === 'ALL');
    });
    document.querySelectorAll('.persona-btn').forEach((b) => {
      b.classList.toggle('is-active', b.dataset.persona === key);
    });
    tracker.switchCustomer(currentCustomer(), state.sceneIndex);
    renderCustomer();
    renderTelemetryBadge();
    if (!el.telemetryModal.hidden) renderTelemetryModal();
    if (!el.posterModal.hidden) refreshPosterPreview();
    if (state.sceneIndex === 0 && state.ignited) {
      animateCounter(document.getElementById('daysTogether'), currentCustomer().daysTogether);
    }
  }

  function switchTone(toneKey) {
    if (!global.JXCopyEngine.TONES[toneKey]) return;
    audio.tap();
    state.toneKey = toneKey;
    state.toneManualOverride = true;
    renderCustomer();
    if (!el.posterModal.hidden) refreshPosterPreview();
  }

  // ---------- 实时访问埋点监控视图 ----------
  function renderTelemetryBadge() {
    const snap = tracker.getLiveSnapshot();
    const label = `${snap.session.viewedScenes.length}/${snap.totalScenes}页 · ${snap.totalDurationLabel}`;
    if (el.toolbarTelemetryBadge) el.toolbarTelemetryBadge.textContent = label;
    if (el.miniTelemetry) el.miniTelemetry.textContent = '埋点';
  }

  function renderTelemetryModal() {
    const snap = tracker.getLiveSnapshot();
    const s = snap.session;

    // Tab 1: 当前会话
    document.getElementById('tVisitorName').textContent = `${s.displayName}（${s.tierLabel}）`;
    document.getElementById('tVisitorMeta').textContent = `${s.customerId} · ${s.shareTokenHash} · 本人已校验`;
    document.getElementById('tTotalDuration').textContent = snap.totalDurationLabel;
    document.getElementById('tPagesRead').textContent = `${s.viewedScenes.length}/${snap.totalScenes} · ${snap.completionPct}%`;
    document.getElementById('tMaxScene').textContent = `第 ${s.maxSceneReached} 幕`;

    const maxSceneMs = Math.max(5000, ...s.sceneDurationsMs);
    const barsEl = document.getElementById('tSceneBars');
    barsEl.innerHTML = snap.sceneNames.map((name, idx) => {
      const ms = s.sceneDurationsMs[idx];
      const visited = s.viewedScenes.includes(idx);
      const pct = visited ? Math.max(4, Math.round((ms / maxSceneMs) * 100)) : 0;
      const secText = visited ? `${(ms / 1000).toFixed(1)}s` : '未看';
      return `
        <div class="t-scene-row ${idx === s.currentScene ? 'is-current' : ''}">
          <span>${name}</span>
          <div class="t-scene-track"><div class="t-scene-fill" style="width:${pct}%"></div></div>
          <span class="t-scene-sec mono">${secText}</span>
        </div>
      `;
    }).join('');

    const act = s.actions;
    const chips = [
      { label: '点亮灯箱', hit: act.ignitedLamp },
      { label: `查看订单明细 (${act.openedOrders.length})`, hit: act.openedOrders.length > 0 },
      { label: '切换隐藏金额', hit: act.toggledPrivacy },
      { label: '生成纪念海报', hit: act.generatedPoster },
      { label: '点击预约管家', hit: act.clickedBookButler }
    ];
    document.getElementById('tActionChips').innerHTML = chips
      .map((c) => `<span class="t-action-chip ${c.hit ? 'is-hit' : ''}">${c.hit ? '✓ ' : ''}${c.label}</span>`)
      .join('');

    // Tab 2: 多客户统计
    const summaries = tracker.getAllCustomerSummaries();
    document.getElementById('tSummaryList').innerHTML = summaries.map((rec) => {
      const pages = (rec.viewedScenesUnion || []).length;
      const highIntent = rec.clickedBookButler || (pages >= 5 && rec.totalVisibleDurationMs >= 15000);
      const intentText = rec.clickedBookButler ? '已点击预约 · 高意向' : (highIntent ? '深度浏览 · 建议跟进' : '已触达');
      return `
        <div class="t-summary-card">
          <div class="t-summary-top">
            <strong>${rec.displayName} · 管家 ${rec.butlerName}</strong>
            <span class="t-intent-tag ${highIntent ? 'is-high' : ''}">${intentText}</span>
          </div>
          <div class="t-summary-stats">
            <div>打开次数<b>${rec.openCount} 次</b></div>
            <div>累计停留<b>${global.JXAnalytics.formatDuration(rec.totalVisibleDurationMs)}</b></div>
            <div>已看页数<b>${pages} / 6 页</b></div>
            <div>海报 / 预约<b>${rec.generatedPoster ? '✓' : '—'} / ${rec.clickedBookButler ? '✓' : '—'}</b></div>
          </div>
        </div>
      `;
    }).join('');

    // Tab 3: 上报报文与触发流水
    document.getElementById('tJsonPreview').textContent = JSON.stringify(snap.latestPayload, null, 2);
    document.getElementById('tEventLog').innerHTML = snap.eventLog.slice(0, 10).map((ev) => `
      <li>
        <span class="mono">${ev.time} · ${ev.trigger}</span>
        <span class="mono">第${ev.scene}幕 · ${ev.durationLabel} · ${ev.transport}</span>
      </li>
    `).join('');

    // Tab 显隐切换
    document.getElementById('tPanelSession').hidden = state.telemetryTab !== 'session';
    document.getElementById('tPanelSummary').hidden = state.telemetryTab !== 'summary';
    document.getElementById('tPanelPayload').hidden = state.telemetryTab !== 'payload';
  }

  function refreshPosterPreview() {
    const c = currentCustomer();
    const copy = currentCopy();
    const dataUrl = global.JXPoster.renderKeepsakePoster(c, state.hideAmount, copy);
    document.getElementById('posterPreviewImg').src = dataUrl;
    const dl = document.getElementById('btnDownloadPoster');
    dl.href = dataUrl;
    dl.download = `jingxuan-2026-${c.customerId}-${copy.toneKey}${state.hideAmount ? '-private' : ''}.png`;
  }

  // ---------- 事件绑定 ----------
  function bindEvents() {
    el.btnIgnite.addEventListener('click', () => igniteCover(true));

    document.querySelectorAll('[data-action="next"]').forEach((btn) => {
      btn.addEventListener('click', () => goToScene(state.sceneIndex + 1));
    });
    el.btnPrev.addEventListener('click', () => goToScene(state.sceneIndex - 1));
    el.btnNext.addEventListener('click', () => goToScene(state.sceneIndex + 1));

    // 季节筛选
    el.seasonTabs.addEventListener('click', (e) => {
      const btn = e.target.closest('.season-tab');
      if (!btn) return;
      audio.tap();
      state.seasonFilter = btn.dataset.season;
      Array.from(el.seasonTabs.children).forEach((b) => b.classList.toggle('is-active', b === btn));
      renderTimeline();
    });

    // 第 4 幕视图切换
    document.querySelectorAll('.seg-btn').forEach((btn) => {
      btn.addEventListener('click', () => {
        audio.tap();
        state.favView = btn.dataset.favView;
        document.querySelectorAll('.seg-btn').forEach((b) => b.classList.toggle('is-active', b === btn));
        renderFavoriteSection();
      });
    });

    // 第 5 幕预约专属管家
    document.getElementById('btnBookButler').addEventListener('click', () => {
      audio.ignite();
      const c = currentCustomer();
      tracker.recordAction('CLICK_BOOK_BUTLER', { butlerName: c.butlerName });
      document.getElementById('bookingBodyText').textContent =
        `已向专属管家 ${c.butlerName} 推送 2027 焕美预约意向提醒（演示模式不会写入生产数据库，也不会自动扣减权益）。`;
      document.getElementById('bookingPreviewBox').innerHTML = `
        客户档案：<strong>${c.displayName}</strong>（${c.memberNo}）<br />
        待开启权益：<strong>${c.remainingEntitlements.items.map((x) => `${x.name} ${x.count}`).join(' / ')}</strong><br />
        通知状态：<strong>待管家回访确认到院时间</strong>
      `;
      el.bookingSheet.hidden = false;
    });

    // 第 6 幕生成海报 & 重温
    document.getElementById('btnGeneratePoster').addEventListener('click', () => {
      audio.ignite();
      tracker.recordAction('GENERATE_POSTER', { hideAmount: state.hideAmount });
      refreshPosterPreview();
      el.posterModal.hidden = false;
    });
    document.getElementById('btnPosterPrivacyFlip').addEventListener('click', togglePrivacy);
    document.getElementById('btnTogglePrivacyEnd').addEventListener('click', togglePrivacy);
    document.getElementById('btnReplay').addEventListener('click', () => goToScene(0));

    // 顶部控制台与移动端快捷入口
    document.querySelectorAll('.persona-btn').forEach((btn) => {
      btn.addEventListener('click', () => switchPersona(btn.dataset.persona));
    });
    el.miniPersona.addEventListener('click', () => {
      const idx = PERSONA_ORDER.indexOf(state.personaKey);
      switchPersona(PERSONA_ORDER[(idx + 1) % PERSONA_ORDER.length]);
    });
    document.querySelectorAll('.tone-btn').forEach((btn) => {
      btn.addEventListener('click', () => switchTone(btn.dataset.tone));
    });
    if (el.miniTone) {
      el.miniTone.addEventListener('click', () => {
        const idx = TONE_ORDER.indexOf(state.toneKey);
        switchTone(TONE_ORDER[(idx + 1) % TONE_ORDER.length]);
      });
    }
    el.btnPrivacy.addEventListener('click', togglePrivacy);
    el.miniPrivacy.addEventListener('click', togglePrivacy);

    const openSchema = () => {
      audio.tap();
      el.schemaModal.hidden = false;
    };
    el.btnSchema.addEventListener('click', openSchema);
    document.getElementById('btnMidnightSource').addEventListener('click', openSchema);

    // 实时访问埋点监控弹窗
    const openTelemetry = () => {
      audio.tap();
      renderTelemetryModal();
      el.telemetryModal.hidden = false;
    };
    el.btnTelemetry.addEventListener('click', openTelemetry);
    el.miniTelemetry.addEventListener('click', openTelemetry);

    document.querySelectorAll('.t-tab').forEach((btn) => {
      btn.addEventListener('click', () => {
        audio.tap();
        state.telemetryTab = btn.dataset.tTab;
        document.querySelectorAll('.t-tab').forEach((b) => b.classList.toggle('is-active', b === btn));
        renderTelemetryModal();
      });
    });

    document.getElementById('btnCopyPayload').addEventListener('click', () => {
      const text = document.getElementById('tJsonPreview').textContent;
      const btn = document.getElementById('btnCopyPayload');
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(() => {
          btn.textContent = '已复制 ✓';
          window.setTimeout(() => { btn.textContent = '复制 JSON'; }, 1600);
        }).catch(() => {});
      }
    });

    document.getElementById('btnResetTelemetry').addEventListener('click', () => {
      audio.tap();
      tracker.resetDemoRecords(currentCustomer(), state.sceneIndex);
      renderTelemetryBadge();
      renderTelemetryModal();
    });

    el.btnSound.addEventListener('click', () => {
      audio.enabled = !audio.enabled;
      el.btnSound.setAttribute('aria-pressed', String(audio.enabled));
      el.btnSound.querySelector('.pill-label').textContent = audio.enabled ? '音效开' : '已静音';
      if (audio.enabled) audio.tap();
    });

    // 关闭所有弹窗/抽屉
    document.querySelectorAll('[data-close-sheet]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const id = btn.dataset.closeSheet;
        document.getElementById(id).hidden = true;
      });
    });
    [el.orderSheet, el.bookingSheet, el.posterModal, el.schemaModal, el.telemetryModal].forEach((backdrop) => {
      backdrop.addEventListener('click', (e) => {
        if (e.target === backdrop) backdrop.hidden = true;
      });
    });

    // 键盘切幕
    window.addEventListener('keydown', (e) => {
      if (!el.orderSheet.hidden || !el.bookingSheet.hidden || !el.posterModal.hidden || !el.schemaModal.hidden || !el.telemetryModal.hidden) {
        if (e.key === 'Escape') {
          el.orderSheet.hidden = true;
          el.bookingSheet.hidden = true;
          el.posterModal.hidden = true;
          el.schemaModal.hidden = true;
          el.telemetryModal.hidden = true;
        }
        return;
      }
      if (e.key === 'ArrowRight' || e.key === 'ArrowDown') goToScene(state.sceneIndex + 1);
      if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') goToScene(state.sceneIndex - 1);
    });

    // 触摸上下/左右滑动切幕（避开内部可滚动列表）
    let touchStartX = 0;
    let touchStartY = 0;
    let touchInScrollable = false;
    el.cavity.addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return;
      touchStartX = e.touches[0].clientX;
      touchStartY = e.touches[0].clientY;
      touchInScrollable = Boolean(e.target.closest('.scrollable-zone'));
    }, { passive: true });

    el.cavity.addEventListener('touchend', (e) => {
      if (!e.changedTouches.length) return;
      const dx = e.changedTouches[0].clientX - touchStartX;
      const dy = e.changedTouches[0].clientY - touchStartY;
      if (Math.abs(dx) > 54 && Math.abs(dx) > Math.abs(dy) * 1.2) {
        goToScene(state.sceneIndex + (dx < 0 ? 1 : -1));
      } else if (!touchInScrollable && Math.abs(dy) > 64 && Math.abs(dy) > Math.abs(dx) * 1.3) {
        goToScene(state.sceneIndex + (dy < 0 ? 1 : -1));
      }
    }, { passive: true });

    // 指针/触控景深视差
    window.addEventListener('pointermove', (e) => {
      const nx = (e.clientX / window.innerWidth) * 2 - 1;
      const ny = (e.clientY / window.innerHeight) * 2 - 1;
      engine.setParallax(nx, ny);
    }, { passive: true });
  }

  // 初始化启动
  buildProgressBar();
  renderSchemaModal();
  renderCustomer();
  bindEvents();
  goToScene(0);
  renderTelemetryBadge();
  engine.start();

  // 每秒刷新实时埋点徽标，若埋点面板开启则同步跳动停留时长
  window.setInterval(() => {
    renderTelemetryBadge();
    if (!el.telemetryModal.hidden) {
      renderTelemetryModal();
    }
  }, 1000);
})(window);
