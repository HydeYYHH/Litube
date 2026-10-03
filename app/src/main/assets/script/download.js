/**
 * Watch-page entries: download, add to queue, open with, and the live-chat
 * panel, injected atomically through the shared scheduler; plus the
 * playlist snapshot the native download bridge collects.
 */
(() => {
  'use strict';
  if (!window.Lite) return;
  if (window.__download) {
    window.__download.rebind();
    return;
  }

  const NS = '__download';

  const ICONS = {
    download:
      'M480-320 280-520l56-58 104 104v-326h80v326l104-104 56 58-200 200ZM240-160q-33 0-56.5-23.5T160-240v-120h80v120h480v-120h80v120q0 33-23.5 56.5T720-160H240Z',
    queue: Lite.queueIcon,
    openWith:
      'M648-96q-50 0-85-35t-35-85q0-9 4-29L295-390q-16 14-36.05 22-20.04 8-42.95 8-50 0-85-35t-35-85q0-50 35-85t85-35q23 0 43 8t36 22l237-145q-2-7-3-13.81-1-6.81-1-15.19 0-50 35-85t85-35q50 0 85 35t35 85q0 50-35 85t-85 35q-23 0-43-8t-36-22L332-509q2 7 3 13.81 1 6.81 1 15.19 0 8.38-1 15.19-1 6.81-3 13.81l237 145q16-14 36.05-22 20.04-8 42.95-8 50 0 85 35t35 85q0 50-35 85t-85 35Zm0-72q20.4 0 34.2-13.8Q696-195.6 696-216q0-20.4-13.8-34.2Q668.4-264 648-264q-20.4 0-34.2 13.8Q600-236.4 600-216q0 20.4 13.8 34.2Q627.6-168 648-168ZM216-432q20.4 0 34.2-14 13.8-14 13.8-34t-13.8-34q-13.8-14-34.2-14-20.4 0-34.2 14-13.8 14-13.8 34t13.8 34q13.8 14 34.2 14Zm466-277.8q14-13.8 14-34.2 0-20.4-13.8-34.2Q668.4-792 648-792q-20.4 0-34.2 13.8Q600-764.4 600-744q0 20.4 14 34.2 14 13.8 34 13.8t34-13.8ZM648-216ZM216-480Zm432-264Z',
    chat:
      'M240-384h336v-72H240v72Zm0-132h480v-72H240v72Zm0-132h480v-72H240v72ZM96-96v-696q0-29.7 21.15-50.85Q138.3-864 168-864h624q29.7 0 50.85 21.15Q864-821.7 864-792v480q0 29.7-21.15 50.85Q821.7-240 792-240H240L96-96Zm114-216h582v-480H168v522l42-42Zm-42 0v-480 480Z',
    close:
      'M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z',
  };
  const CLOSE_BOX = '0 0 24 24';

  const ALL_IDS = ['downloadButton', 'queueButton', 'openWithButton', 'chatButton'];

  function pageMeta() {
    return window.__downloadPage || { tabId: -1, pageGeneration: -1 };
  }

  function post(obj) {
    const meta = pageMeta();
    obj.tabId = meta.tabId;
    obj.pageGeneration = meta.pageGeneration;
    const json = JSON.stringify(obj);
    if (window.Download && typeof Download.postMessage === 'function') {
      Download.postMessage(json);
    } else if (window.DownloadFallback && typeof DownloadFallback.post === 'function') {
      DownloadFallback.post(json);
    }
  }

  function currentVideo() {
    const details = (globalThis.ytInitialPlayerResponse || {}).videoDetails || {};
    const id = Lite.id() || details.videoId || null;
    if (!Lite.isId(id)) return null;
    return {
      videoId: id,
      title: details.title || '',
      author: details.author || details.channelTitle || null,
    };
  }

  function isLive() {
    try {
      const p = document.querySelector('#movie_player, .html5-video-player');
      if (p && typeof p.getPlayerResponse === 'function' &&
          p.getPlayerResponse()?.playabilityStatus?.liveStreamability) return true;
      return !!globalThis.ytInitialPlayerResponse?.videoDetails?.isLive;
    } catch { return false; }
  }

  // -- download status label --

  let lastStatus = null;

  function applyStatus() {
    if (!lastStatus) return;
    const page = currentVideo();
    // A late status for video A must never relabel video B's button.
    if (page && lastStatus.videoId && page.videoId !== lastStatus.videoId) return;
    const button = document.getElementById('downloadButton');
    if (!button) return;
    const done = lastStatus.watchPageDownloaded === true || lastStatus.state === 'complete';
    const label = Lite.text(done ? 'downloaded' : 'download');
    if (button.getAttribute('aria-label') !== label) button.setAttribute('aria-label', label);
    button.setAttribute('data-downloaded', done ? '1' : '0');
  }

  /** Entries are icon-only: the like template's count and rolling digits
   *  must not survive as stray glyphs; the label lives in aria-labels. */
  function blankText(button) {
    for (const node of button.querySelectorAll(
      '.ytSpecButtonShapeNextButtonTextContent, .ytAttributedStringHost, ' +
      '.yt-core-attributed-string, #text')) {
      if (node.textContent !== '') node.textContent = '';
    }
  }

  function onHostMessage(event) {
    let detail = event && (event.data || event.detail);
    if (typeof detail === 'string') {
      try { detail = JSON.parse(detail); } catch { return; }
    }
    lastStatus = detail;
    applyStatus();
  }

  /** Binds once: rebind() runs on every re-injection, and duplicate
   *  listeners would stack one per navigation. */
  let hostBound = false;

  function bindHost() {
    if (hostBound) return;
    hostBound = true;
    try {
      if (window.Download && typeof Download.addEventListener === 'function') {
        Download.addEventListener('message', onHostMessage);
      }
    } catch { /* bridge optional */ }
    window.addEventListener('downloadStatus', onHostMessage);
  }

  // -- button definitions --

  function onDownloadClick() {
    const button = document.getElementById('downloadButton');
    if (button && button.getAttribute('data-downloaded') === '1') {
      post({ type: 'openManager' });
      return;
    }
    const video = currentVideo();
    if (video) post({ type: 'openConfirm', ...video });
  }

  function onQueueClick() {
    const payload = currentVideo();
    const b = Lite.bridge();
    if (payload && b && typeof b.addToQueue === 'function') {
      b.addToQueue(JSON.stringify(payload));
    } else if (b && typeof b.reportQueueAddFailed === 'function') {
      b.reportQueueAddFailed();
    }
  }

  function onOpenWithClick() {
    const b = Lite.bridge();
    if (b && typeof b.openWith === 'function') b.openWith(location.href);
  }

  const WATCH_DEFS = [
    { id: 'downloadButton', icon: ICONS.download, label: () => Lite.text('download'), click: onDownloadClick },
    { id: 'queueButton', icon: ICONS.queue, label: () => Lite.text('addToQueue'), click: onQueueClick },
    { id: 'openWithButton', icon: ICONS.openWith, label: () => Lite.text('openWith'), click: onOpenWithClick },
  ];
  const LIVE_DEFS = [
    { id: 'chatButton', icon: ICONS.chat, label: () => Lite.text('chat'), click: toggleChat },
  ];

  /** Builds one entry from the row's chip; null while the chip carries
   *  no svg yet, and the scheduler retries. */
  function build(chip, def) {
    const button = chip.cloneNode(true);
    button.id = def.id;
    button.setAttribute('data-lite', 'entry');
    Lite.strip(button);
    blankText(button);
    const label = def.label();
    button.setAttribute('aria-label', label);
    const inner = button.querySelector('button, a');
    if (inner && inner.getAttribute('aria-label') !== label) {
      inner.setAttribute('aria-label', label);
    }
    if (!Lite.icon(button, def.icon)) return null;
    Lite.fit(button);
    button.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      def.click();
    }, true);
    return button;
  }

  /** Keeps [defs] inline right after the dislike button — the bar
   *  hydrates like/dislike (and further chips) late, so the block
   *  re-anchors whenever something lands ahead of it. Before the first
   *  free chip until dislike exists. Returns the number freshly built,
   *  or false when a chip carried no svg yet. */
  function place(row, chip, defs) {
    let anchor = row.querySelector('dislike-button-view-model') || chip;
    while (anchor && anchor.parentElement !== row) {
      anchor = anchor.parentElement;
    }
    if (!anchor) return false;
    const after = anchor !== chip;
    const nodes = [];
    let built = 0;
    for (const def of defs) {
      let el = document.getElementById(def.id);
      if (el && el.parentElement !== row) {
        el.remove();
        el = null;
      }
      if (!el) {
        el = build(chip, def);
        if (!el) return false;
        built += 1;
      }
      nodes.push(el);
    }
    const settled = (node, i) =>
      node.previousElementSibling ===
      (i ? nodes[i - 1] : (after ? anchor : anchor.previousElementSibling));
    if (!nodes.every(settled)) {
      if (after) {
        const ref = anchor.nextElementSibling;
        for (const node of nodes) row.insertBefore(node, ref);
      } else {
        for (let i = nodes.length - 1; i >= 0; i--) {
          row.insertBefore(nodes[i], anchor);
          anchor = nodes[i];
        }
      }
    }
    return built;
  }

  function removeButtons(ids) {
    for (const id of ids) {
      const el = document.getElementById(id);
      if (el) el.remove();
    }
  }

  // Consecutive no-chip passes on a watch page: the scheduler's early
  // ticks always miss (the page renders later), so only a sustained miss
  // means the bar has no cloneable host — say so once, in logcat.
  let chipMisses = 0;

  /** The single watch-bar pass: watch pages get the three entries, live
   *  pages the chat entry, everything else none. */
  function ensure() {
    const video = Lite.id();
    const row = video ? Lite.bar() : null;
    const chip = row && Lite.chip(row);
    if (!row || !chip) {
      if (video && ++chipMisses === 5) {
        console.warn('[download] no cloneable action-bar host');
      }
      removeButtons(ALL_IDS);
      return !video;
    }
    chipMisses = 0;
    const defs = isLive() ? LIVE_DEFS : WATCH_DEFS;
    removeButtons(ALL_IDS.filter((id) => !defs.some((def) => def.id === id)));
    const built = place(row, chip, defs);
    if (built === false) return false;
    if (built > 0) post({ type: 'requestStatus', videoId: video });
    applyStatus();
    return true;
  }

  // -- live chat panel --

  const CHAT_BOX_ID = 'chatBox';
  const CHAT_FRAME_ID = 'chatFrame';

  function darkMode() {
    return document.documentElement.getAttribute('dark') === 'true' ||
      window.matchMedia('(prefers-color-scheme: dark)').matches;
  }

  /** Shows or hides the chat panel. */
  function toggleChat() {
    const open = document.getElementById(CHAT_BOX_ID);
    if (open) {
      if (open.style.display === 'none') {
        open.style.display = 'flex';
        setScrollLock(true);
        history.pushState({ chatOpen: true }, '', `${location.href}#chat`);
      } else {
        hideChat(open);
      }
      return;
    }
    const panel = document.querySelector('#panel-container, .watch-below-the-player');
    const videoId = Lite.id();
    if (!panel || !videoId) return;
    panel.insertBefore(buildChatPanel(videoId), panel.firstChild);
    setScrollLock(true);
    history.pushState({ chatOpen: true }, '', `${location.href}#chat`);
  }

  function buildChatPanel(videoId) {
    const box = document.createElement('div');
    box.id = CHAT_BOX_ID;
    box.style.cssText = 'position:fixed;top:calc(56.25vw + 48px);bottom:0;left:0;right:0;' +
      'z-index:4;display:flex;flex-direction:column;box-shadow:0 -2px 10px rgba(0,0,0,0.1);' +
      'border-radius:12px 12px 0 0;overflow:hidden;';
    box.style.backgroundColor = darkMode() ? '#0f0f0f' : '#ffffff';

    const header = document.createElement('div');
    header.style.cssText = 'display:flex;justify-content:space-between;align-items:center;' +
      'padding:12px 16px;border-bottom:1px solid var(--yt-spec-10-percent-layer);' +
      'background-color:inherit;border-radius:12px 12px 0 0;';
    const title = document.createElement('h2');
    title.className = 'engagement-panel-section-list-header-title';
    title.textContent = Lite.text('chat');
    title.style.cssText = 'font-family:"YouTube Sans","Roboto",sans-serif;font-size:1.8rem;' +
      'font-weight:600;color:var(--yt-spec-text-primary);margin:0;';
    const closeButton = document.createElement('div');
    closeButton.style.cssText = 'cursor:pointer;color:var(--yt-spec-text-primary);padding:4px;';
    closeButton.appendChild(Lite.svg(ICONS.close, CLOSE_BOX));
    closeButton.addEventListener('click', (e) => {
      e.stopPropagation();
      hideChat(box);
    });
    header.appendChild(title);
    header.appendChild(closeButton);
    box.appendChild(header);

    const frame = document.createElement('iframe');
    frame.id = CHAT_FRAME_ID;
    frame.src = `https://www.youtube.com/live_chat?v=${videoId}` +
      `&embed_domain=${location.hostname}${darkMode() ? '&dark_theme=1' : ''}`;
    frame.style.cssText = 'width:100%;height:100%;border:none;flex:1;background-color:transparent;';
    box.appendChild(frame);

    window.addEventListener('popstate', () => {
      if (box.isConnected && box.style.display !== 'none' && !location.hash.includes('chat')) {
        hideChat(box, false);
      }
    });
    return box;
  }

  function hideChat(box, goBack = true) {
    box.style.display = 'none';
    setScrollLock(false);
    if (goBack && location.hash === '#chat') history.back();
  }

  function setScrollLock(locked) {
    const value = locked ? 'hidden' : '';
    document.body.style.overflow = value;
    document.documentElement.style.overflow = value;
  }

  // -- playlist snapshot for the native batch confirm --

  function walkPlaylist(node, items, seen) {
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node)) {
      for (const entry of node) walkPlaylist(entry, items, seen);
      return;
    }
    const renderer = node.playlistVideoRenderer || node.playlistPanelVideoRenderer ||
      node.videoRenderer || node.compactVideoRenderer;
    if (renderer && Lite.isId(renderer.videoId) && !seen[renderer.videoId]) {
      seen[renderer.videoId] = 1;
      const runs = renderer.title && renderer.title.runs;
      const byline = renderer.shortBylineText && renderer.shortBylineText.runs;
      items.push({
        videoId: renderer.videoId,
        title: (runs && runs[0] && runs[0].text) || renderer.title || '',
        author: (byline && byline[0] && byline[0].text) || null,
      });
    }
    for (const key in node) {
      if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
      if (key === 'playlistVideoRenderer' || key === 'playlistPanelVideoRenderer') continue;
      walkPlaylist(node[key], items, seen);
    }
  }

  function collect() {
    const items = [];
    const seen = {};
    try {
      const data = globalThis.ytInitialData || {};
      const playlist = data.contents && (
        data.contents.twoColumnBrowseResultsRenderer ||
        data.contents.singleColumnBrowseResultsRenderer ||
        (data.contents.singleColumnWatchNextResults &&
          data.contents.singleColumnWatchNextResults.playlist &&
          data.contents.singleColumnWatchNextResults.playlist.playlist)
      );
      walkPlaylist(playlist, items, seen);
    } catch { /* page data optional */ }
    for (const root of document.querySelectorAll(
      'ytm-playlist-video-list-renderer, ytm-playlist-panel-renderer, ' +
      'ytm-playlist-video-renderer, yt-playlist-panel-video-renderer',
    )) {
      for (const a of root.querySelectorAll('a[href*="v="], a[href^="/shorts/"], a[href*="/shorts/"]')) {
        const id = Lite.id(a.href);
        if (!id || seen[id]) continue;
        seen[id] = 1;
        items.push({ videoId: id, title: (a.getAttribute('title') || a.textContent || '').trim(), author: null });
      }
    }
    const header = document.querySelector('ytm-playlist-header-renderer h1, .playlist-header-title, h1');
    return { type: 'openBatch', name: (header && header.textContent || '').trim(), items };
  }

  // -- lifecycle --

  function rebind() {
    bindHost();
    Lite.module('watch', ensure);
    Lite.wake();
  }

  rebind();
  window[NS] = { rebind, collect };
})();
