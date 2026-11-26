/**
 * Paper-cut Lightbox (纸雕灯影) Canvas2D 实时渲染引擎
 *
 * 严格遵循 Lemo-Opuscar styles/paper-lantern/STYLE.md：
 * 1. 每层剪纸具备独立色彩明度梯队：背层最透亮 → 前层深邃近黑；
 * 2. 程序化纸浆纤维 & 云雾透光质感（pulp mottling）；
 * 3. 每层上缘暖光高光（top rim）、下缘投影暗带（cast shadow）；
 * 4. 高斯暖琥珀背光 + 呼吸微闪烁 + 镂空发光窗格/星点（bloom）；
 * 5. 支持 6 幕色温梯队平滑过渡、点亮仪式渐进透光（The lamp turns on）与手势/鼠标景深视差。
 */
(function (global) {
  'use strict';

  // 6 幕对应的纸雕色阶梯（严格由远及近明度递减）
  const SCENE_THEMES = [
    {
      // 第 1 幕：暮蓝鲸海
      id: 'dawn-whale',
      cssBg: '#091124',
      accent: '#ffb862',
      accentSoft: 'rgba(255, 184, 98, 0.24)',
      cardBg: 'rgba(8, 16, 36, 0.72)',
      cardBorder: 'rgba(255, 224, 174, 0.26)',
      rim: 'rgba(255, 224, 170, 0.5)',
      spill: 'rgba(255, 184, 98, 0.26)',
      skyTop: '#0b1836',
      skyBottom: '#2d4d80',
      lightPos: [0.76, 0.27],
      lightColor: [255, 198, 114],
      layers: ['#7fa1d4', '#4d73ab', '#2a4779', '#16294d', '#091124'],
      motif: 'whale'
    },
    {
      // 第 2 幕：暖杏流金（四季购买轨迹）
      id: 'four-seasons',
      cssBg: '#190c08',
      accent: '#ffcf86',
      accentSoft: 'rgba(255, 207, 134, 0.24)',
      cardBg: 'rgba(28, 14, 9, 0.72)',
      cardBorder: 'rgba(255, 224, 178, 0.24)',
      rim: 'rgba(255, 232, 188, 0.46)',
      spill: 'rgba(255, 176, 92, 0.28)',
      skyTop: '#4a2315',
      skyBottom: '#e9b77c',
      lightPos: [0.8, 0.19],
      lightColor: [255, 218, 146],
      layers: ['#d49762', '#a35f39', '#6c3821', '#3d1d12', '#190c08'],
      motif: 'seasons'
    },
    {
      // 第 3 幕：午夜窗灯（深夜光影时刻）
      id: 'midnight-window',
      cssBg: '#080e1f',
      accent: '#ffb862',
      accentSoft: 'rgba(255, 184, 98, 0.28)',
      cardBg: 'rgba(9, 15, 34, 0.74)',
      cardBorder: 'rgba(255, 216, 154, 0.26)',
      rim: 'rgba(255, 220, 158, 0.5)',
      spill: 'rgba(255, 170, 74, 0.25)',
      skyTop: '#070e24',
      skyBottom: '#2a4173',
      lightPos: [0.82, 0.17],
      lightColor: [255, 184, 98],
      layers: ['#6c88c2', '#415d94', '#243764', '#132040', '#080e1f'],
      motif: 'midnight'
    },
    {
      // 第 4 幕：绯樱薄暮（年度挚爱项目）
      id: 'beloved-ritual',
      cssBg: '#1a0a12',
      accent: '#ffd09c',
      accentSoft: 'rgba(255, 208, 156, 0.24)',
      cardBg: 'rgba(30, 12, 22, 0.72)',
      cardBorder: 'rgba(255, 218, 192, 0.26)',
      rim: 'rgba(255, 226, 200, 0.48)',
      spill: 'rgba(235, 134, 148, 0.26)',
      skyTop: '#4c1e31',
      skyBottom: '#eab3be',
      lightPos: [0.76, 0.44],
      lightColor: [255, 214, 174],
      layers: ['#cc8698', '#9a5268', '#662f43', '#3b1826', '#1a0a12'],
      motif: 'petals'
    },
    {
      // 第 5 幕：翡翠深海（履约核销与权益宝库，呼应鲸选品牌青 #0f948b）
      id: 'fulfillment-vault',
      cssBg: '#051619',
      accent: '#82e6dc',
      accentSoft: 'rgba(130, 230, 220, 0.22)',
      cardBg: 'rgba(6, 26, 29, 0.74)',
      cardBorder: 'rgba(154, 234, 224, 0.26)',
      rim: 'rgba(202, 248, 242, 0.46)',
      spill: 'rgba(32, 186, 174, 0.26)',
      skyTop: '#072a2e',
      skyBottom: '#5db7af',
      lightPos: [0.5, 0.30],
      lightColor: [255, 226, 162],
      layers: ['#62b8b0', '#2b837d', '#155252', '#0b2f32', '#051619'],
      motif: 'vault'
    },
    {
      // 第 6 幕：私享灯箱（框景回拉）
      id: 'keepsake-box',
      cssBg: '#0c1222',
      accent: '#ffcf86',
      accentSoft: 'rgba(255, 207, 134, 0.28)',
      cardBg: 'rgba(14, 16, 26, 0.8)',
      cardBorder: 'rgba(255, 218, 158, 0.34)',
      rim: 'rgba(255, 232, 188, 0.56)',
      spill: 'rgba(255, 186, 98, 0.36)',
      skyTop: '#121c38',
      skyBottom: '#566b96',
      lightPos: [0.76, 0.61],
      lightColor: [255, 204, 122],
      layers: ['#8ba2cc', '#576f9e', '#30436b', '#192542', '#0b1020'],
      motif: 'keepsake'
    }
  ];

  // 创建可复用的纸浆纤维离屏贴图
  function createPaperGrainPattern() {
    const c = document.createElement('canvas');
    c.width = 160;
    c.height = 160;
    const g = c.getContext('2d');
    const img = g.createImageData(160, 160);
    let seed = 4219;
    function rand() {
      seed = (seed * 16807) % 2147483647;
      return (seed - 1) / 2147483646;
    }
    for (let i = 0; i < img.data.length; i += 4) {
      const n = 128 + (rand() - 0.5) * 44;
      img.data[i] = n;
      img.data[i + 1] = n - 2;
      img.data[i + 2] = n - 6;
      img.data[i + 3] = 22;
    }
    g.putImageData(img, 0, 0);
    // 细微棉纸纤维丝
    g.strokeStyle = 'rgba(255, 244, 224, 0.08)';
    g.lineWidth = 0.65;
    for (let i = 0; i < 65; i++) {
      const x = rand() * 160;
      const y = rand() * 160;
      const len = 4 + rand() * 10;
      const ang = rand() * Math.PI * 2;
      g.beginPath();
      g.moveTo(x, y);
      g.quadraticCurveTo(
        x + Math.cos(ang + 0.4) * len * 0.5,
        y + Math.sin(ang + 0.4) * len * 0.5,
        x + Math.cos(ang) * len,
        y + Math.sin(ang) * len
      );
      g.stroke();
    }
    return c;
  }

  function hexToRgb(hex) {
    const clean = hex.replace('#', '');
    return [
      parseInt(clean.slice(0, 2), 16),
      parseInt(clean.slice(2, 4), 16),
      parseInt(clean.slice(4, 6), 16)
    ];
  }

  function lerp(a, b, t) {
    return a + (b - a) * t;
  }

  function lerpColor(hexA, hexB, t) {
    const a = hexToRgb(hexA);
    const b = hexToRgb(hexB);
    const r = Math.round(lerp(a[0], b[0], t));
    const g = Math.round(lerp(a[1], b[1], t));
    const bl = Math.round(lerp(a[2], b[2], t));
    return `rgb(${r},${g},${bl})`;
  }

  function smoothstep(edge0, edge1, x) {
    const t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
    return t * t * (3 - 2 * t);
  }

  class PaperLightboxEngine {
    constructor(canvas) {
      this.canvas = canvas;
      this.ctx = canvas.getContext('2d');
      this.grainCanvas = createPaperGrainPattern();
      this.grainPattern = null;

      this.width = 390;
      this.height = 790;
      this.dpr = Math.min(window.devicePixelRatio || 1, 2);

      this.sceneIndex = 0;
      this.prevSceneIndex = 0;
      this.sceneMix = 1;
      this.sceneEnterAt = performance.now();

      // 灯箱点亮程度：初始第 1 幕未点击时为微弱星光态（0.28），点击后缓缓点亮至 1.0
      this.lampLit = 0.28;
      this.lampTarget = 0.28;

      // 景深视差目标与阻尼当前值
      this.pxTarget = 0;
      this.pyTarget = 0;
      this.px = 0;
      this.py = 0;

      // 暖金微光尘埃粒子
      this.motes = Array.from({ length: 26 }, (_, i) => ({
        x: ((i * 37) % 100) / 100,
        y: ((i * 53 + 17) % 100) / 100,
        r: 1 + (i % 3) * 0.75,
        speed: 0.008 + (i % 5) * 0.004,
        phase: i * 1.13
      }));

      this._raf = null;
      this._boundResize = this.resize.bind(this);
      this.resize();
      window.addEventListener('resize', this._boundResize);
      this.applyCssTheme(0);
    }

    resize() {
      const rect = this.canvas.getBoundingClientRect();
      this.width = Math.max(300, Math.round(rect.width || 390));
      this.height = Math.max(500, Math.round(rect.height || 790));
      this.dpr = Math.min(window.devicePixelRatio || 1, 2);
      this.canvas.width = Math.round(this.width * this.dpr);
      this.canvas.height = Math.round(this.height * this.dpr);
      this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
      this.grainPattern = this.ctx.createPattern(this.grainCanvas, 'repeat');
    }

    setScene(index) {
      if (index === this.sceneIndex) return;
      this.prevSceneIndex = this.sceneIndex;
      this.sceneIndex = index;
      this.sceneMix = 0;
      this.sceneEnterAt = performance.now();
      if (index > 0) this.lampTarget = 1;
      this.applyCssTheme(index);
    }

    igniteLamp() {
      this.lampTarget = 1;
      this.sceneEnterAt = performance.now();
    }

    setParallax(nx, ny) {
      this.pxTarget = Math.max(-1, Math.min(1, nx));
      this.pyTarget = Math.max(-1, Math.min(1, ny));
    }

    applyCssTheme(index) {
      const th = SCENE_THEMES[index] || SCENE_THEMES[0];
      const root = document.documentElement.style;
      root.setProperty('--scene-bg', th.cssBg);
      root.setProperty('--scene-accent', th.accent);
      root.setProperty('--scene-accent-soft', th.accentSoft);
      root.setProperty('--scene-card-bg', th.cardBg);
      root.setProperty('--scene-card-border', th.cardBorder);
      root.setProperty('--scene-rim', th.rim);
      root.setProperty('--scene-spill', th.spill);
    }

    start() {
      if (this._raf) return;
      const tick = (now) => {
        this.render(now);
        this._raf = requestAnimationFrame(tick);
      };
      this._raf = requestAnimationFrame(tick);
    }

    stop() {
      if (this._raf) {
        cancelAnimationFrame(this._raf);
        this._raf = null;
      }
    }

    // 绘制一片具备真实纸雕工艺的剪纸层（顶部暖光缘 + 实体阴影 + 纸纤维肌理）
    drawPaperLayer(pathFn, fillColor, rimColor, shadowAlpha, transFactor, lx, ly, lit) {
      const ctx = this.ctx;
      const w = this.width;
      const h = this.height;

      // 1. 柔和投影（每一层向后投影，纸雕灯箱的立体灵魂）
      ctx.save();
      ctx.shadowColor = `rgba(3, 5, 12, ${shadowAlpha})`;
      ctx.shadowBlur = 22;
      ctx.shadowOffsetY = -4;
      pathFn(ctx, w, h);
      ctx.fillStyle = fillColor;
      ctx.fill();
      ctx.restore();

      // 2. 纸内背光透射 + 纸浆纤维（剪裁在当前剪纸层内部）
      ctx.save();
      pathFn(ctx, w, h);
      ctx.clip();

      // 背光透射光晕（远层 transFactor 高、近层低）
      if (transFactor > 0.02 && lit > 0.05) {
        const radius = w * (0.65 + transFactor * 0.45);
        const grad = ctx.createRadialGradient(lx, ly, 6, lx, ly, radius);
        grad.addColorStop(0, `rgba(255, 224, 162, ${0.42 * transFactor * lit})`);
        grad.addColorStop(0.55, `rgba(255, 194, 118, ${0.14 * transFactor * lit})`);
        grad.addColorStop(1, 'rgba(255, 184, 98, 0)');
        ctx.fillStyle = grad;
        ctx.fillRect(0, 0, w, h);
      }

      // 底部阴影渐暗带（STYLE.md §1 dark band on bottom edge）
      const shadeGrad = ctx.createLinearGradient(0, h * 0.45, 0, h);
      shadeGrad.addColorStop(0, 'rgba(0,0,0,0)');
      shadeGrad.addColorStop(1, 'rgba(3, 5, 12, 0.34)');
      ctx.fillStyle = shadeGrad;
      ctx.fillRect(0, 0, w, h);

      // 纸浆纤维纹理
      if (this.grainPattern) {
        ctx.fillStyle = this.grainPattern;
        ctx.fillRect(0, 0, w, h);
      }
      ctx.restore();

      // 3. 上缘暖光描边（光线掠过 300g 纸张切口的高光细边）
      ctx.save();
      ctx.translate(0, -1.2);
      pathFn(ctx, w, h);
      ctx.strokeStyle = rimColor;
      ctx.lineWidth = 1.4;
      ctx.stroke();
      ctx.restore();
    }

    // 绘制跃动纸雕光鲸（第 1 幕 & 第 5/6 幕标志符号）
    drawPaperWhale(cx, cy, scale, fillColor, rimColor, lit, t) {
      const ctx = this.ctx;
      const bob = Math.sin(t * 1.1) * 5;
      ctx.save();
      ctx.translate(cx, cy + bob);
      ctx.scale(scale, scale);

      const whalePath = () => {
        ctx.beginPath();
        // 优雅跃起的鲸背与流线尾鳍
        ctx.moveTo(-112, 22);
        ctx.bezierCurveTo(-92, -36, -18, -68, 56, -34);
        ctx.bezierCurveTo(96, -16, 122, 16, 134, 34);
        // 尾鳍上叶
        ctx.bezierCurveTo(154, 18, 174, -4, 186, -12);
        ctx.bezierCurveTo(176, 14, 164, 30, 150, 40);
        // 尾鳍下叶
        ctx.bezierCurveTo(166, 52, 178, 66, 182, 78);
        ctx.bezierCurveTo(162, 70, 142, 58, 126, 46);
        // 腹部曲线与胸鳍
        ctx.bezierCurveTo(86, 54, 36, 42, 6, 32);
        ctx.bezierCurveTo(-6, 52, -26, 66, -42, 68);
        ctx.bezierCurveTo(-36, 46, -32, 34, -34, 26);
        ctx.bezierCurveTo(-68, 32, -98, 34, -112, 22);
        ctx.closePath();
      };

      // 实体投影
      ctx.save();
      ctx.shadowColor = 'rgba(4, 8, 22, 0.56)';
      ctx.shadowBlur = 18;
      ctx.shadowOffsetY = 8;
      whalePath();
      ctx.fillStyle = fillColor;
      ctx.fill();
      ctx.restore();

      // 顶部纸切口暖光缘
      ctx.save();
      ctx.translate(0, -1.2);
      whalePath();
      ctx.strokeStyle = rimColor;
      ctx.lineWidth = 1.6;
      ctx.stroke();
      ctx.restore();

      // 鲸身星芒镂空孔（透出温暖琥珀光）
      const stars = [
        [-58, -10, 4.2],
        [-24, -24, 5.4],
        [14, -22, 3.8],
        [48, -10, 4.6],
        [82, 8, 3.2]
      ];
      stars.forEach(([sx, sy, sr], idx) => {
        const pulse = 0.65 + 0.35 * Math.sin(t * 2.4 + idx * 1.1);
        const alpha = lit * pulse;
        ctx.save();
        const glow = ctx.createRadialGradient(sx, sy, 0.5, sx, sy, sr * 4.5);
        glow.addColorStop(0, `rgba(255, 240, 200, ${0.95 * alpha})`);
        glow.addColorStop(0.35, `rgba(255, 194, 102, ${0.55 * alpha})`);
        glow.addColorStop(1, 'rgba(255, 184, 98, 0)');
        ctx.fillStyle = glow;
        ctx.beginPath();
        ctx.arc(sx, sy, sr * 4.5, 0, Math.PI * 2);
        ctx.fill();

        // 四芒星镂空
        ctx.fillStyle = `rgba(255, 246, 220, ${alpha})`;
        ctx.beginPath();
        ctx.moveTo(sx, sy - sr);
        ctx.quadraticCurveTo(sx, sy, sx + sr, sy);
        ctx.quadraticCurveTo(sx, sy, sx, sy + sr);
        ctx.quadraticCurveTo(sx, sy, sx - sr, sy);
        ctx.quadraticCurveTo(sx, sy, sx, sy - sr);
        ctx.fill();
        ctx.restore();
      });

      ctx.restore();
    }

    // 绘制午夜城市纸雕天际线与一扇独亮暖窗（第 3 幕专属）
    drawMidnightCity(ox, oy, layerColor, rimColor, lit, elapsed) {
      const ctx = this.ctx;
      const w = this.width;
      const h = this.height;
      const baseY = h * 0.72 + oy;

      const buildings = [
        { x: -16, bw: 62, bh: 155, arch: false },
        { x: 42, bw: 54, bh: 118, arch: true },
        { x: 92, bw: 68, bh: 182, arch: false, hero: true }, // 主角灯窗所在楼
        { x: 158, bw: 56, bh: 136, arch: false },
        { x: 210, bw: 72, bh: 168, arch: true },
        { x: 278, bw: 60, bh: 128, arch: false },
        { x: 334, bw: 78, bh: 170, arch: false }
      ];

      this.drawPaperLayer((c) => {
        c.beginPath();
        c.moveTo(-24, h + 20);
        buildings.forEach((b) => {
          const bx = b.x + ox;
          const topY = baseY - b.bh;
          c.lineTo(bx, topY + (b.arch ? 16 : 0));
          if (b.arch) {
            c.quadraticCurveTo(bx + b.bw * 0.5, topY - 12, bx + b.bw, topY + 16);
          } else {
            c.lineTo(bx + b.bw, topY);
          }
          c.lineTo(bx + b.bw, baseY);
        });
        c.lineTo(w + 24, h + 20);
        c.closePath();
      }, layerColor, rimColor, 0.55, 0.16, w * 0.5, h * 0.34, lit);

      // 镂空发光窗格（按入场时间逐一亮起，凸显 23:47 独亮之窗）
      const windows = [
        { x: 116, y: baseY - 148, ww: 18, wh: 26, delay: 0.15, hero: true },
        { x: 14, y: baseY - 102, ww: 10, wh: 14, delay: 0.45, hero: false },
        { x: 238, y: baseY - 122, ww: 11, wh: 16, delay: 0.68, hero: false },
        { x: 300, y: baseY - 88, ww: 9, wh: 13, delay: 0.9, hero: false }
      ];

      windows.forEach((win) => {
        const on = smoothstep(win.delay, win.delay + 0.5, elapsed) * lit;
        if (on <= 0.01) return;
        const wx = win.x + ox;
        const wy = win.y;
        ctx.save();
        // 窗格向外弥散的暖琥珀 Bloom
        const glowR = win.hero ? 58 : 26;
        const g = ctx.createRadialGradient(
          wx + win.ww / 2, wy + win.wh / 2, 2,
          wx + win.ww / 2, wy + win.wh / 2, glowR
        );
        g.addColorStop(0, `rgba(255, 224, 152, ${(win.hero ? 0.72 : 0.34) * on})`);
        g.addColorStop(0.5, `rgba(255, 178, 86, ${(win.hero ? 0.28 : 0.12) * on})`);
        g.addColorStop(1, 'rgba(255, 170, 74, 0)');
        ctx.fillStyle = g;
        ctx.beginPath();
        ctx.arc(wx + win.ww / 2, wy + win.wh / 2, glowR, 0, Math.PI * 2);
        ctx.fill();

        // 拱形窗孔本体
        ctx.fillStyle = win.hero
          ? `rgba(255, 236, 186, ${0.95 * on})`
          : `rgba(255, 206, 128, ${0.65 * on})`;
        ctx.beginPath();
        ctx.roundRect(wx, wy, win.ww, win.wh, [win.ww / 2, win.ww / 2, 2, 2]);
        ctx.fill();
        ctx.restore();
      });
    }

    render(now) {
      const ctx = this.ctx;
      const w = this.width;
      const h = this.height;
      const t = now * 0.001;
      const elapsed = (now - this.sceneEnterAt) * 0.001;

      // 平滑过渡章节色彩与点亮状态
      this.sceneMix = Math.min(1, this.sceneMix + 0.045);
      const mix = smoothstep(0, 1, this.sceneMix);
      this.lampLit += (this.lampTarget - this.lampLit) * 0.055;

      // 景深视差阻尼
      this.px += (this.pxTarget - this.px) * 0.08;
      this.py += (this.pyTarget - this.py) * 0.08;

      const prev = SCENE_THEMES[this.prevSceneIndex] || SCENE_THEMES[0];
      const curr = SCENE_THEMES[this.sceneIndex] || SCENE_THEMES[0];

      // 微呼吸灯火闪烁（STYLE.md §5: 1 + 0.06 * sin * sin）
      const flicker = 1 + 0.045 * Math.sin(5.1 * t) * Math.sin(2.3 * t);
      const lit = this.lampLit * flicker;

      const lx = w * lerp(prev.lightPos[0], curr.lightPos[0], mix) + this.px * 8;
      const ly = h * lerp(prev.lightPos[1], curr.lightPos[1], mix) + this.py * 6;

      // 1. 天空背板（最远层，透光基底）
      const skyGrad = ctx.createLinearGradient(0, 0, 0, h);
      skyGrad.addColorStop(0, lerpColor(prev.skyTop, curr.skyTop, mix));
      skyGrad.addColorStop(0.68, lerpColor(prev.skyBottom, curr.skyBottom, mix));
      skyGrad.addColorStop(1, lerpColor(prev.cssBg, curr.cssBg, mix));
      ctx.fillStyle = skyGrad;
      ctx.fillRect(0, 0, w, h);

      // 2. 纸雕核心暖背光（高斯衰减 + 满月光轮）
      const lr = Math.round(lerp(prev.lightColor[0], curr.lightColor[0], mix));
      const lg = Math.round(lerp(prev.lightColor[1], curr.lightColor[1], mix));
      const lb = Math.round(lerp(prev.lightColor[2], curr.lightColor[2], mix));

      const backGlow = ctx.createRadialGradient(lx, ly, 8, lx, ly, w * 0.92);
      backGlow.addColorStop(0, `rgba(${lr}, ${lg}, ${lb}, ${0.88 * lit})`);
      backGlow.addColorStop(0.32, `rgba(${lr}, ${lg}, ${lb}, ${0.38 * lit})`);
      backGlow.addColorStop(0.7, `rgba(${lr}, ${lg}, ${lb}, ${0.1 * lit})`);
      backGlow.addColorStop(1, 'rgba(0, 0, 0, 0)');
      ctx.fillStyle = backGlow;
      ctx.fillRect(0, 0, w, h);

      // 镂空月盘（按章节设定合适半径，严格避让文字区域）
      const moonR = this.sceneIndex === 2 ? 22 : (this.sceneIndex === 1 || this.sceneIndex === 3 ? 28 : 42);
      ctx.save();
      ctx.beginPath();
      ctx.arc(lx, ly, moonR * (0.85 + 0.15 * lit), 0, Math.PI * 2);
      ctx.fillStyle = `rgba(255, 245, 222, ${0.85 * lit})`;
      ctx.shadowColor = `rgba(${lr}, ${lg}, ${lb}, ${0.9 * lit})`;
      ctx.shadowBlur = 32;
      ctx.fill();
      ctx.restore();

      // 3. 逐层剪纸山峦/云海/花影/城市（由远及近，明度递减）
      const colors = [0, 1, 2, 3, 4].map((i) => lerpColor(prev.layers[i], curr.layers[i], mix));
      const rimBase = `rgba(255, 230, 184, ${0.42 * lit})`;

      // Layer 0（最远云山/浪涛，透光最高 trans=0.85）
      const ox0 = this.px * 4;
      const oy0 = this.py * 3;
      this.drawPaperLayer((c) => {
        c.beginPath();
        c.moveTo(-20, h + 20);
        c.lineTo(-20, h * 0.44 + oy0);
        c.bezierCurveTo(w * 0.22 + ox0, h * 0.35 + oy0, w * 0.46 + ox0, h * 0.48 + oy0, w * 0.72 + ox0, h * 0.38 + oy0);
        c.bezierCurveTo(w * 0.88 + ox0, h * 0.33 + oy0, w + 10, h * 0.41 + oy0, w + 20, h * 0.44 + oy0);
        c.lineTo(w + 20, h + 20);
        c.closePath();
      }, colors[0], rimBase, 0.28, 0.82, lx, ly, lit);

      // Layer 1（中远景山岚/云层）
      const ox1 = this.px * 9;
      const oy1 = this.py * 5;
      this.drawPaperLayer((c) => {
        c.beginPath();
        c.moveTo(-24, h + 20);
        c.lineTo(-24, h * 0.54 + oy1);
        c.bezierCurveTo(w * 0.18 + ox1, h * 0.46 + oy1, w * 0.36 + ox1, h * 0.58 + oy1, w * 0.62 + ox1, h * 0.49 + oy1);
        c.bezierCurveTo(w * 0.82 + ox1, h * 0.44 + oy1, w * 0.94 + ox1, h * 0.52 + oy1, w + 24, h * 0.54 + oy1);
        c.lineTo(w + 24, h + 20);
        c.closePath();
      }, colors[1], `rgba(255, 224, 172, ${0.38 * lit})`, 0.36, 0.58, lx, ly, lit);

      // 主角中景剪纸（按当前幕切换意象）
      if (this.sceneIndex === 0 || this.sceneIndex === 4 || this.sceneIndex === 5) {
        const whaleScale = this.sceneIndex === 0 ? 0.92 : (this.sceneIndex === 5 ? 0.8 : 0.78);
        const whaleY = this.sceneIndex === 0 ? h * 0.44 : (this.sceneIndex === 5 ? h * 0.64 : h * 0.39);
        const whaleX = this.sceneIndex === 5 ? w * 0.48 : w * 0.52;
        this.drawPaperWhale(
          whaleX + this.px * 14,
          whaleY + this.py * 8,
          whaleScale,
          colors[2],
          `rgba(255, 232, 186, ${0.58 * lit})`,
          lit,
          t
        );
      } else if (this.sceneIndex === 2) {
        this.drawMidnightCity(this.px * 13, this.py * 7, colors[3], `rgba(255, 212, 148, ${0.32 * lit})`, lit, elapsed);
      }

      // Layer 2（中景叠浪/花枝剪影）
      const ox2 = this.px * 15;
      const oy2 = this.py * 8;
      this.drawPaperLayer((c) => {
        c.beginPath();
        c.moveTo(-30, h + 20);
        c.lineTo(-30, h * 0.66 + oy2);
        c.bezierCurveTo(w * 0.25 + ox2, h * 0.58 + oy2, w * 0.52 + ox2, h * 0.72 + oy2, w * 0.8 + ox2, h * 0.62 + oy2);
        c.bezierCurveTo(w * 0.92 + ox2, h * 0.58 + oy2, w + 16, h * 0.64 + oy2, w + 30, h * 0.66 + oy2);
        c.lineTo(w + 30, h + 20);
        c.closePath();
      }, colors[2], `rgba(255, 220, 162, ${0.34 * lit})`, 0.44, 0.36, lx, ly, lit);

      // Layer 3（近景剪纸拱檐与侧翼花影）
      const ox3 = this.px * 21;
      const oy3 = this.py * 11;
      this.drawPaperLayer((c) => {
        c.beginPath();
        c.moveTo(-32, h + 24);
        c.lineTo(-32, h * 0.77 + oy3);
        c.bezierCurveTo(w * 0.2 + ox3, h * 0.72 + oy3, w * 0.48 + ox3, h * 0.83 + oy3, w * 0.74 + ox3, h * 0.75 + oy3);
        c.bezierCurveTo(w * 0.9 + ox3, h * 0.71 + oy3, w + 18, h * 0.75 + oy3, w + 32, h * 0.77 + oy3);
        c.lineTo(w + 32, h + 24);
        c.closePath();
      }, colors[3], `rgba(255, 214, 152, ${0.28 * lit})`, 0.52, 0.16, lx, ly, lit);

      // Layer 4（最前景深暗框幕：拱形镂空窗格边框，牢牢锁住文字底部对比度）
      const ox4 = this.px * 26;
      const oy4 = this.py * 12;
      this.drawPaperLayer((c) => {
        c.beginPath();
        // 外圈大框
        c.rect(-36, -36, w + 72, h + 72);
        // 内部拱形镂空（逆时针挖空）
        const padX = 16;
        const archTop = 76 + oy4 * 0.3;
        const archBottom = h - 72 + oy4 * 0.3;
        c.moveTo(padX + ox4 * 0.25, archBottom);
        c.lineTo(w - padX + ox4 * 0.25, archBottom);
        c.lineTo(w - padX + ox4 * 0.25, archTop + 88);
        c.bezierCurveTo(
          w - padX + ox4 * 0.25, archTop,
          padX + ox4 * 0.25, archTop,
          padX + ox4 * 0.25, archTop + 88
        );
        c.closePath();
      }, colors[4], `rgba(255, 218, 160, ${0.22 * lit})`, 0.62, 0.04, lx, ly, lit);

      // 4. 漂浮的暖金微光尘埃（如灯箱空气中的光粒）
      ctx.save();
      this.motes.forEach((m) => {
        const y = ((m.y - t * m.speed) % 1 + 1) % 1;
        const x = m.x + Math.sin(t * 0.7 + m.phase) * 0.03;
        const alpha = (0.2 + 0.55 * Math.abs(Math.sin(t * 1.4 + m.phase))) * lit;
        const mx = x * w + this.px * 12;
        const my = y * h * 0.78 + h * 0.06;
        ctx.fillStyle = `rgba(255, 232, 178, ${alpha})`;
        ctx.beginPath();
        ctx.arc(mx, my, m.r, 0, Math.PI * 2);
        ctx.fill();
      });
      ctx.restore();
    }
  }

  global.PaperLightboxEngine = PaperLightboxEngine;
  global.PAPER_SCENE_THEMES = SCENE_THEMES;
})(window);
