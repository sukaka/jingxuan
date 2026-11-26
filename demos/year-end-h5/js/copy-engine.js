/**
 * 鲸选美学 2026 千人千面文案引擎（Demo 完整演示版 + 生产规则兜底引擎核心）
 *
 * 支持：
 * 1. 基于客户性别（他/她）、客龄、消费品类、活跃时段自动推断最匹配的语调风格（解决 10,000+ 人冷启动免人工打标）；
 * 2. 同一份有赞订单与 CRM 事实数据，按不同语调实时输出完全不同的 6 幕文案与纪念海报结语；
 * 3. 所有文案严格绑定真实数据槽位，零医疗夸大/违禁词，可直接作为 10,000+ 客户的分层规则模板底座。
 */
(function (global) {
  'use strict';

  const TONES = {
    poetic: {
      key: 'poetic',
      label: '诗意松弛风',
      shortLabel: '诗意',
      desc: '感性温润、注重仪式感与氛围表达，适合老客与深夜感性客群'
    },
    clinical: {
      key: 'clinical',
      label: '清醒专业风',
      shortLabel: '专业',
      desc: '理性数据化、强调成分与维养周期，适合功课型成分党'
    },
    minimal: {
      key: 'minimal',
      label: '极简高效风',
      shortLabel: '高效',
      desc: '克制干练、少煽情重结果，适合男士客户与高压职场精英'
    }
  };

  /**
   * 10,000+ 客户自动语调推断（零人工打标冷启动规则）
   * 优先级：1) 管家显式标签 -> 2) 性别与消费特征自动推断
   */
  function inferTone(customer) {
    if (customer.explicitToneTag && TONES[customer.explicitToneTag]) {
      return {
        toneKey: customer.explicitToneTag,
        reason: `管家偏好标签：${TONES[customer.explicitToneTag].label}`
      };
    }
    if (customer.gender === 'male') {
      return {
        toneKey: 'minimal',
        reason: '自动匹配：男士状态管理画像 + 高效履约节奏'
      };
    }
    const tags = (customer.preferenceTags || []).join(',');
    if (tags.includes('成分') || tags.includes('微针') || tags.includes('功课')) {
      return {
        toneKey: 'clinical',
        reason: '自动匹配：成分功课偏好 + 精准周期维养特征'
      };
    }
    if (customer.daysTogether >= 500 || customer.tierCode === 'S') {
      return {
        toneKey: 'poetic',
        reason: '自动匹配：长期陪伴老客 + 深夜仪式感下单特征'
      };
    }
    return {
      toneKey: 'poetic',
      reason: '自动匹配：默认温润美学语调'
    };
  }

  function generateCopy(customer, toneKey) {
    const resolvedKey = TONES[toneKey] ? toneKey : inferTone(customer).toneKey;
    const isMale = customer.gender === 'male';
    const salutationName = customer.honorific
      ? `${customer.displayName} ${customer.honorific}`
      : customer.displayName;
    const favName = customer.favorite.projectName;
    const orderCnt = customer.orders.length;
    const m = customer.midnight;
    const rem = customer.remainingEntitlements;

    if (resolvedKey === 'clinical') {
      return {
        toneKey: 'clinical',
        toneLabel: TONES.clinical.label,
        salutation: `致 ${salutationName}`,
        coverSubTitle: '2026 精准维养年度数据档案',
        igniteBtnText: '解锁你的 2026 科学变美轨迹',
        igniteHint: '基于有赞交易快照与院线核销记录生成 · 仅本人可见',
        firstMeetNote: `${customer.firstMeetDate} 建立首份美学档案，${customer.daysTogether} 天里共完成 ${customer.redeemCount} 次规律院线护理，每一次投入都有清晰周期逻辑。`,
        coverNextBtn: '查看全年订单数据',
        scene1Title: '全年下单记录，每一笔都有清晰功课',
        scene2Title: '你的活跃决策时刻，藏在夜间专注里',
        midnightStory: `${m.date} ${m.time}，你在结束一天日程后依然认真核对护理周期，锁定了《${m.orderTitle}》——懂得看懂成分与间隔，本身就是最好的底气。`,
        scene3Title: '全年高频复购，锁定你的核心维养公式',
        favDesc: `全年围绕「${favName}」建立稳定维养节律，${customer.favorite.clinicalNote || '兼顾肌底修护与长期状态稳定'}。`,
        scene4Title: '从下单到到院核销，闭环见证真实履约',
        vaultBadge: '✦ 权益账本提醒 · 尚有可用余次',
        vaultCta: `通知管家 ${customer.butlerName} 安排下一周期预约`,
        personaTitle: isMale ? '理性状态管理专家' : '清醒成分美学派',
        personaQuote: isMale
          ? '拒绝盲目跟风，用清晰周期与靠谱院线维持稳定状态。2026 年，你把高效与克制写进了每一次护理安排。'
          : '每次决策前都认真做足功课，懂得按季节与肤质节律精准投入。2026 年，理性与好状态在你身上完美重合。',
        butlerSign: `专属美学顾问 ${customer.butlerName} · 数据核验寄语`,
        posterHeading: `${salutationName} 的 2026 精准美学画像`
      };
    }

    if (resolvedKey === 'minimal') {
      return {
        toneKey: 'minimal',
        toneLabel: TONES.minimal.label,
        salutation: `${salutationName}，见字如晤`,
        coverSubTitle: '2026 个人状态管理年度简报',
        igniteBtnText: '开启 2026 状态复盘',
        igniteHint: '有赞订单与院线履约同步汇总 · 私密只读视图',
        firstMeetNote: `自 ${customer.firstMeetDate} 结缘起已同行 ${customer.daysTogether} 天。这一年 ${orderCnt} 笔下单、${customer.redeemCount} 次高效到院，把繁琐留给团队，只留从容状态。`,
        coverNextBtn: '浏览年度记录',
        scene1Title: '2026 全年投入与项目清单',
        scene2Title: '高效决策，往往发生在忙碌落幕后',
        midnightStory: `${m.date} ${m.time}，在结束全天日程后的片刻空档，你用几分钟干脆完成了《${m.orderTitle}》的安排——不拖泥带水，状态管理同样讲究效率。`,
        scene3Title: '最常选择的王牌方案，省心且稳妥',
        favDesc: `核心聚焦「${favName}」，${customer.favorite.minimalNote || '单次耗时短、无需冗长恢复期，完美匹配高强度日程'}。`,
        scene4Title: '履约进度清晰可控，余次一键衔接',
        vaultBadge: '✦ 账户尚有未核销权益待安排',
        vaultCta: `让管家 ${customer.butlerName} 协调近期到院时段`,
        personaTitle: isMale ? '秩序感状态管理家' : '高效率质感主理人',
        personaQuote: isMale
          ? '不堆砌冗余仪式，只在关键节点选择专业方案。2026 年，你用极简自律保持着松弛而利落的精气神。'
          : '清晰知道自己要什么，从不在选择上内耗。2026 年，你把高效与好质感同时握在了手里。',
        butlerSign: `专属管家 ${customer.butlerName} · 2026 简报`,
        posterHeading: `${salutationName} · 2026 状态管理年度档案`
      };
    }

    // 默认：poetic（诗意松弛风）
    return {
      toneKey: 'poetic',
      toneLabel: TONES.poetic.label,
      salutation: `致 ${salutationName}`,
      coverSubTitle: '2026 年度私享纸雕灯箱',
      igniteBtnText: '轻触点亮你的 2026 光采灯箱',
      igniteHint: '来自有赞与鲸选美学专属档案 · 仅你本人可见',
      firstMeetNote: `${customer.firstMeetDate} 那天，我们开启了第一场光采之约；${customer.daysTogether} 个日夜流转，时间把每一次用心都酿成了柔和光泽。`,
      coverNextBtn: '推开四季光影',
      scene1Title: '一整年，你把光留在了这些日子',
      scene2Title: '整座城睡着时，有一扇窗还亮着',
      midnightStory: `${m.date} ${m.time}，夜色慢慢安静下来，你为自己定格了一份《${m.orderTitle}》——把一整天最后的温柔，留给了发光的自己。`,
      scene3Title: '你的年度本命，藏在一次次偏爱里',
      favDesc: `${customer.favorite.desc || `全年反复偏爱「${favName}」，是你最安心的光采底牌`}`,
      scene4Title: '不止于下单，更见证每一次赴约蜕变',
      vaultBadge: '✦ 你的美学宝库仍有待开启权益',
      vaultCta: `一键通知专属管家 ${customer.butlerName} 预约`,
      personaTitle: isMale ? '静谧时光鉴赏家' : '午夜发光收藏家',
      personaQuote: isMale
        ? '在快节奏的城市里，仍愿为自己留出一盏从容的灯。2026 年，你在克制与温柔之间找到了最舒适的松弛感。'
        : '不追逐喧嚣的潮流，只在安静时光里挑选最合拍的光与水。2026 年，你把松弛感写进了每一次肌肤焕亮。',
      butlerSign: `专属管家 ${customer.butlerName} · 敬寄`,
      posterHeading: `${salutationName} 的 2026 年度美学人格`
    };
  }

  global.JXCopyEngine = {
    TONES: TONES,
    inferTone: inferTone,
    generateCopy: generateCopy
  };
})(window);
