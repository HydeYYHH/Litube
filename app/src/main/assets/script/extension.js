(function () {
  'use strict';

  // Settings injection clones the ytm-settings template rows. No fixed
  // overlay and no DOM observer: late DOM is covered by a short setTimeout
  // retry chain plus SPA navigation events (native also re-runs this script
  // on doUpdateVisitedHistory). Re-evaluation is idempotent: when both rows
  // exist it performs reads only.
  var BUTTON_ID = 'extensionButton';
  var DOWNLOADER_BUTTON_ID = 'downloaderButton';
  var VIEW_BOX = '0 -960 960 960';
  var ICON_PATH =
    'M384-144H216q-29.7 0-50.85-21.15Q144-186.3 144-216v-168q40-2 68-29.5t28-66.5q0-39-28-66.5T144-576v-168q0-29.7 21.15-50.85Q186.3-816 216-816h168q0-40 27.77-68 27.78-28 68-28Q520-912 548-884.16q28 27.84 28 68.16h168q29.7 0 50.85 21.15Q816-773.7 816-744v168q40 0 68 27.77 28 27.78 28 68Q912-440 884.16-412q-27.84 28-68.16 28v168q0 29.7-21.15 50.85Q773.7-144 744-144H576q-2-40-29.38-68t-66.5-28q-39.12 0-66.62 28-27.5 28-29.5 68Zm-168-72h112q20-45 61.5-70.5T480-312q49 0 90.5 25.5T632-216h112v-240h72q9.6 0 16.8-7.2 7.2-7.2 7.2-16.8 0-9.6-7.2-16.8-7.2-7.2-16.8-7.2h-72v-240H504v-72q0-9.6-7.2-16.8-7.2-7.2-16.8-7.2-9.6 0-16.8 7.2-7.2 7.2-7.2 16.8v72H216v112q45 20 70.5 61.5T312-480q0 50.21-25.5 91.6Q261-347 216-328v112Zm264-264Z';
  var DOWNLOAD_ICON_PATH =
    'M480-336 288-528l51-51 105 105v-246h72v246l105-105 51 51-192 192ZM264-192q-30 0-51-21t-21-51v-72h72v72h432v-72h72v72q0 30-21 51t-51 21H264Z';
  var LABELS = {
    zh: '扩展', zt: '擴充功能', en: 'Extension', ja: '拡張機能',
    ko: '플러그인', fr: 'Extension', ru: 'Расширение', tr: 'Uzantı',
    de: 'Erweiterung', es: 'Extensión', pt: 'Extensão', hi: 'एक्सटेंशन', ar: 'الإضافات'
  };
  var DOWNLOADER_LABELS = {
    zh: '下载管理', zt: '下載管理', en: 'Downloads', ja: 'ダウンロード',
    ko: '다운로드', fr: 'Téléchargements', ru: 'Загрузки', tr: 'İndirilenler',
    de: 'Downloads', es: 'Descargas', pt: 'Downloads', hi: 'डाउनलोड', ar: 'التنزيلات'
  };
  var RETRY_MS = [128, 256, 512, 1024, 2048];

  function reportBase() {
    return {
      ok: false,
      skipped: false,
      reason: null,
      buttonId: BUTTON_ID,
      failures: [],
      steps: [],
      icon: { viewBox: null, pathSet: false }
    };
  }

  function label(map) {
    var lang = (document.documentElement && document.documentElement.lang || 'en').toLowerCase();
    var key = lang.substring(0, 2);
    if (lang.indexOf('tw') >= 0 || lang.indexOf('hk') >= 0 ||
        lang.indexOf('mo') >= 0 || lang.indexOf('hant') >= 0) {
      key = 'zt';
    }
    return map[key] || map.en;
  }

  function isSettingsPage() {
    try {
      if (document.querySelector('ytm-settings')) return true;
      if (document.querySelector('[data-mode="settings"]')) return true;
      var path = (location.pathname || '').toLowerCase();
      return path.indexOf('/select_site') >= 0;
    } catch (e) {
      return false;
    }
  }

  function stripNav(button) {
    if (!(button instanceof Element)) return;
    button.removeAttribute('href');
    button.removeAttribute('target');
    var anchors = button.querySelectorAll('a[href]');
    for (var i = 0; i < anchors.length; i++) {
      anchors[i].removeAttribute('href');
      anchors[i].removeAttribute('target');
    }
  }

  function setPath(root, pathData) {
    var svg = root && root.querySelector ? root.querySelector('svg') : null;
    if (!(svg instanceof Element)) return false;
    svg.setAttribute('viewBox', VIEW_BOX);
    var pathEl = svg.querySelector('path');
    if (pathEl instanceof Element) pathEl.setAttribute('d', pathData);
    return true;
  }

  function fitIcon(root) {
    var size = 24;
    var px = size + 'px';
    try {
      var icon = root.querySelector('c3-icon');
      var host = root.querySelector('.yt-icon-shape');
      var svg = root.querySelector('svg');
      if (icon) {
        icon.style.width = px;
        icon.style.height = px;
      }
      if (host) {
        host.style.width = px;
        host.style.height = px;
      }
      if (svg) {
        svg.setAttribute('width', String(size));
        svg.setAttribute('height', String(size));
        svg.style.width = px;
        svg.style.height = px;
      }
    } catch (e) { /* icon sizing is decorative */ }
  }

  function setText(button, text) {
    var el = button.querySelector('.ytAttributedStringHost');
    if (el) {
      el.innerText = text;
      return true;
    }
    return false;
  }

  function bindClick(button, action) {
    var bridge = window.lite || window.Bridge;
    if (!bridge || typeof bridge[action] !== 'function') return false;
    var handler = function (event) {
      try {
        if (event) {
          event.preventDefault();
          event.stopImmediatePropagation();
        }
        bridge[action]();
      } catch (e) { /* ignore */ }
    };
    try {
      button.removeEventListener('click', button.__liteClick, true);
    } catch (e) { /* ignore */ }
    button.__liteClick = handler;
    button.addEventListener('click', handler, true);
    return true;
  }

  function cloneAction(template, id, text, iconPath, action) {
    var button;
    try {
      button = template.cloneNode(true);
    } catch (e) {
      return null;
    }
    button.id = id;
    stripNav(button);
    setText(button, text);
    setPath(button, iconPath);
    fitIcon(button);
    bindClick(button, action);
    return button;
  }

  function ensureAction(settings, template, id, text, iconPath, action) {
    if (document.getElementById(id)) return true;
    var button = cloneAction(template, id, text, iconPath, action);
    if (!button) return false;
    try {
      settings.insertBefore(button, template);
    } catch (e) {
      return false;
    }
    return !!document.getElementById(id);
  }

  function readIcon() {
    try {
      var btn = document.getElementById(BUTTON_ID);
      var svg = btn && btn.querySelector('svg');
      var pathEl = svg && svg.querySelector('path');
      var vb = svg ? svg.getAttribute('viewBox') : null;
      var d = pathEl ? pathEl.getAttribute('d') : null;
      return { viewBox: vb, pathSet: !!d && d.indexOf(ICON_PATH.substring(0, 16)) === 0 };
    } catch (e) {
      return { viewBox: null, pathSet: false };
    }
  }

  function injectOnce() {
    var report = reportBase();
    if (!isSettingsPage()) {
      report.ok = true;
      report.skipped = true;
      report.reason = 'not_settings_page';
      return report;
    }
    var settings = null;
    var template = null;
    try {
      settings = document.querySelector('ytm-settings');
      template = settings && settings.firstElementChild;
    } catch (e) { /* fall through to missing-root report */ }
    if (!(settings instanceof Element) || !(template instanceof Element) ||
        !template.querySelector('svg')) {
      report.reason = 'missing_settings_root';
      report.failures.push({ element: 'ytm-settings', reason: 'settings list root missing' });
      return report;
    }
    var okDownloader = ensureAction(
      settings, template, DOWNLOADER_BUTTON_ID,
      label(DOWNLOADER_LABELS), DOWNLOAD_ICON_PATH, 'download');
    var okExtension = ensureAction(
      settings, template, BUTTON_ID,
      label(LABELS), ICON_PATH, 'extension');
    report.icon = readIcon();
    report.ok = okDownloader && okExtension;
    report.reason = report.ok ? 'injected' : 'partial_failure';
    if (!report.ok) {
      report.failures.push({ element: 'template_button', reason: 'clone or insert failed' });
    }
    return report;
  }

  function scheduleRetries() {
    if (window.__liteExtRetrying) return;
    window.__liteExtRetrying = true;
    var i = 0;
    function tick() {
      var r = injectOnce();
      if (r.ok || r.skipped || i >= RETRY_MS.length) {
        window.__liteExtRetrying = false;
        return;
      }
      setTimeout(tick, RETRY_MS[i++]);
    }
    setTimeout(tick, RETRY_MS[i++]);
  }

  function bindSpa() {
    if (window.__liteExtSpaBound) return;
    window.__liteExtSpaBound = true;
    try {
      window.addEventListener('yt-navigate-finish', scheduleRetries);
      window.addEventListener('yt-page-data-updated', scheduleRetries);
    } catch (e) { /* ignore */ }
  }

  var result = injectOnce();
  bindSpa();
  if (!result.ok && !result.skipped) {
    scheduleRetries();
  }
  return result;
})()
