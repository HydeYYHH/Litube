/**
 * Shared page runtime: label table, DOM helpers, and the one scheduler
 * every feature module registers with. Injected first.
 */
(() => {
  'use strict';
  if (window.Lite) return;

  const VIEW_BOX = '0 -960 960 960';
  const RETRY_MS = [128, 256, 512, 1024, 2048];
  const POLL_MS = 1000;
  const SPA_LAG_MS = 80;
  const ID_RE = /^[a-zA-Z0-9_-]{11}$/;

  // Every injected label in one table. `zt` serves the zh-Hant variants.
  const TEXT = {
    download: {
      zh: '下载', zt: '下載', en: 'Download', ja: 'ダウンロード', ko: '다운로드',
      fr: 'Télécharger', de: 'Herunterladen', es: 'Descargar', pt: 'Baixar',
      ru: 'Скачать', tr: 'İndir', hi: 'डाउनलोड', ar: 'تنزيل',
    },
    downloaded: {
      zh: '已下载', zt: '已下載', en: 'Downloaded', ja: 'ダウンロード済み',
      ko: '다운로드됨', fr: 'Téléchargé', de: 'Heruntergeladen', es: 'Descargado',
      pt: 'Baixado', ru: 'Скачано', tr: 'İndirildi', hi: 'डाउनलोड हो गया', ar: 'تم التنزيل',
    },
    addToQueue: {
      zh: '加入队列', zt: '加入佇列', en: 'Add to queue', ja: 'キューに追加',
      ko: '대기열에 추가', fr: 'Ajouter à la file', de: 'Zur Wiedergabeliste hinzufügen',
      es: 'Añadir a la cola', pt: 'Adicionar à fila', ru: 'Добавить в очередь',
      tr: 'Kuyruğa ekle', hi: 'कतार में जोड़ें', ar: 'إضافة إلى قائمة الانتظار',
    },
    openWith: {
      zh: '打开方式', zt: '開啟方式', en: 'Open with', ja: 'アプリで開く',
      ko: '다른 앱으로 열기', fr: 'Ouvrir avec', de: 'Öffnen mit', es: 'Abrir con',
      pt: 'Abrir com', ru: 'Открыть с помощью', tr: 'Birlikte aç',
      hi: 'अन्य ऐप से खोलें', ar: 'فتح باستخدام',
    },
    chat: {
      zh: '聊天室', zt: '聊天室', en: 'Chat', ja: 'チャット', ko: '채팅',
      fr: 'Chat', de: 'Chat', es: 'Chat', pt: 'Chat', ru: 'Чат', tr: 'Sohbet',
      hi: 'चैट', ar: 'الدردشة',
    },
    about: {
      zh: '关于', zt: '關於', en: 'About', ja: 'このアプリについて', ko: '정보',
      fr: 'À propos', de: 'Info', es: 'Acerca de', pt: 'Sobre', ru: 'О программе',
      tr: 'Hakkında', hi: 'ऐप के बारे में', ar: 'حول',
    },
    extension: {
      zh: '扩展', zt: '擴充功能', en: 'Extension', ja: '拡張機能', ko: '플러그인',
      fr: 'Extension', de: 'Erweiterung', es: 'Extensión', pt: 'Extensão',
      ru: 'Расширение', tr: 'Uzantı', hi: 'एक्सटेंशन', ar: 'الإضافات',
    },
    downloads: {
      zh: '下载管理', zt: '下載管理', en: 'Downloads', ja: 'ダウンロード', ko: '다운로드',
      fr: 'Téléchargements', de: 'Downloads', es: 'Descargas', pt: 'Downloads',
      ru: 'Загрузки', tr: 'İndirilenler', hi: 'डाउनलोड', ar: 'التنزيلات',
    },
  };

  function langKey() {
    const tag = (document.documentElement.lang || navigator.language || 'en').toLowerCase();
    const parts = tag.split('-');
    if (parts.includes('hant') || ['tw', 'hk', 'mo'].includes(parts[1])) return 'zt';
    return parts[0];
  }

  // The one glyph two features share — "add to queue" (watch-bar entry and
  // the ⋮ sheet row). Deliberately the playlist-add glyph, not the native
  // queue icon: it blends with YouTube's own iconography.
  const queueIcon =
    'M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z';

  function text(key) {
    const row = TEXT[key] || {};
    return row[langKey()] || row.en || key;
  }

  function bridge() {
    return window.Bridge || null;
  }

  function id(url) {
    try {
      const u = new URL(url || location.href, location.href);
      let id = u.searchParams.get('v');
      if (!id) {
        const segs = u.pathname.split('/').filter(Boolean);
        if (/youtu\.be$/.test(u.hostname)) id = segs[0];
        else {
          for (const kind of ['shorts', 'live', 'embed']) {
            const at = segs.indexOf(kind);
            if (at >= 0) { id = segs[at + 1]; break; }
          }
        }
      }
      return id && ID_RE.test(id) ? id : null;
    } catch { return null; }
  }

  const isId = (id) => !!id && ID_RE.test(id);

  /** The current video's action-bar row. */
  function bar() {
    return document.querySelector('ytm-slim-video-action-bar-renderer .slim-video-action-bar-actions') ||
      document.querySelector('.slim-video-action-bar-actions') ||
      document.querySelector('ytm-slim-video-action-bar-renderer');
  }

  const HOSTS = '.ytSpecButtonViewModelHost, .ytButtonViewModelHost, button-view-model';
  const NESTED = 'like-button-view-model, dislike-button-view-model, ' +
    'segmented-like-dislike-button-view-model, ytm-subscribe-button-renderer, ' +
    'ytm-slim-video-metadata-section-renderer';

  /** Clone source for an action-bar entry: the first free native chip
   *  (share, more, …); our own data-injected entries never qualify. When the
   *  bar has no free chip yet, the last nested host — the dislike entry,
   *  after which the entries belong — is the template of last resort. */
  function chip(row) {
    if (!(row instanceof Element)) return null;
    let nested = null;
    for (const host of row.querySelectorAll(HOSTS)) {
      if (host.closest('[data-injected]')) continue;
      if (host.closest(NESTED)) {
        nested = host;
        continue;
      }
      return host;
    }
    return nested;
  }

  function strip(el) {
    el.removeAttribute('href');
    el.removeAttribute('target');
    for (const a of el.querySelectorAll('a[href]')) {
      a.removeAttribute('href');
      a.removeAttribute('target');
    }
  }

  /** Own the cloned icon's DOM: c3-icon can render after cloneNode(),
   *  leaving an empty 24px slot beside a fallback SVG or overwriting a
   *  stamped glyph. Replace it in place before the clone is connected.
   *  Plain SVG templates keep their native layout. */
  function icon(el, path, box) {
    const c3 = el.querySelector('c3-icon');
    if (c3) {
      const host = document.createElement('span');
      host.className = 'yt-icon-shape';
      host.style.display = 'inline-flex';
      host.style.fill = 'currentColor';
      host.appendChild(svg(path, box));
      c3.replaceWith(host);
      return true;
    }
    const target = Array.from(el.querySelectorAll('svg')).find(
      (node) => !node.closest('lottie-component'));
    if (target) {
      target.setAttribute('viewBox', box || VIEW_BOX);
      let pathEl = target.querySelector('path');
      if (!(pathEl instanceof Element)) {
        pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
        target.appendChild(pathEl);
      }
      pathEl.setAttribute('d', path);
      return true;
    }
    const host = el.querySelector('.yt-icon-shape') ||
      el.querySelector('.ytSpecButtonShapeNextIcon');
    if (!(host instanceof Element)) return false;
    host.appendChild(svg(path, box));
    return true;
  }

  function svg(path, box) {
    const el = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    el.setAttribute('viewBox', box || VIEW_BOX);
    el.setAttribute('width', '24');
    el.setAttribute('height', '24');
    el.setAttribute('aria-hidden', 'true');
    el.setAttribute('focusable', 'false');
    el.setAttribute('fill', 'currentColor');
    const pathEl = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    pathEl.setAttribute('d', path);
    el.appendChild(pathEl);
    return el;
  }

  function fit(el) {
    if (!(el instanceof Element)) return;
    // Some bar variants squeeze entries below icon size, overlapping
    // glyphs; native flexible items absorb the space instead.
    el.style.flex = 'none';
    for (const host of [el.querySelector('c3-icon'), el.querySelector('.yt-icon-shape')]) {
      if (host instanceof Element) {
        host.style.width = '24px';
        host.style.height = '24px';
      }
    }
    const svgEl = el.querySelector('svg');
    if (svgEl instanceof Element) {
      svgEl.setAttribute('width', '24');
      svgEl.setAttribute('height', '24');
      svgEl.style.width = '24px';
      svgEl.style.height = '24px';
    }
  }

  // -- scheduler --

  const state = { mods: new Map(), raf: 0, timer: 0, step: 0 };

  function run() {
    clearTimeout(state.timer);
    state.timer = 0;
    let pending = false;
    for (const ensure of state.mods.values()) {
      let ok = true;
      try { ok = ensure() !== false; } catch { ok = false; }
      if (!ok) pending = true;
    }
    if (pending) {
      state.timer = setTimeout(wake, RETRY_MS[Math.min(state.step++, RETRY_MS.length - 1)]);
    } else {
      state.step = 0;
    }
  }

  function wake() {
    if (state.raf || !document.documentElement) return;
    state.raf = requestAnimationFrame(() => {
      state.raf = 0;
      run();
    });
  }

  /** Registers (or replaces) module [name]; ensure() returning false
   *  schedules the backoff retry. */
  function module(name, ensure) {
    state.mods.set(name, ensure);
    wake();
  }

  /** Runs [probe] on the backoff chain until it stops returning false,
   *  at most [cap] attempts. For transient targets, not modules. */
  function retry(probe, cap) {
    let step = 0;
    let timer = 0;
    let alive = true;
    function tick() {
      if (!alive) return;
      if (probe() !== false) return;
      if (cap != null && step >= cap) return;
      timer = setTimeout(tick, RETRY_MS[Math.min(step++, RETRY_MS.length - 1)]);
    }
    tick();
    return { cancel() { alive = false; clearTimeout(timer); } };
  }

  function start() {
    const root = document.documentElement;
    if (!root) {
      document.addEventListener('DOMContentLoaded', start, { once: true });
      return;
    }
    new MutationObserver(wake).observe(root, { childList: true, subtree: true });
    for (const type of ['yt-navigate-finish', 'yt-page-data-updated', 'popstate']) {
      window.addEventListener(type, () => setTimeout(wake, SPA_LAG_MS));
    }
    // Backstop when neither the observer nor an SPA event fires.
    setInterval(() => { if (document.visibilityState === 'visible') wake(); }, POLL_MS);
  }

  window.Lite = {
    text, bridge, id, isId, bar, chip, strip, icon, svg, fit, queueIcon,
    module, retry, wake,
  };
  start();
})();
