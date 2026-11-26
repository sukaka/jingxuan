/**
 * 鲸选美学 2026 年度纸雕灯影纪念海报生成器
 * 纯前端离屏 Canvas2D 渲染 750×1334 高清 PNG（无需后端、微信/桌面一键保存）
 */
(function (global) {
  'use strict';

  function renderKeepsakePoster(customer, hideAmount, resolvedCopy) {
    const copy = resolvedCopy || {
      posterHeading: `${customer.displayName} 的 2026 年度美学人格`,
      personaTitle: customer.personaTitle,
      personaQuote: customer.personaQuote,
      butlerSign: `专属管家 ${customer.butlerName} · 2026 敬寄`
    };
    const W = 750;
    const H = 1334;
    const canvas = document.createElement('canvas');
    canvas.width = W;
    canvas.height = H;
    const ctx = canvas.getContext('2d');

    // 1. 外圈胡桃木灯箱画框
    const woodGrad = ctx.createLinearGradient(0, 0, W, H);
    woodGrad.addColorStop(0, '#2c1d14');
    woodGrad.addColorStop(0.5, '#1b110b');
    woodGrad.addColorStop(1, '#120a06');
    ctx.fillStyle = woodGrad;
    ctx.fillRect(0, 0, W, H);

    // 内框裁切区
    const pad = 34;
    const ix = pad;
    const iy = pad;
    const iw = W - pad * 2;
    const ih = H - pad * 2;

    ctx.save();
    ctx.beginPath();
    ctx.roundRect(ix, iy, iw, ih, 24);
    ctx.clip();

    // 2. 灯箱天空背板
    const sky = ctx.createLinearGradient(0, iy, 0, iy + ih);
    sky.addColorStop(0, '#0c1835');
    sky.addColorStop(0.45, '#335488');
    sky.addColorStop(0.75, '#192a4d');
    sky.addColorStop(1, '#091021');
    ctx.fillStyle = sky;
    ctx.fillRect(ix, iy, iw, ih);

    // 3. 暖琥珀背光与圆月
    const mx = W * 0.5;
    const my = 340;
    const glow = ctx.createRadialGradient(mx, my, 16, mx, my, 430);
    glow.addColorStop(0, 'rgba(255, 224, 152, 0.92)');
    glow.addColorStop(0.32, 'rgba(255, 186, 98, 0.42)');
    glow.addColorStop(0.7, 'rgba(255, 174, 86, 0.1)');
    glow.addColorStop(1, 'rgba(0, 0, 0, 0)');
    ctx.fillStyle = glow;
    ctx.fillRect(ix, iy, iw, ih);

    ctx.save();
    ctx.beginPath();
    ctx.arc(mx, my, 88, 0, Math.PI * 2);
    ctx.fillStyle = 'rgba(255, 244, 218, 0.92)';
    ctx.shadowColor = 'rgba(255, 198, 110, 0.85)';
    ctx.shadowBlur = 42;
    ctx.fill();
    ctx.restore();

    // 4. 分层剪纸海浪 + 纸雕光鲸
    function waveLayer(yBase, amp, color, rimAlpha) {
      ctx.save();
      ctx.shadowColor = 'rgba(3, 6, 16, 0.52)';
      ctx.shadowBlur = 24;
      ctx.shadowOffsetY = -6;
      ctx.beginPath();
      ctx.moveTo(ix - 10, iy + ih + 10);
      ctx.lineTo(ix - 10, yBase);
      ctx.bezierCurveTo(W * 0.25, yBase - amp, W * 0.55, yBase + amp, W + 10, yBase - amp * 0.35);
      ctx.lineTo(W + 10, iy + ih + 10);
      ctx.closePath();
      ctx.fillStyle = color;
      ctx.fill();
      ctx.strokeStyle = `rgba(255, 228, 182, ${rimAlpha})`;
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.restore();
    }

    waveLayer(510, 74, '#5a7fb5', 0.45);
    waveLayer(595, 58, '#304e80', 0.38);

    // 跃动纸雕光鲸剪影
    ctx.save();
    ctx.translate(W * 0.5, 428);
    ctx.scale(1.55, 1.55);
    ctx.shadowColor = 'rgba(5, 10, 26, 0.58)';
    ctx.shadowBlur = 22;
    ctx.beginPath();
    ctx.moveTo(-112, 22);
    ctx.bezierCurveTo(-92, -36, -18, -68, 56, -34);
    ctx.bezierCurveTo(96, -16, 122, 16, 134, 34);
    ctx.bezierCurveTo(154, 18, 174, -4, 186, -12);
    ctx.bezierCurveTo(176, 14, 164, 30, 150, 40);
    ctx.bezierCurveTo(166, 52, 178, 66, 182, 78);
    ctx.bezierCurveTo(162, 70, 142, 58, 126, 46);
    ctx.bezierCurveTo(86, 54, 36, 42, 6, 32);
    ctx.bezierCurveTo(-6, 52, -26, 66, -42, 68);
    ctx.bezierCurveTo(-36, 46, -32, 34, -34, 26);
    ctx.bezierCurveTo(-68, 32, -98, 34, -112, 22);
    ctx.closePath();
    ctx.fillStyle = '#1b315c';
    ctx.fill();
    ctx.strokeStyle = 'rgba(255, 232, 188, 0.6)';
    ctx.lineWidth = 1.6;
    ctx.stroke();

    // 鲸身星光孔
    [[-56, -10, 4.5], [-20, -24, 5.5], [18, -20, 4], [52, -8, 4.5]].forEach(([sx, sy, sr]) => {
      ctx.fillStyle = '#fff3d4';
      ctx.beginPath();
      ctx.arc(sx, sy, sr, 0, Math.PI * 2);
      ctx.fill();
    });
    ctx.restore();

    waveLayer(675, 52, '#162749', 0.3);
    waveLayer(760, 40, '#0a1226', 0.22);

    // 5. 品牌抬头与印章
    ctx.fillStyle = '#c94635';
    ctx.beginPath();
    ctx.roundRect(78, 86, 44, 44, 8);
    ctx.fill();
    ctx.fillStyle = '#fff6e6';
    ctx.font = 'bold 24px "Songti SC", "Noto Serif SC", Georgia, serif';
    ctx.textAlign = 'center';
    ctx.fillText('鲸', 100, 117);

    ctx.textAlign = 'left';
    ctx.fillStyle = '#fdf4e3';
    ctx.font = '600 24px "Songti SC", "Noto Serif SC", Georgia, serif';
    ctx.fillText('鲸选美学', 136, 108);
    ctx.fillStyle = 'rgba(253, 244, 227, 0.65)';
    ctx.font = '14px Menlo, Consolas, monospace';
    ctx.fillText('WHALE AESTHETICS · 2026 ANNUAL KEEPSAKE', 136, 129);

    // 右上角等级印章
    ctx.fillStyle = 'rgba(201, 70, 53, 0.9)';
    ctx.beginPath();
    ctx.roundRect(W - 186, 90, 108, 36, 6);
    ctx.fill();
    ctx.fillStyle = '#fff6e8';
    ctx.textAlign = 'center';
    ctx.font = '600 18px "Songti SC", "Noto Serif SC", serif';
    ctx.fillText(customer.sealText, W - 132, 115);

    // 6. 底部纪念信息卡片（确保深色衬底超高对比度）
    const cardX = 68;
    const cardY = 670;
    const cardW = W - 136;
    const cardH = 548;
    ctx.save();
    ctx.fillStyle = 'rgba(11, 16, 30, 0.86)';
    ctx.strokeStyle = 'rgba(255, 218, 158, 0.45)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.roundRect(cardX, cardY, cardW, cardH, 22);
    ctx.fill();
    ctx.stroke();
    ctx.restore();

    ctx.textAlign = 'center';
    ctx.fillStyle = 'rgba(255, 228, 184, 0.78)';
    ctx.font = '22px "Songti SC", "Noto Serif SC", serif';
    ctx.fillText(copy.posterHeading, W / 2, cardY + 62);

    ctx.fillStyle = '#ffe3ab';
    ctx.font = 'bold 46px "Songti SC", "Noto Serif SC", serif';
    ctx.fillText(copy.personaTitle, W / 2, cardY + 124);

    // 金色细分割线
    const lineGrad = ctx.createLinearGradient(cardX + 60, 0, cardX + cardW - 60, 0);
    lineGrad.addColorStop(0, 'rgba(255, 214, 148, 0)');
    lineGrad.addColorStop(0.5, 'rgba(255, 214, 148, 0.65)');
    lineGrad.addColorStop(1, 'rgba(255, 214, 148, 0)');
    ctx.strokeStyle = lineGrad;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(cardX + 60, cardY + 150);
    ctx.lineTo(cardX + cardW - 60, cardY + 150);
    ctx.stroke();

    // 折行文案（微调字号与宽度避免孤字换行）
    ctx.fillStyle = 'rgba(253, 244, 228, 0.88)';
    ctx.font = '20px "Songti SC", "Noto Serif SC", serif';
    const quoteLines = wrapChineseText(ctx, copy.personaQuote, cardW - 60);
    quoteLines.slice(0, 3).forEach((line, idx) => {
      ctx.fillText(line, W / 2, cardY + 194 + idx * 34);
    });

    // 4 格核心轨迹数据
    const metrics = [
      { label: '相伴鲸选', value: `${customer.daysTogether} 天` },
      { label: '全年订单 / 到院', value: `${customer.orders.length} 笔 · ${customer.redeemCount} 次` },
      { label: '年度挚爱项目', value: customer.favorite.projectName },
      { label: '年度焕美投入', value: global.JXData.formatFenToYuan(customer.netSpendFen, hideAmount) }
    ];

    const boxW = (cardW - 66) / 2;
    const boxH = 88;
    metrics.forEach((m, idx) => {
      const col = idx % 2;
      const row = Math.floor(idx / 2);
      const bx = cardX + 24 + col * (boxW + 18);
      const by = cardY + 292 + row * (boxH + 14);

      ctx.fillStyle = 'rgba(255, 224, 178, 0.08)';
      ctx.strokeStyle = 'rgba(255, 218, 162, 0.22)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.roundRect(bx, by, boxW, boxH, 14);
      ctx.fill();
      ctx.stroke();

      ctx.textAlign = 'left';
      ctx.fillStyle = 'rgba(253, 236, 210, 0.65)';
      ctx.font = '18px -apple-system, "PingFang SC", sans-serif';
      ctx.fillText(m.label, bx + 18, by + 32);

      ctx.fillStyle = '#ffe5b0';
      ctx.font = 'bold 23px Menlo, "Songti SC", serif';
      ctx.fillText(m.value, bx + 18, by + 68);
    });

    // 底部落款
    ctx.textAlign = 'left';
    ctx.fillStyle = '#ffd99c';
    ctx.font = '20px "Songti SC", "Noto Serif SC", serif';
    ctx.fillText(copy.butlerSign, cardX + 24, cardY + cardH - 22);

    ctx.textAlign = 'right';
    ctx.fillStyle = 'rgba(253, 240, 216, 0.56)';
    ctx.font = '16px Menlo, monospace';
    ctx.fillText(`最晚光影 ${customer.midnight.time} · ${customer.memberNo}`, cardX + cardW - 24, cardY + cardH - 22);

    ctx.restore();

    // 金色细内边框
    ctx.strokeStyle = 'rgba(255, 218, 162, 0.28)';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.roundRect(ix, iy, iw, ih, 24);
    ctx.stroke();

    return canvas.toDataURL('image/png');
  }

  function wrapChineseText(ctx, text, maxWidth) {
    const lines = [];
    let current = '';
    for (const ch of text) {
      const next = current + ch;
      if (ctx.measureText(next).width > maxWidth && current) {
        lines.push(current);
        current = ch;
      } else {
        current = next;
      }
    }
    if (current) lines.push(current);
    return lines;
  }

  global.JXPoster = {
    renderKeepsakePoster: renderKeepsakePoster
  };
})(window);
