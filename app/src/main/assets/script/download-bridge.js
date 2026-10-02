(function () {
  'use strict';

  var NS = '__litubeDownload';
  var BUTTON_ID = 'litube-download-entry';
  var DOWNLOAD_ICON =
    'M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z';
  var VIEW_BOX = '0 -960 960 960';
  var ID_RE = /^[a-zA-Z0-9_-]{11}$/;
  var LABELS = {
    zh: '下载', zt: '下載', en: 'Download', ja: 'ダウンロード',
    ko: '다운로드', fr: 'Télécharger', ru: 'Скачать', tr: 'İndir'
  };
  var DONE = {
    zh: '已下载', zt: '已下載', en: 'Downloaded', ja: 'ダウンロード済み',
    ko: '다운로드됨', fr: 'Téléchargé', ru: 'Скачано', tr: 'İndirildi'
  };

  if (window[NS] && typeof window[NS].rebind === 'function') {
    window[NS].rebind();
    return;
  }

  var observer = null;
  var spaBound = false;
  var lastStatus = null;

  function pageMeta() {
    return window.__litubeDownloadPage || { tabId: -1, pageGeneration: -1 };
  }

  function langKey() {
    var lang = (document.documentElement && document.documentElement.lang || navigator.language || 'en').toLowerCase();
    var parts = lang.split('-');
    if (parts.indexOf('hant') >= 0 || parts[1] === 'tw' || parts[1] === 'hk' || parts[1] === 'mo') {
      return 'zt';
    }
    return parts[0];
  }

  function label(map) {
    var key = langKey();
    if (key === 'zh') return map.zh;
    return map[key] || map.en;
  }

  function watchIdOf(href) {
    try {
      var url = new URL(href, location.href);
      var path = url.pathname || '';
      var id = null;
      if (path.indexOf('/shorts/') === 0 || path.indexOf('/live/') === 0 || path.indexOf('/embed/') === 0) {
        id = path.split('/')[2] || null;
      } else if (url.hostname === 'youtu.be' || url.hostname === 'www.youtu.be') {
        id = path.split('/')[1] || null;
      } else {
        id = url.searchParams.get('v');
      }
      return id && ID_RE.test(id) ? id : null;
    } catch (e) {
      return null;
    }
  }

  function currentVideo() {
    var id = watchIdOf(location.href) ||
      (globalThis.ytInitialPlayerResponse && globalThis.ytInitialPlayerResponse.videoDetails &&
        globalThis.ytInitialPlayerResponse.videoDetails.videoId) || null;
    if (!id || !ID_RE.test(id)) return null;
    var details = (globalThis.ytInitialPlayerResponse && globalThis.ytInitialPlayerResponse.videoDetails) || {};
    return {
      videoId: id,
      title: details.title || '',
      author: details.author || details.channelTitle || null
    };
  }

  function post(obj) {
    var meta = pageMeta();
    obj.tabId = meta.tabId;
    obj.pageGeneration = meta.pageGeneration;
    var json = JSON.stringify(obj);
    try {
      if (window.LitubeDownload && typeof LitubeDownload.postMessage === 'function') {
        LitubeDownload.postMessage(json);
        return;
      }
    } catch (e) {}
    try {
      if (window.LitubeDownloadFallback && typeof LitubeDownloadFallback.post === 'function') {
        LitubeDownloadFallback.post(json);
      }
    } catch (e2) {}
  }

  function applyStatus(detail) {
    if (!detail || typeof detail !== 'object') return;
    var statusId = typeof detail.videoId === 'string' ? detail.videoId : '';
    if (statusId) {
      var pageVideo = currentVideo();
      // SPA guard: never let a late status for video A relabel video B's button.
      if (pageVideo && pageVideo.videoId !== statusId) return;
    }
    lastStatus = detail;
    var button = document.getElementById(BUTTON_ID);
    if (!button) return;
    var downloaded = detail.watchPageDownloaded === true || detail.state === 'complete';
    var text = downloaded ? label(DONE) : label(LABELS);
    setText(button, text);
    if (button.getAttribute('aria-label') !== text) {
      button.setAttribute('aria-label', text);
    }
    var flag = downloaded ? '1' : '0';
    if (button.getAttribute('data-downloaded') !== flag) {
      button.setAttribute('data-downloaded', flag);
    }
  }

  function setText(button, text) {
    var node = button.querySelector('.ytSpecButtonShapeNextButtonTextContent') ||
      button.querySelector('.yt-core-attributed-string');
    // Writing textContent replaces the text node even when identical, which
    // re-triggers our own MutationObserver; compare before writing.
    if (node && node.textContent !== text) node.textContent = text;
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
    if (!(pathEl instanceof Element)) return false;
    pathEl.setAttribute('d', pathData);
    return true;
  }

  function svgIcon(pathData) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', VIEW_BOX);
    svg.setAttribute('width', '24');
    svg.setAttribute('height', '24');
    var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', pathData);
    path.setAttribute('fill', 'currentColor');
    svg.appendChild(path);
    return svg;
  }

  function cloneAction(template, id, text) {
    var button = template.cloneNode(true);
    button.id = id;
    stripNav(button);
    setText(button, text);
    button.setAttribute('aria-label', text);
    if (!setPath(button, DOWNLOAD_ICON)) {
      var iconHost = button.querySelector('.yt-spec-button-shape-next__icon') ||
        button.querySelector('.yt-icon-shape') || button;
      if (!iconHost.querySelector('svg')) iconHost.prepend(svgIcon(DOWNLOAD_ICON));
    }
    return button;
  }

  function isWatchPath() {
    var path = (location.pathname || '').toLowerCase();
    return path.indexOf('/watch') === 0 || path.indexOf('/shorts/') === 0 ||
      path.indexOf('/live/') === 0 || path.indexOf('/embed/') === 0;
  }

  function ensureWatchButton() {
    var old = document.getElementById(BUTTON_ID);
    if (!isWatchPath()) {
      if (old) old.remove();
      return;
    }
    if (old && old.isConnected && old.parentElement &&
        old.parentElement.querySelector('#' + BUTTON_ID) === old &&
        old.parentElement.querySelector('.ytSpecButtonViewModelHost')) {
      if (lastStatus) applyStatus(lastStatus);
      return;
    }
    var saveButton = document.querySelector(
      '.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button'
    ) || document.querySelector('ytm-slim-video-action-bar-renderer .ytSpecButtonViewModelHost') ||
      document.querySelector('.slim-video-action-bar-renderer .ytSpecButtonViewModelHost');
    if (!(saveButton instanceof Element) || !saveButton.parentElement) return;
    var actionBar = saveButton.parentElement;
    if (old && (old.parentElement !== actionBar || !old.isConnected)) old.remove();
    if (actionBar.querySelector('#' + BUTTON_ID)) return;
    var text = label(LABELS);
    var button = cloneAction(saveButton, BUTTON_ID, text);
    button.addEventListener('click', function (event) {
      event.preventDefault();
      event.stopImmediatePropagation();
      var downloaded = button.getAttribute('data-downloaded') === '1';
      if (downloaded) {
        post({ type: 'openManager' });
        return;
      }
      var video = currentVideo();
      if (!video) return;
      post({
        type: 'openConfirm',
        videoId: video.videoId,
        title: video.title,
        author: video.author
      });
    }, true);
    actionBar.insertBefore(button, saveButton);
    var video = currentVideo();
    if (video) post({ type: 'requestStatus', videoId: video.videoId });
  }

  function walkPlaylist(node, items, seen) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (var i = 0; i < node.length; i++) walkPlaylist(node[i], items, seen);
      return;
    }
    var renderer = node.playlistVideoRenderer || node.playlistPanelVideoRenderer ||
      node.videoRenderer || node.compactVideoRenderer;
    if (renderer && renderer.videoId && ID_RE.test(renderer.videoId) && !seen[renderer.videoId]) {
      seen[renderer.videoId] = 1;
      var titleRuns = renderer.title && renderer.title.runs;
      var title = (titleRuns && titleRuns[0] && titleRuns[0].text) || renderer.title || '';
      var author = null;
      if (renderer.shortBylineText && renderer.shortBylineText.runs && renderer.shortBylineText.runs[0]) {
        author = renderer.shortBylineText.runs[0].text;
      }
      items.push({ videoId: renderer.videoId, title: title, author: author });
    }
    for (var key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
      if (key === 'playlistVideoRenderer' || key === 'playlistPanelVideoRenderer') continue;
      walkPlaylist(node[key], items, seen);
    }
  }

  function collectDomItems(items, seen) {
    var roots = document.querySelectorAll(
      'ytm-playlist-video-list-renderer, ytm-playlist-panel-renderer, ytm-playlist-video-renderer, yt-playlist-panel-video-renderer'
    );
    if (!roots.length) return;
    for (var r = 0; r < roots.length; r++) {
      var links = roots[r].querySelectorAll('a[href*="v="], a[href^="/shorts/"], a[href*="/shorts/"]');
      for (var i = 0; i < links.length; i++) {
        var id = watchIdOf(links[i].href);
        if (!id || seen[id]) continue;
        seen[id] = 1;
        var title = (links[i].getAttribute('title') || links[i].textContent || '').trim();
        items.push({ videoId: id, title: title, author: null });
      }
    }
  }

  function collect() {
    var items = [];
    var seen = {};
    try {
      var data = globalThis.ytInitialData;
      var playlist = data && data.contents && (
        (data.contents.twoColumnBrowseResultsRenderer) ||
        (data.contents.singleColumnBrowseResultsRenderer) ||
        (data.contents.singleColumnWatchNextResults &&
          data.contents.singleColumnWatchNextResults.playlist &&
          data.contents.singleColumnWatchNextResults.playlist.playlist)
      );
      walkPlaylist(playlist, items, seen);
    } catch (e) {}
    collectDomItems(items, seen);
    var name = '';
    try {
      name = (document.querySelector('ytm-playlist-header-renderer h1, .playlist-header-title, h1') || {}).textContent || '';
      name = String(name).trim();
    } catch (e3) {}
    return { type: 'openBatch', name: name, items: items };
  }

  function cleanup() {
    if (observer) {
      observer.disconnect();
      observer = null;
    }
  }

  function observe() {
    cleanup();
    var scheduled = false;
    function scheduleEnsure() {
      if (scheduled) return;
      scheduled = true;
      setTimeout(function () {
        scheduled = false;
        ensureWatchButton();
      }, 0);
    }
    observer = new MutationObserver(function (records) {
      var button = document.getElementById(BUTTON_ID);
      for (var i = 0; i < records.length; i++) {
        var target = records[i].target;
        // Our own button updates must not feed back into ensureWatchButton.
        if (button && (target === button || (button.contains && button.contains(target)))) {
          continue;
        }
        scheduleEnsure();
        return;
      }
    });
    if (document.documentElement) {
      observer.observe(document.documentElement, { childList: true, subtree: true });
    }
  }

  function onSpa() {
    lastStatus = null;
    ensureWatchButton();
    var video = currentVideo();
    if (video) post({ type: 'requestStatus', videoId: video.videoId });
  }

  function bindSpa() {
    if (spaBound) return;
    spaBound = true;
    window.addEventListener('yt-navigate-finish', onSpa);
    window.addEventListener('yt-page-data-updated', onSpa);
    window.addEventListener('popstate', onSpa);
    window.addEventListener('pagehide', cleanup);
  }

  function onHostMessage(event) {
    var detail = event && (event.data || event.detail);
    if (typeof detail === 'string') {
      try { detail = JSON.parse(detail); } catch (e) { return; }
    }
    applyStatus(detail);
  }

  function bindHost() {
    try {
      if (window.LitubeDownload && typeof LitubeDownload.addEventListener === 'function') {
        LitubeDownload.addEventListener('message', onHostMessage);
      }
    } catch (e) {}
    window.addEventListener('litubeDownloadStatus', onHostMessage);
  }

  function rebind() {
    bindSpa();
    bindHost();
    observe();
    ensureWatchButton();
  }

  window[NS] = {
    rebind: rebind,
    collect: collect,
    cleanup: cleanup
  };
  rebind();
})();
