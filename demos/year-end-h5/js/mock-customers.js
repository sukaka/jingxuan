/**
 * 鲸选美学 2026 年终购买轨迹 H5 — 脱敏虚构演示数据
 *
 * 严格遵循 AGENTS.md：
 * 1. 不含任何真实客户隐私，所有姓名/单号/客户 ID 均为虚构脱敏数据；
 * 2. 金额全部以整数分（*Fen）保存，展示时统一格式化，禁止 float 参与计算；
 * 3. 字段命名与有赞官方订单（youzan.trades.sold.get/4.0.2）及 CRM 领域模型 1:1 对齐。
 */
(function (global) {
  'use strict';

  function formatFenToYuan(fen, hideAmount) {
    if (hideAmount) return '私享保密';
    const yuan = Math.round(fen / 100);
    return '¥' + yuan.toLocaleString('zh-CN');
  }

  const MOCK_CUSTOMERS = {
    lin: {
      customerId: 'CUS-DEMO-8801',
      displayName: '林清晚',
      honorific: '',
      gender: 'female',
      pronoun: '她',
      preferenceTags: ['老客陪伴', '深夜仪式感', '光电水光'],
      memberNo: 'NO. JX-2024-0512',
      tierCode: 'S',
      tierLabel: '臻选 S 级缪斯',
      sealText: '臻选S',
      firstMeetDate: '2024.05.12',
      daysTogether: 928,
      firstMeetNote: '那一天你在有赞开启了第一份超光子水光之约，928 个日夜，时间把每一次用心都酿成了光泽。',
      butlerName: '安安',
      butlerResponseCount: 24,
      netSpendFen: 2680000, // ¥26,800（实付扣成功退款）
      categoryCount: 4,
      redeemCount: 9,
      institutionCount: 2,
      personaTitle: '午夜发光收藏家',
      personaQuote: '不追逐喧嚣的潮流，只在深夜安静为自己挑选最合拍的光与水。2026 年，你把松弛感写进了每一次肌肤焕亮。',
      midnight: {
        time: '23:47',
        date: '2026.07.18',
        orderTitle: '夏日超光子+嗨体水光季卡',
        eventType: '有赞深夜下单时刻（pay_time）',
        story: '2026 年 7 月 18 日 23:47，盛夏的晚风刚刚安静下来，你在有赞商城悄悄拍下了《夏日超光子+嗨体水光季卡》——把一整天最后的温柔，留给了变美的自己。',
        peakLabel: '高频购美时段：21:00–24:00（占比 57%）',
        hourDistribution: [
          { hour: 10.5, weight: 0.45, label: '10:30 晨间预约' },
          { hour: 14.2, weight: 0.55, label: '14:12 午后下单' },
          { hour: 19.8, weight: 0.72, label: '19:48 晚间加购' },
          { hour: 21.6, weight: 0.88, label: '21:35 黄金时段' },
          { hour: 22.9, weight: 0.92, label: '22:54 睡前选卡' },
          { hour: 23.78, weight: 1.0, isLatest: true, label: '23:47 年度最晚下单' }
        ]
      },
      favorite: {
        projectName: 'M22 黄金超光子',
        beatPercent: '超越 94% 同期焕美会员',
        desc: '全年累计入手与打卡 6 次 · 你最信赖的全季光学焕亮底牌',
        clinicalNote: '以全模式多波段滤片稳定提亮肤色、配合水光周期形成全年光学维养闭环',
        minimalNote: '单次约 30 分钟、零停工期，出差与日常社交间隙即可轻松完成状态提亮',
        tags: ['全模式滤片', '透亮淡斑', '零恢复期', '复购王牌'],
        dimensions: [
          { name: '光电焕亮', percent: 92 },
          { name: '深层水光', percent: 84 },
          { name: '轮廓紧致', percent: 68 },
          { name: '敏肌修护', percent: 56 }
        ],
        top3: [
          { rank: '01', name: 'M22 黄金超光子全模式', count: '6 次' },
          { rank: '02', name: '嗨体 2.5ml 颈部/面部水光', count: '5 次' },
          { rank: '03', name: '半岛超声炮全模式抗衰', count: '1 次' }
        ]
      },
      remainingEntitlements: {
        totalCount: 3,
        expireDate: '2027.06.30',
        items: [
          { name: 'M22 黄金超光子（全模式）', inst: '静安臻选美学中心 / 徐汇院区通用', count: '余 2 次' },
          { name: '丝丽 532 动能素水光', inst: '静安臻选美学中心', count: '余 1 次' }
        ]
      },
      orders: [
        {
          externalOrderNo: 'E20260118****1042',
          date: '2026.01.18',
          md: '01.18',
          time: '21:16',
          season: '冬',
          title: '新年焕亮 · 超光子水光双享卡',
          category: '光电水光',
          amountFen: 428000,
          statusText: '已核销完',
          isOpen: false,
          institution: '静安臻选美学中心',
          channel: '有赞官方商城',
          skus: [
            { name: 'M22 黄金超光子全模式', qty: '2 次' },
            { name: '基础玻尿酸补水水光', qty: '2 次' }
          ]
        },
        {
          externalOrderNo: 'E20260322****2390',
          date: '2026.03.22',
          md: '03.22',
          time: '14:12',
          season: '春',
          title: '春日敏肌维稳 · 舒敏之星护理',
          category: '敏肌修护',
          amountFen: 198000,
          statusText: '已核销完',
          isOpen: false,
          institution: '静安臻选美学中心',
          channel: '有赞官方商城',
          skus: [
            { name: '舒敏之星声波修护', qty: '2 次' },
            { name: '医用冷敷修护套组', qty: '1 盒' }
          ]
        },
        {
          externalOrderNo: 'E20260514****4107',
          date: '2026.05.14',
          md: '05.14',
          time: '22:54',
          season: '春',
          title: '520 私享季 · 嗨体熊猫眼周抗衰',
          category: '深层水光',
          amountFen: 368000,
          statusText: '已核销完',
          isOpen: false,
          institution: '徐汇星光医疗美容',
          channel: '有赞大促批次',
          skus: [
            { name: '嗨体熊猫针 1.0ml', qty: '1 支' },
            { name: '眼周胶原微光养护', qty: '1 次' }
          ]
        },
        {
          externalOrderNo: 'E20260718****8421',
          date: '2026.07.18',
          md: '07.18',
          time: '23:47',
          season: '夏',
          title: '夏日超光子 + 胶原水光季卡',
          category: '光电水光',
          amountFen: 588000,
          statusText: '余 1 次待约',
          isOpen: true,
          institution: '静安臻选美学中心',
          channel: '有赞官方商城',
          skus: [
            { name: 'M22 黄金超光子全模式', qty: '3 次' },
            { name: '丝丽 532 动能素水光', qty: '2 次' }
          ]
        },
        {
          externalOrderNo: 'E20260909****5520',
          date: '2026.09.09',
          md: '09.09',
          time: '19:48',
          season: '秋',
          title: '初秋轮廓高定 · 半岛超声炮全模式',
          category: '轮廓紧致',
          amountFen: 698000,
          statusText: '已核销完',
          isOpen: false,
          institution: '徐汇星光医疗美容',
          channel: '有赞快闪专享',
          skus: [
            { name: '半岛超声炮刀头+炮头全脸', qty: '1 次' },
            { name: '术后冰感修护面膜', qty: '2 盒' }
          ]
        },
        {
          externalOrderNo: 'E20261111****9012',
          date: '2026.11.11',
          md: '11.11',
          time: '21:35',
          season: '秋',
          title: '双十一臻选 · 超光子年度囤货卡',
          category: '光电水光',
          amountFen: 298000,
          statusText: '余 2 次待约',
          isOpen: true,
          institution: '静安臻选美学中心 / 徐汇院区通用',
          channel: '有赞双十一大促',
          skus: [
            { name: 'M22 黄金超光子全模式', qty: '2 次' }
          ]
        },
        {
          externalOrderNo: 'E20261120****9733',
          date: '2026.11.20',
          md: '11.20',
          time: '10:30',
          season: '冬',
          title: '岁末礼遇 · 嗨体 2.5ml 颈纹管理',
          category: '深层水光',
          amountFen: 102000,
          statusText: '已核销完',
          isOpen: false,
          institution: '静安臻选美学中心',
          channel: '有赞官方商城',
          skus: [
            { name: '嗨体 2.5ml 颈部水光', qty: '1 支' }
          ]
        }
      ]
    },

    su: {
      customerId: 'CUS-DEMO-9104',
      displayName: '苏知夏',
      honorific: '',
      gender: 'female',
      pronoun: '她',
      preferenceTags: ['成分党', '黄金微针', '精准功课'],
      memberNo: 'NO. JX-2026-0117',
      tierCode: 'A',
      tierLabel: '鎏金 A 级新星',
      sealText: '鎏金A',
      firstMeetDate: '2026.01.17',
      daysTogether: 312,
      firstMeetNote: '2026 年的第一个冬夜，我们因一次毛孔细腻功课相遇，312 天里共同见证了素颜发光的惊喜。',
      butlerName: '知意',
      butlerResponseCount: 15,
      netSpendFen: 968000, // ¥9,680
      categoryCount: 3,
      redeemCount: 5,
      institutionCount: 1,
      personaTitle: '清醒成分美学派',
      personaQuote: '每次决策前都认真做足功课，懂得在合适季节给皮肤恰到好处的滋养。2026 是你与精致美学并肩的第一年。',
      midnight: {
        time: '00:24',
        date: '2026.06.15',
        orderTitle: '夏日毛孔精雕·黄金微针胶原组',
        eventType: '合规企微深夜咨询 + 次日有赞成单',
        story: '2026 年 6 月 15 日凌晨 00:24，你在企微向专属管家知意认真请教了黄金微针与胶原水光的搭配周期，次日午后便在有赞稳稳拿下《夏日毛孔精雕组合》。',
        peakLabel: '高频活跃时段：22:00–01:00（占比 62%）',
        hourDistribution: [
          { hour: 12.4, weight: 0.52, label: '12:25 午休下单' },
          { hour: 18.6, weight: 0.65, label: '18:36 下班预约' },
          { hour: 22.3, weight: 0.85, label: '22:18 睡前功课' },
          { hour: 23.4, weight: 0.9, label: '23:24 查方案' },
          { hour: 0.4, weight: 1.0, isLatest: true, label: '00:24 凌晨咨询时刻' }
        ]
      },
      favorite: {
        projectName: '黄金微针 + 胶原水光',
        beatPercent: '超越 86% 同期新晋会员',
        desc: '全年累计焕肤 4 次 · 细腻毛孔与饱满弹性双线在线',
        clinicalNote: '以射频微针精准作用于真皮浅中层，叠加重组胶原蛋白提升肌底致密度',
        minimalNote: '高性价比联合方案，把毛孔收细与补水提亮合并在更少到院次数内完成',
        tags: ['毛孔精雕', '胶原新生', '精准分层', '高口碑组合'],
        dimensions: [
          { name: '毛孔精雕', percent: 94 },
          { name: '深层水光', percent: 82 },
          { name: '光电焕亮', percent: 64 },
          { name: '敏肌修护', percent: 48 }
        ],
        top3: [
          { rank: '01', name: '美迪迈黄金微针', count: '2 次' },
          { rank: '02', name: '薇旖美胶原蛋白水光', count: '2 次' },
          { rank: '03', name: 'BBL 牛奶光嫩肤', count: '2 次' }
        ]
      },
      remainingEntitlements: {
        totalCount: 2,
        expireDate: '2027.08.31',
        items: [
          { name: '美迪迈黄金微针（全脸）', inst: '杭州万象臻选院区', count: '余 1 次' },
          { name: 'BBL 牛奶光嫩肤', inst: '杭州万象臻选院区', count: '余 1 次' }
        ]
      },
      orders: [
        {
          externalOrderNo: 'E20260117****0815',
          date: '2026.01.17',
          md: '01.17',
          time: '22:18',
          season: '冬',
          title: '初见礼遇 · BBL 牛奶光体验双次卡',
          category: '光电焕亮',
          amountFen: 158000,
          statusText: '余 1 次待约',
          isOpen: true,
          institution: '杭州万象臻选院区',
          channel: '有赞官方商城',
          skus: [
            { name: 'BBL 牛奶光全脸嫩肤', qty: '2 次' }
          ]
        },
        {
          externalOrderNo: 'E20260408****3126',
          date: '2026.04.08',
          md: '04.08',
          time: '12:25',
          season: '春',
          title: '春光水润 · 薇旖美胶原水光首享',
          category: '深层水光',
          amountFen: 268000,
          statusText: '已核销完',
          isOpen: false,
          institution: '杭州万象臻选院区',
          channel: '有赞官方商城',
          skus: [
            { name: '薇旖美重组胶原蛋白水光', qty: '1 次' },
            { name: '院线冷喷修护', qty: '1 次' }
          ]
        },
        {
          externalOrderNo: 'E20260616****6219',
          date: '2026.06.16',
          md: '06.16',
          time: '13:40',
          season: '夏',
          title: '夏日毛孔精雕 · 黄金微针胶原组',
          category: '毛孔精雕',
          amountFen: 362000,
          statusText: '余 1 次待约',
          isOpen: true,
          institution: '杭州万象臻选院区',
          channel: '有赞 618 活动批次',
          skus: [
            { name: '美迪迈黄金微针全脸', qty: '2 次' },
            { name: '薇旖美胶原蛋白导入', qty: '1 次' }
          ]
        },
        {
          externalOrderNo: 'E20261019****8044',
          date: '2026.10.19',
          md: '10.19',
          time: '23:24',
          season: '秋',
          title: '秋日屏障修护 · 润百颜深层补水',
          category: '深层水光',
          amountFen: 180000,
          statusText: '已核销完',
          isOpen: false,
          institution: '杭州万象臻选院区',
          channel: '有赞官方商城',
          skus: [
            { name: '润百颜院线深层水光', qty: '2 次' }
          ]
        }
      ]
    },

    chen: {
      customerId: 'CUS-DEMO-9318',
      displayName: '陈砚洲',
      honorific: '先生',
      gender: 'male',
      pronoun: '他',
      preferenceTags: ['男士状态管理', '极简高效', '午休无创'],
      memberNo: 'NO. JX-2025-0806',
      tierCode: 'A',
      tierLabel: '鎏金 A 级绅士',
      sealText: '鎏金A',
      firstMeetDate: '2025.08.06',
      daysTogether: 477,
      firstMeetNote: '自 2025.08.06 结缘起已同行 477 天，把皮肤与轮廓状态管理交给专业团队，省心高效。',
      butlerName: '陆晨',
      butlerResponseCount: 11,
      netSpendFen: 1460000, // ¥14,600
      categoryCount: 3,
      redeemCount: 4,
      institutionCount: 1,
      personaTitle: '秩序感状态管理家',
      personaQuote: '不堆砌冗余仪式，只在关键节点选择专业方案。2026 年，你用极简自律保持着松弛而利落的精气神。',
      midnight: {
        time: '22:15',
        date: '2026.08.29',
        orderTitle: '商务精英 · 轮廓紧致无创抗衰',
        eventType: '有赞晚间快捷下单（pay_time）',
        story: '2026 年 8 月 29 日 22:15，在结束加班后的空档，你用 3 分钟在有赞完成了《轮廓紧致无创抗衰》预约下单——省去繁琐，只留干净利落的好状态。',
        peakLabel: '高频决策时段：12:00–13:30 / 21:00–22:30',
        hourDistribution: [
          { hour: 12.2, weight: 0.82, label: '12:10 午休快捷下单' },
          { hour: 13.1, weight: 0.75, label: '13:05 预约周末到院' },
          { hour: 20.5, weight: 0.78, label: '20:30 晚间复购' },
          { hour: 22.25, weight: 1.0, isLatest: true, label: '22:15 加班后极速下单' }
        ]
      },
      favorite: {
        projectName: '黑金超光子 + 轮廓紧致',
        beatPercent: '超越 91% 同期男士会员',
        desc: '全年高效到院 4 次 · 控油提亮与下颌线清晰度同步在线',
        clinicalNote: '以 DPL 精准窄谱脉冲改善男士暗沉泛红与出油，搭配无创射频稳固下颌缘线条',
        minimalNote: '全程无创、单次 40 分钟内完成，不耽误次日会议与差旅安排',
        tags: ['男士清爽净肤', '无创紧致', '零恢复期', '高效午休项目'],
        dimensions: [
          { name: '光电提亮', percent: 90 },
          { name: '轮廓紧致', percent: 86 },
          { name: '控油净肤', percent: 78 },
          { name: '屏障舒缓', percent: 52 }
        ],
        top3: [
          { rank: '01', name: 'DPL 黑金超光子全模式', count: '3 次' },
          { rank: '02', name: '男士净肤控油管理', count: '2 次' },
          { rank: '03', name: '无创下颌缘紧致抗衰', count: '1 次' }
        ]
      },
      remainingEntitlements: {
        totalCount: 2,
        expireDate: '2027.09.30',
        items: [
          { name: 'DPL 黑金超光子（男士提亮）', inst: '陆家嘴臻选私享诊所', count: '余 1 次' },
          { name: '院线深层控油净肤护理', inst: '陆家嘴臻选私享诊所', count: '余 1 次' }
        ]
      },
      orders: [
        {
          externalOrderNo: 'E20260224****1903',
          date: '2026.02.24',
          md: '02.24',
          time: '12:10',
          season: '春',
          title: '开年状态焕新 · 黑金超光子季卡',
          category: '光电提亮',
          amountFen: 398000,
          statusText: '余 1 次待约',
          isOpen: true,
          institution: '陆家嘴臻选私享诊所',
          channel: '有赞官方商城',
          skus: [
            { name: 'DPL 黑金超光子全模式', qty: '3 次' }
          ]
        },
        {
          externalOrderNo: 'E20260518****4412',
          date: '2026.05.18',
          md: '05.18',
          time: '20:30',
          season: '夏',
          title: '初夏清爽 · 男士控油净肤双次卡',
          category: '控油净肤',
          amountFen: 182000,
          statusText: '余 1 次待约',
          isOpen: true,
          institution: '陆家嘴臻选私享诊所',
          channel: '有赞官方商城',
          skus: [
            { name: '院线深层控油净肤护理', qty: '2 次' }
          ]
        },
        {
          externalOrderNo: 'E20260829****7185',
          date: '2026.08.29',
          md: '08.29',
          time: '22:15',
          season: '夏',
          title: '商务精英 · 轮廓紧致无创抗衰',
          category: '轮廓紧致',
          amountFen: 680000,
          statusText: '已核销完',
          isOpen: false,
          institution: '陆家嘴臻选私享诊所',
          channel: '有赞专享批次',
          skus: [
            { name: '无创下颌缘紧致射频', qty: '1 次' },
            { name: '术后舒缓修护', qty: '1 次' }
          ]
        },
        {
          externalOrderNo: 'E20261105****9208',
          date: '2026.11.05',
          md: '11.05',
          time: '13:05',
          season: '冬',
          title: '秋冬稳态 · 屏障补水维稳单次',
          category: '屏障舒缓',
          amountFen: 200000,
          statusText: '已核销完',
          isOpen: false,
          institution: '陆家嘴臻选私享诊所',
          channel: '有赞官方商城',
          skus: [
            { name: '舒敏补水修护管理', qty: '1 次' }
          ]
        }
      ]
    }
  };

  const SCHEMA_MAPPING = [
    {
      title: '相识日期 & 相伴天数',
      badge: '可直接拉取',
      isNote: false,
      detail: '取 crm_customer.created_at 与有赞首笔已支付订单 min(order_info.pay_time) 较早值，按 Asia/Shanghai 时区计算天数。'
    },
    {
      title: '哪月哪日买了什么产品（订单轨迹）',
      badge: '可直接拉取',
      isNote: false,
      detail: '来源于已预建的 youzan.trades.sold.get/4.0.2（full_order_info）同步至 crm_order 与冻结 SKU 快照，金额全程整数分计算，扣除成功退款。'
    },
    {
      title: '「深夜几点还在浏览啥」口径说明',
      badge: '务实替代方案',
      isNote: true,
      detail: '有赞标准订单 API 无 C 端个人历史商品浏览日志。推荐零改造口径：采用有赞订单下单/支付时刻（pay_time）与已授权合规归档的企微咨询时间戳；若明年需要“浏览未买”记录，需在小程序/微页面补齐行为埋点（且不回溯历史）。'
    },
    {
      title: '到院核销次数 & 剩余权益提醒',
      badge: 'CRM 独家增值',
      isNote: false,
      detail: '聚合 crm_entitlement_ledger 正式核销流水（仅机构授权操作员确认事件）与当前可用余额，把年终盘点直接转化为春节前预约核销入口。'
    },
    {
      title: '客户点开 H5 如何识别身份与防越权？',
      badge: '合规安全设计',
      isNote: true,
      detail: '禁止在 URL 拼接明文 customerId 或手机号。正式环境通过专属管家下发带时效签名 Token（或微信/有赞静默 OAuth 映射 yz_open_id → customerId），后端只读返回脱敏快照。'
    },
    {
      title: '10,000+ 客户如何批量生成千人千面文案？',
      badge: '分级生成方案',
      isNote: false,
      detail: '采用离线分层预生成（绝不点开时现场调大模型）：S/A 级高净值 VIP 通过离线 Batch API 逐人精写 + 自动事实核验/医美敏感词门禁（10,000 人全量跑轻量模型约 ¥15–¥60）；B/C/D 常规客户走「语调×性别×品类×时段」192+ 组合模板毫秒级零成本生成，发送前支持管家一键微调寄语。'
    }
  ];

  global.JXData = {
    customers: MOCK_CUSTOMERS,
    schemaMapping: SCHEMA_MAPPING,
    formatFenToYuan: formatFenToYuan
  };
})(window);
