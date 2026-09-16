(() => {
  'use strict';
  // Top frame only: an iframe's URL rarely resolves a watch id, and its
  // sync() would then hide the native player for the whole app.
  if (window.top !== window.self) return;
  if (window.__litePlayerHook) return;
  window.__litePlayerHook = true;

  const BRIDGE = 'lite';
  const ID_RE = /^[a-zA-Z0-9_-]{11}$/;
  let activeId = null;
  let playerShown = false;

  function bridge() {
    return window[BRIDGE] || window.Bridge || null;
  }

  // Mirrors watch-id.js videoId() (display_dislikes.js getVideoId() is a
  // partial copy too); kept separate on purpose — merging would couple the
  // scripts' injection order.
  function watchIdOf(url) {
    try {
      const u = new URL(url || location.href, location.href);
      if (u.pathname === '/watch') {
        const v = u.searchParams.get('v');
        return v && ID_RE.test(v) ? v : null;
      }
      const segs = u.pathname.split('/').filter(Boolean);
      if (/(^|\.)youtu\.be$/i.test(u.hostname) && segs[0] && ID_RE.test(segs[0])) return segs[0];
      const shorts = segs.indexOf('shorts');
      if (shorts >= 0 && segs[shorts + 1] && ID_RE.test(segs[shorts + 1])) return segs[shorts + 1];
      const li = segs.indexOf('live');
      if (li >= 0 && segs[li + 1] && ID_RE.test(segs[li + 1])) return segs[li + 1];
      const embed = segs.indexOf('embed');
      if (embed >= 0 && segs[embed + 1] && ID_RE.test(segs[embed + 1])) return segs[embed + 1];
    } catch {}
    return null;
  }

  // -- in-page player suppression --

  function pagePlayer() {
    return document.querySelector('#movie_player') ||
      document.querySelector('.html5-video-player');
  }

  function suppressPagePlayer() {
    try {
      const p = pagePlayer();
      if (!p) return;
      // Pause, mute + hide: native playback streams its own URLs, so the
      // page player only wastes bandwidth and can race the audio focus.
      const v = p.querySelector('video');
      if (v) {
        v.muted = true;
        v.pause && v.pause();
      }
      p.style.visibility = 'hidden';
      p.style.pointerEvents = 'none';
    } catch {}
  }

  function restorePagePlayer() {
    try {
      const p = pagePlayer();
      if (!p) return;
      p.style.visibility = '';
      p.style.pointerEvents = '';
    } catch {}
  }

  // -- ad skip inside the (suppressed) page player --

  function skipAdIfPlaying() {
    try {
      const p = pagePlayer();
      if (!p || !p.classList.contains('ad-showing')) return;
      const v = p.querySelector('video');
      if (v && isFinite(v.duration) && v.duration > 0) {
        v.currentTime = v.duration;
      }
      const skip = p.querySelector('.ytp-skip-ad-button, .ytp-ad-skip-button');
      if (skip) skip.click();
    } catch {}
  }

  let lastLayoutKey = '';
  let lastPlaylist = false;
  let lastQueueBtnProbe = 0;

  function reportLayout() {
    try {
      const p = pagePlayer();
      const b = bridge();
      if (!p || !b || typeof b.setPlayerLayout !== 'function') return;
      const r = p.getBoundingClientRect();
      const top = Math.round(r.top);
      const height = Math.round(r.height);
      if (height <= 0) return;
      const key = top + 'x' + height;
      if (key === lastLayoutKey) return;
      lastLayoutKey = key;
      b.setPlayerLayout(top, height);
    } catch {}
  }

  function hideNative() {
    if (!playerShown) return;
    playerShown = false;
    activeId = null;
    lastLayoutKey = '';
    lastPlaylist = false;
    const b = bridge();
    if (b && typeof b.hidePlayer === 'function') b.hidePlayer();
    if (b && typeof b.setPageHasPlaylist === 'function') {
      try { b.setPageHasPlaylist(false); } catch {}
    }
  }

  // -- navigation-driven show/hide --

  function reportPlaylist() {
    const b = bridge();
    if (!b || typeof b.setPageHasPlaylist !== 'function') return;
    try {
      const has = /[?&]list=/.test(location.search) || !!playlistData();
      if (has === lastPlaylist) return;
      lastPlaylist = has;
      b.setPageHasPlaylist(has);
    } catch {}
  }

  function mediaHref() {
    const href = location.href || '';
    const hashIdx = href.indexOf('#');
    const base = hashIdx < 0 ? href : href.slice(0, hashIdx);
    const hash = hashIdx < 0 ? '' : href.slice(hashIdx + 1);
    if (!/[?&](?:t|start)=/.test(base) && hash) {
      const tm = hash.match(/(?:^|&)t=([^&]+)/);
      if (tm) {
        return base + (base.indexOf('?') >= 0 ? '&' : '?') + 't=' + tm[1];
      }
    }
    return base;
  }
  window.__liteMediaHref = mediaHref;

  /** Shorts vertical feed: native overlay swipes must move the page, not brightness. */
  function shortsNav(dir) {
    const step = dir >= 0 ? 1 : -1;
    const scroller = document.querySelector(
      'ytm-shorts, #shorts-container, #shorts-inner-container, ytm-reel-watch-fragment, #shorts-player',
    );
    if (scroller && typeof scroller.scrollBy === 'function') {
      const h = scroller.clientHeight || window.innerHeight || 800;
      scroller.scrollBy(0, step * h);
      return 'ok';
    }
    return 'none';
  }
  window.__liteShortsNav = shortsNav;

  function sync() {
    watchPath = isWatchPath();
    const id = watchIdOf(location.href);
    const b = bridge();
    if (id) {
      suppressPagePlayer();
      skipAdIfPlaying();
      if (id !== activeId || !playerShown) {
        lastLayoutKey = '';
        activeId = id;
        playerShown = true;
        // Native decides the surface: full overlay on an active watch tab,
        // keep the mini-player when the watch tab is merely suspended.
        if (b && typeof b.play === 'function') {
          b.play(mediaHref());
        }
      }
      reportLayout();
      reportPlaylist();
      ensureWatchQueueButton();
    } else {
      hideNative();
      restorePagePlayer();
      ensureWatchQueueButton();
    }
  }

  // Timestamp links (?t=1m30s / &t=90) → seek the native player instead of navigating.
  function interceptTimestamps() {
    document.addEventListener('click', (e) => {
      const a = e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      const href = a.getAttribute('href') || '';
      if (!/[?&#]t=/.test(href)) return;
      const targetId = watchIdOf(href);
      if (!targetId || targetId !== activeId) return;
      const m = href.match(/[?&#]t=([^&]+)/);
      const seconds = parseTime(m && m[1]);
      if (seconds == null) return;
      const b = bridge();
      if (b && typeof b.seekLoadedVideo === 'function') {
        if (b.seekLoadedVideo(location.href.split('#')[0], seconds * 1000)) {
          e.preventDefault();
          e.stopImmediatePropagation();
        }
      }
    }, true);
  }

  // -- YouTube playlist navigation --

  function playlistData() {
    try {
      return globalThis.ytInitialData?.contents?.
        singleColumnWatchNextResults?.playlist?.playlist || null;
    } catch { return null; }
  }

  function currentVideoId() {
    try {
      const id = watchIdOf(location.href) ||
        globalThis.ytInitialPlayerResponse?.videoDetails?.videoId;
      return id && ID_RE.test(id) ? id : null;
    } catch { return null; }
  }

  /**
   * Finds the playlist entry for the current video and answers the requested
   * relative entry's URL. Sentinels (JSON strings) tell the host when to fall
   * back to WebView back-navigation.
   */
  function playlistEntryUrl(dir) {
    const list = playlistData();
    if (!list || !Array.isArray(list.contents) || list.contents.length === 0) {
      return '"missing-playlist"';
    }
    const id = currentVideoId();
    if (!id) return '"missing-current-video-id"';
    let index = -1;
    for (let i = 0; i < list.contents.length; i++) {
      const entry = list.contents[i]?.playlistPanelVideoRenderer;
      if (entry && entry.videoId === id) { index = i; break; }
    }
    if (index < 0) return '"missing-current-video"';
    const total = list.contents.length;
    let target;
    if (dir > 0) {
      if (index === total - 1) return '"playlist-end"';  // next at tail stops
      target = index + 1;
    } else {
      if (index === 0) return '"playlist-head"';          // prev at head
      target = index - 1;
    }
    const renderer = list.contents[target]?.playlistPanelVideoRenderer;
    const url = renderer?.navigationEndpoint?.commandMetadata?.
      webCommandMetadata?.url;
    if (url) return JSON.stringify(new URL(url, location.origin).toString());
    return '"missing-target"';
  }

  function goToEntryUrl(jsonUrl) {
    try {
      const url = JSON.parse(jsonUrl);
      if (typeof url === 'string' && /^https?:/.test(url)) {
        // Full page load on purpose: an SPA hop would need YouTube's own
        // router state replayed, and the reload deterministically re-runs
        // the document-start hooks (the new page's hook re-calls lite.play).
        location.href = url;
        return true;
      }
    } catch {}
    return false;
  }

  // Host → page: navigate the YouTube playlist relatively.
  window.__litePlaylistNav = function (dir) {
    const jsonUrl = playlistEntryUrl(dir);
    if (goToEntryUrl(jsonUrl)) return 'navigating';
    return JSON.parse(jsonUrl);
  };

  // -- "Add to queue": watch bar, long-press card, overflow sheet --

  // Deliberately different from res/drawable/ic_queue.xml (three lines):
  // the in-page entries use the "playlist add" glyph to blend with
  // YouTube's own action-bar iconography.
  const QUEUE_ICON = 'M120-320v-80h280v80H120Zm0-160v-80h440v80H120Zm0-160v-80h440v80H120Zm520 480v-160H480v-80h160v-160h80v160h160v80H720v160h-80Z';
  const MEDIA_BASE_URL = 'https://m.youtube.com';
  const THUMB_BASE_URL = 'https://i.ytimg.com/vi/';
  const MENU_LABELS = {
    'zh': '加入队列', 'zh-TW': '加入佇列', 'ja': 'キューに追加', 'ko': '대기열에 추가',
    'en': 'Add to queue', 'fr': 'Ajouter à la file', 'de': 'Zur Wiedergabeliste hinzufügen',
    'es': 'Añadir a la cola', 'pt': 'Adicionar à fila', 'ru': 'Добавить в очередь',
    'tr': 'Kuyruğa ekle', 'hi': 'कतार में जोड़ें', 'ar': 'إضافة إلى قائمة الانتظار',
  };
  const MEDIA_ROOT = 'ytm-media-item, yt-lockup-view-model, ytm-rich-item-renderer, .ytLockupViewModelHost';
  const MEDIA_INFO = '.media-item-info';
  const MENU_TRIGGER = '.media-item-menu, .ytLockupMetadataViewModelMenuButton';
  const SHEET_ITEM = 'ytm-menu-service-item-renderer, yt-list-item-view-model, toggleable-list-item-view-model';
  const SHEET_BUTTONS = [
    'button.menu-item-button',
    'button.yt-list-item-view-model__button-or-anchor',
    'button',
  ];
  const ENGLISH_MENU_ARIA = ['more actions', 'action menu'];
  const RETRY_MS = [128, 256, 512, 1024, 2048];
  const MEDIA_LINKS = [
    '.media-item-metadata a[href]',
    'a.ytLockupViewModelContentImage[href]',
    'a.ytmVideoPreviewNavigationEndpoint[href]',
    'a[href*="/watch"][href*="v="]',
    'a[href^="/shorts/"]',
    'a[href*="/shorts/"]',
  ];
  const TITLE_SELECTORS = [
    '.media-item-headline .yt-core-attributed-string', '.media-item-headline',
    '.media-item-title .yt-core-attributed-string', '.media-item-title',
    '.ytLockupViewModelTitle .yt-core-attributed-string', '.ytLockupViewModelTitle',
    '.yt-lockup-metadata-view-model-wiz__title',
    'h3 .yt-core-attributed-string', 'h3', 'a[title]', '.yt-core-attributed-string',
  ];
  const AUTHOR_SELECTORS = [
    'ytm-badge-and-byline-renderer span[dir="auto"]',
    '.media-item-byline .yt-core-attributed-string', '.media-item-byline',
    '.ytLockupViewModelMetadata .yt-core-attributed-string',
    '.yt-lockup-metadata-view-model-wiz__metadata',
    '.secondary-text .yt-core-attributed-string', '.secondary-text',
    '.ytm-badge-and-byline-item-byline',
  ];

  // The selector tables above track YouTube internals that A/B tests
  // shuffle. When a whole group stops matching, the hooks degrade silently;
  // say it once per group so the breakage is discoverable in logcat.
  const warnedGroups = new Set();
  function warnSelectorMiss(group) {
    if (warnedGroups.has(group)) return;
    warnedGroups.add(group);
    console.warn('[player-hook] selector group no longer matches: ' + group);
  }

  const QUEUE_BTN_ID = 'liteQueueButton';
  const MEDIA_HOLD_MS = 420;
  const MOVE_CANCEL_PX = 12;
  const MEDIA_CONTEXT_BLOCK_MS = 400;
  let mediaHold = null;
  let mediaContextUntil = 0;
  let lastSheetPayload = null;
  let sheetRetryTimer = 0;
  let sheetRetryVersion = 0;
  // Menu-trigger clicks open this window; outside it the MutationObserver
  // skips the sheetRoot() probe (four document-wide selectors per frame).
  let sheetWindowUntil = 0;

  function menuLabel() {
    const lang = (document.documentElement.lang || navigator.language || 'en').toLowerCase();
    const segments = lang.split('-');
    // Traditional variants: match whole BCP-47 segments, not substrings.
    if (segments.includes('hant') || ['tw', 'hk', 'mo'].includes(segments[1])) {
      return MENU_LABELS['zh-TW'];
    }
    const base = segments[0];
    if (base === 'zh') return MENU_LABELS.zh;
    return MENU_LABELS[base] || MENU_LABELS.en;
  }

  function pierceParent(node) {
    if (!node) return null;
    if (node.parentElement) return node.parentElement;
    const root = node.getRootNode && node.getRootNode();
    return (root && root.host) || null;
  }

  function queryDeep(root, selector) {
    if (!root) return null;
    if (root instanceof Element && root.matches && root.matches(selector)) return root;
    if (root.querySelector) {
      const hit = root.querySelector(selector);
      if (hit) return hit;
    }
    const visit = (el) => {
      if (!el) return null;
      if (el.shadowRoot) {
        const inner = el.shadowRoot.querySelector(selector);
        if (inner) return inner;
        for (const child of el.shadowRoot.children) {
          const nested = visit(child);
          if (nested) return nested;
        }
      }
      for (const child of el.children || []) {
        const nested = visit(child);
        if (nested) return nested;
      }
      return null;
    };
    if (root instanceof Element || root instanceof ShadowRoot) return visit(root);
    return null;
  }

  function closestDeep(node, selector) {
    let cur = node instanceof Element ? node : null;
    while (cur) {
      if (cur.matches && cur.matches(selector)) return cur;
      if (cur.closest) {
        const hit = cur.closest(selector);
        if (hit) return hit;
      }
      cur = pierceParent(cur);
    }
    return null;
  }

  function pathOf(event) {
    return typeof event.composedPath === 'function' ? event.composedPath() : [];
  }

  function isMenuTrigger(node) {
    if (!(node instanceof Element)) return false;
    if (node.matches(MENU_TRIGGER) || node.closest(MENU_TRIGGER)) return true;
    const aria = (node.getAttribute('aria-label') || '').toLowerCase();
    return node.matches('button') && ENGLISH_MENU_ARIA.indexOf(aria) >= 0;
  }

  function fitIcon(root, size) {
    if (!(root instanceof Element)) return;
    const px = (size || 24) + 'px';
    const icon = root.querySelector('c3-icon');
    const host = root.querySelector('.yt-icon-shape');
    const svg = root.querySelector('svg');
    if (icon instanceof Element) {
      icon.style.width = px;
      icon.style.height = px;
    }
    if (host instanceof Element) {
      host.style.width = px;
      host.style.height = px;
    }
    if (svg instanceof SVGElement) {
      svg.setAttribute('width', String(size || 24));
      svg.setAttribute('height', String(size || 24));
      svg.style.width = px;
      svg.style.height = px;
    }
  }

  function svgIcon(pathData, size) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 -960 960 960');
    svg.setAttribute('width', String(size || 24));
    svg.setAttribute('height', String(size || 24));
    svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', pathData);
    svg.appendChild(path);
    return svg;
  }

  function setPath(root, pathData) {
    const svg = root && root.querySelector && root.querySelector('svg');
    if (!(svg instanceof SVGElement)) return false;
    svg.setAttribute('viewBox', '0 -960 960 960');
    const paths = Array.from(svg.querySelectorAll('path'));
    if (paths.length === 0) {
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', pathData);
      svg.appendChild(path);
      return true;
    }
    paths[0].setAttribute('d', pathData);
    for (let i = 1; i < paths.length; i++) paths[i].remove();
    return true;
  }

  function stripNav(button) {
    if (!(button instanceof Element)) return;
    button.removeAttribute('href');
    button.removeAttribute('target');
    button.querySelectorAll('a[href]').forEach((anchor) => {
      anchor.removeAttribute('href');
      anchor.removeAttribute('target');
    });
  }

  function textOf(root, selectors) {
    for (let i = 0; i < selectors.length; i++) {
      const el = queryDeep(root, selectors[i]);
      const text = el && el.textContent && el.textContent.replace(/\s+/g, ' ').trim();
      if (text) return text;
    }
    return '';
  }

  function mediaLinkOf(root) {
    for (let i = 0; i < MEDIA_LINKS.length; i++) {
      const selector = MEDIA_LINKS[i];
      const link = (root instanceof Element && root.matches(selector))
        ? root
        : queryDeep(root, selector);
      const href = link && (link.getAttribute('href') || link.href);
      if (href && watchIdOf(href)) return href;
    }
    return null;
  }

  function mediaCardOf(origin) {
    let node = origin instanceof Element ? origin : null;
    while (node && node !== document.body) {
      if (!(isMenuTrigger(node) && node.matches && node.matches(MENU_TRIGGER))) {
        const self = closestDeep(node, MEDIA_ROOT);
        if (self) return self;
        const info = queryDeep(node, MEDIA_INFO);
        if (info) return info;
      }
      const parent = pierceParent(node);
      if (parent) {
        const siblings = Array.from(parent.children || []);
        const match = siblings.find((sibling) =>
          sibling.matches && (sibling.matches(MEDIA_ROOT) || sibling.matches(MEDIA_INFO)));
        if (match) return match;
        for (let i = 0; i < siblings.length; i++) {
          const nested = siblings[i].querySelector && (
            siblings[i].querySelector(MEDIA_ROOT) || siblings[i].querySelector(MEDIA_INFO)
          );
          if (nested) return nested;
        }
      }
      node = parent;
    }
    return null;
  }

  /** Payload for the media card the opened bottom-sheet belongs to. */
  function queueItemFor(trigger) {
    const root = mediaCardOf(trigger);
    if (!root) return null;
    return queuePayloadForRoot(root);
  }

  /** Long-press path: the card root is captured at touchstart and the
   *  payload is only scraped once the hold actually fires. */
  function queuePayloadForRoot(root) {
    const href = mediaLinkOf(root);
    const videoId = href && watchIdOf(href);
    if (!videoId) return null;
    let url;
    try {
      const parsed = new URL(href, MEDIA_BASE_URL);
      parsed.searchParams.delete('list');
      parsed.searchParams.delete('index');
      parsed.searchParams.delete('pp');
      url = parsed.toString();
    } catch {
      return null;
    }
    const link = queryDeep(root, 'a[title]');
    const scrapedTitle = textOf(root, TITLE_SELECTORS);
    const scrapedAuthor = textOf(root, AUTHOR_SELECTORS);
    if (!scrapedTitle && !scrapedAuthor) warnSelectorMiss('media-card-text');
    return {
      videoId,
      url,
      title: scrapedTitle || (link && link.getAttribute('title')) || videoId,
      author: scrapedAuthor || null,
      thumbnailUrl: THUMB_BASE_URL + videoId + '/hqdefault.jpg',
    };
  }

  // Touchstart sits on the input path: one scoped selector decides whether
  // arming a hold is worthwhile; payload scraping waits for the hold.
  const CARD_LINK_HINT = 'a[href*="/watch"], a[href*="/shorts/"], ' +
    'a[href*="youtu.be"], a.ytmVideoPreviewNavigationEndpoint, ' +
    'a.ytLockupViewModelContentImage';

  function cardHasVideoLink(root) {
    try {
      return !!(root && root.querySelector && root.querySelector(CARD_LINK_HINT));
    } catch {
      return false;
    }
  }

  function isWatchPath() {
    try {
      return new URL(location.href).pathname === '/watch';
    } catch {
      return false;
    }
  }

  // getPlayerResponse() builds a large object, so the live verdict is
  // cached per video: the MutationObserver path can call this every frame.
  let liveWatchCache = { id: null, live: false };
  function isLiveWatch() {
    try {
      const id = currentVideoId();
      if (id && liveWatchCache.id === id) return liveWatchCache.live;
      let live = false;
      const p = pagePlayer();
      if (p && typeof p.getPlayerResponse === 'function') {
        if (p.getPlayerResponse()?.playabilityStatus?.liveStreamability) live = true;
      }
      if (!live) live = !!globalThis.ytInitialPlayerResponse?.videoDetails?.isLive;
      if (id) liveWatchCache = { id, live };
      return live;
    } catch {
      return false;
    }
  }

  /** Currently playing watch item for the action-bar "Add to queue" button. */
  function currentQueueItem() {
    try {
      const p = pagePlayer();
      const data = p && typeof p.getVideoData === 'function' ? p.getVideoData() : null;
      const details = globalThis.ytInitialPlayerResponse?.videoDetails;
      const videoId = (data && data.video_id) || (details && details.videoId) || currentVideoId();
      if (!videoId) return null;
      let author = (data && data.author) || (details && details.author);
      if (!author) {
        const selectors = [
          'ytm-slim-owner-renderer .slim-owner-channel-name',
          'ytm-slim-owner-renderer a .yt-core-attributed-string',
          'ytm-slim-owner-renderer .yt-core-attributed-string',
          'ytm-video-owner-renderer .video-owner-title',
          '.slim-video-metadata .yt-core-attributed-string',
        ];
        for (let i = 0; i < selectors.length; i++) {
          const text = document.querySelector(selectors[i])?.textContent?.replace(/\s+/g, ' ').trim();
          if (text) { author = text; break; }
        }
      }
      const title = (data && data.title) || (details && details.title) ||
        document.title.replace(/ - YouTube$/, '').trim() || videoId;
      return {
        videoId,
        url: location.href.split('#')[0],
        title,
        author: author || null,
        thumbnailUrl: THUMB_BASE_URL + videoId + '/hqdefault.jpg',
      };
    } catch {
      return null;
    }
  }

  function cloneAction(templateButton, id, label, iconPath) {
    const button = templateButton.cloneNode(true);
    button.id = id;
    stripNav(button);
    const text = button.querySelector('.ytSpecButtonShapeNextButtonTextContent') ||
      button.querySelector('.yt-core-attributed-string');
    if (text) text.textContent = label;
    button.setAttribute('aria-label', label);
    if (!setPath(button, iconPath)) {
      const iconHost = button.querySelector('.yt-spec-button-shape-next__icon') ||
        button.querySelector('.yt-icon-shape') || button;
      if (!iconHost.querySelector('svg')) iconHost.prepend(svgIcon(iconPath));
      else return null;
    }
    fitIcon(button);
    return button;
  }

  /** Watch action bar: clone the first chip and insert Add to queue before it. */
  function ensureWatchQueueButton() {
    const old = document.getElementById(QUEUE_BTN_ID);
    if (!isWatchPath() || isLiveWatch()) {
      old && old.remove();
      return;
    }
    // Steady state: the button is still anchored inside a bar that has
    // YouTube's own button — skip the template lookups (this runs from the
    // MutationObserver frame loop and the 1s sync backstop).
    if (old && old.isConnected && old.parentElement &&
        old.parentElement.querySelector('#' + QUEUE_BTN_ID) === old &&
        old.parentElement.querySelector('.ytSpecButtonViewModelHost')) {
      return;
    }
    const saveButton = document.querySelector(
      '.ytSpecButtonViewModelHost.slim_video_action_bar_renderer_button',
    ) || document.querySelector('ytm-slim-video-action-bar-renderer .ytSpecButtonViewModelHost') ||
      document.querySelector('.slim-video-action-bar-renderer .ytSpecButtonViewModelHost');
    if (!(saveButton instanceof Element) || !saveButton.parentElement) {
      warnSelectorMiss('watch-action-bar');
      return;
    }
    const actionBar = saveButton.parentElement;
    if (old && (old.parentElement !== actionBar || !old.isConnected)) old.remove();
    if (actionBar.querySelector('#' + QUEUE_BTN_ID)) return;
    const button = cloneAction(saveButton, QUEUE_BTN_ID, menuLabel(), QUEUE_ICON);
    if (!button) return;
    button.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      const payload = currentQueueItem();
      const b = bridge();
      if (payload && b && typeof b.addToQueue === 'function') {
        b.addToQueue(JSON.stringify(payload));
      } else if (b && typeof b.reportQueueAddFailed === 'function') {
        b.reportQueueAddFailed();
      }
    }, true);
    actionBar.insertBefore(button, saveButton);
  }

  function mediaRootOf(event) {
    const path = pathOf(event);
    for (let i = 0; i < path.length; i++) {
      const node = path[i];
      if (!(node instanceof Element)) continue;
      if (isMenuTrigger(node)) return null;
      const root = closestDeep(node, MEDIA_ROOT);
      if (root) return root;
    }
    if (event.target instanceof Element) {
      if (isMenuTrigger(event.target)) return null;
      return closestDeep(event.target, MEDIA_ROOT);
    }
    const touch = event.touches && event.touches[0];
    if (touch && typeof document.elementsFromPoint === 'function') {
      const hits = document.elementsFromPoint(touch.clientX, touch.clientY);
      for (let i = 0; i < hits.length; i++) {
        const node = hits[i];
        if (!(node instanceof Element)) continue;
        if (isMenuTrigger(node)) return null;
        const root = closestDeep(node, MEDIA_ROOT);
        if (root) return root;
      }
    }
    return null;
  }

  function clearMediaHold() {
    if (mediaHold && mediaHold.timerId) clearTimeout(mediaHold.timerId);
    mediaHold = null;
  }

  function emitMediaMenu() {
    const hold = mediaHold;
    if (!hold || hold.triggered) return;
    hold.triggered = true;
    // Deferred from touchstart: the captured root still scrapes fine even
    // if the card re-rendered under the finger during the hold.
    const payload = queuePayloadForRoot(hold.root);
    clearMediaHold();
    mediaContextUntil = Date.now() + MEDIA_CONTEXT_BLOCK_MS;
    const b = bridge();
    if (payload && b && typeof b.showMediaItemMenu === 'function') {
      b.showMediaItemMenu(JSON.stringify(payload));
    } else if (b && typeof b.reportQueueAddFailed === 'function') {
      b.reportQueueAddFailed();
    }
  }

  function interceptMediaHold() {
    document.addEventListener('touchstart', (event) => {
      const root = mediaRootOf(event);
      if (!(root instanceof Element)) { clearMediaHold(); return; }
      if (mediaHold) return;
      // Gate on link presence only; queueItemFor-style scraping is deferred
      // to emitMediaMenu so it never runs on the touch input path.
      if (!cardHasVideoLink(root)) { clearMediaHold(); return; }
      const touch = event.touches && event.touches[0];
      if (!touch) return;
      clearMediaHold();
      mediaHold = {
        root,
        startX: touch.clientX,
        startY: touch.clientY,
        startAt: Date.now(),
        triggered: false,
        timerId: setTimeout(emitMediaMenu, MEDIA_HOLD_MS),
      };
    }, { capture: true, passive: true });
    document.addEventListener('touchmove', (event) => {
      if (!mediaHold) return;
      const touch = event.touches && event.touches[0];
      if (!touch) return;
      if (Math.abs(touch.clientX - mediaHold.startX) > MOVE_CANCEL_PX ||
          Math.abs(touch.clientY - mediaHold.startY) > MOVE_CANCEL_PX) {
        clearMediaHold();
      }
    }, { capture: true, passive: true });
    const end = () => {
      const hold = mediaHold;
      if (!hold) return;
      const duration = Date.now() - hold.startAt;
      if (!hold.triggered && duration >= MEDIA_HOLD_MS) emitMediaMenu();
      else clearMediaHold();
    };
    document.addEventListener('touchend', end, { capture: true, passive: true });
    document.addEventListener('touchcancel', clearMediaHold, { capture: true, passive: true });
    window.addEventListener('blur', clearMediaHold, true);
    document.addEventListener('visibilitychange', clearMediaHold, true);
    const onMenuTrigger = (node) => {
      if (!(node instanceof Element)) return false;
      return isMenuTrigger(node) || closestDeep(node, MENU_TRIGGER);
    };
    document.addEventListener('contextmenu', (event) => {
      // Cheap checks first: only pay the composedPath/mediaRootOf scan when
      // the press is actually on a media card.
      if (mediaHold || Date.now() < mediaContextUntil) {
        event.preventDefault();
        event.stopPropagation();
        return;
      }
      if (mediaRootOf(event) instanceof Element) {
        event.preventDefault();
        event.stopPropagation();
      }
    }, true);
    document.addEventListener('click', (event) => {
      if (Date.now() >= mediaContextUntil) return;
      const path = pathOf(event);
      for (let i = 0; i < path.length; i++) {
        if (onMenuTrigger(path[i])) return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      mediaContextUntil = 0;
    }, true);
  }

  function isSheetItem(element) {
    if (!(element instanceof Element)) return false;
    if (element.matches(SHEET_ITEM)) return true;
    const button = SHEET_BUTTONS.map((selector) => element.querySelector(selector))
      .find((node) => node instanceof Element);
    const text = element.querySelector('.yt-core-attributed-string');
    return button instanceof Element && text instanceof Element;
  }

  function menuBox(origin) {
    if (!(origin instanceof Element)) return null;
    const queue = [origin];
    while (queue.length > 0) {
      const node = queue.shift();
      if (!(node instanceof Element)) continue;
      const direct = Array.from(node.children).filter(isSheetItem);
      if (direct.length > 0) return node;
      queue.push(...Array.from(node.children));
    }
    return null;
  }

  function sheetRoot() {
    return document.querySelector('.bottom-sheet-media-menu-item') ||
      document.querySelector('bottom-sheet-layout') ||
      document.querySelector('ytm-bottom-sheet-renderer') ||
      document.querySelector('ytm-app-bottom-sheet-layout');
  }

  function styleQueueMenuItem(menuItem) {
    const menuButton = SHEET_BUTTONS.map((selector) => menuItem.querySelector(selector))
      .find((node) => node instanceof Element);
    if (!(menuButton instanceof Element)) return false;
    let menuText = menuItem.querySelector('.yt-core-attributed-string') ||
      menuItem.querySelector('[role="text"]') ||
      menuItem.querySelector('.menu-item-text') ||
      menuItem.querySelector('.button-text') ||
      menuItem.querySelector('.ytListItemViewModelTitle');
    if (!(menuText instanceof Element)) {
      menuText = document.createElement('span');
      menuText.className = 'yt-core-attributed-string';
      menuText.setAttribute('role', 'text');
      menuButton.appendChild(menuText);
    }
    const menuSvg = menuItem.querySelector('svg');
    if (menuSvg instanceof SVGElement) {
      setPath(menuItem, QUEUE_ICON);
      fitIcon(menuItem);
    } else {
      const iconHost = menuItem.querySelector('.yt-spec-button-shape-next__icon');
      if (iconHost instanceof Element && !iconHost.querySelector('svg')) {
        iconHost.appendChild(svgIcon(QUEUE_ICON));
      } else if (!menuItem.querySelector('svg')) {
        menuButton.prepend(svgIcon(QUEUE_ICON));
      }
    }
    menuText.textContent = menuLabel();
    menuButton.setAttribute('aria-label', menuLabel());
    return true;
  }

  function ensureQueueMenuItem(sheet, payload) {
    const layout = sheet instanceof Element ? sheet : sheetRoot();
    if (!(layout instanceof Element)) return false;
    const menuContainer = menuBox(layout) ||
      layout.querySelector('.bottom-sheet-media-menu-item') ||
      layout;
    if (!(menuContainer instanceof Element)) {
      warnSelectorMiss('sheet-menu-container');
      return false;
    }
    const existingItems = Array.from(menuContainer.children).filter(
      (child) => child instanceof Element && child.getAttribute('data-lite-queue-menu-item') === 'true',
    );
    existingItems.forEach((node, index) => { if (index > 0) node.remove(); });
    if (!payload || !payload.videoId) {
      existingItems.forEach((node) => node.remove());
      return true;
    }
    let queueMenuElement = existingItems[0];
    if (!(queueMenuElement instanceof Element)) {
      const template = Array.from(menuContainer.children)
        .reverse()
        .find((child) => child instanceof Element &&
          child.getAttribute('data-lite-queue-menu-item') !== 'true' &&
          isSheetItem(child)) || menuContainer.lastElementChild;
      if (!(template instanceof Element)) {
        warnSelectorMiss('sheet-menu-item');
        return false;
      }
      queueMenuElement = template.cloneNode(true);
      queueMenuElement.setAttribute('data-lite-queue-menu-item', 'true');
    }
    queueMenuElement.dataset.liteQueuePayload = JSON.stringify(payload);
    if (!styleQueueMenuItem(queueMenuElement)) return false;
    if (queueMenuElement.parentElement !== menuContainer ||
        queueMenuElement !== menuContainer.firstElementChild) {
      menuContainer.insertBefore(queueMenuElement, menuContainer.firstElementChild);
    }
    return true;
  }

  function scheduleSheetInject(payload) {
    lastSheetPayload = payload;
    // Retry chain spans ~10s; keep the observer backstop alive a bit longer.
    sheetWindowUntil = Date.now() + 12000;
    window.clearTimeout(sheetRetryTimer);
    const version = ++sheetRetryVersion;
    let delayIndex = 0;
    const run = () => {
      if (version !== sheetRetryVersion) return;
      const sheet = sheetRoot();
      if (sheet && ensureQueueMenuItem(sheet, lastSheetPayload)) return;
      if (delayIndex > 8) return;
      sheetRetryTimer = window.setTimeout(run, RETRY_MS[delayIndex] || RETRY_MS[RETRY_MS.length - 1]);
      delayIndex += 1;
    };
    run();
  }

  function menuTriggerOf(event) {
    const path = pathOf(event);
    for (let i = 0; i < path.length; i++) {
      const node = path[i];
      if (!(node instanceof Element)) continue;
      if (node.matches(MENU_TRIGGER) || isMenuTrigger(node)) return node;
      const lockup = node.closest && node.closest('.ytLockupMetadataViewModelMenuButton');
      if (lockup) return lockup;
    }
    const target = event.target instanceof Element ? event.target : null;
    if (!target) return null;
    return closestDeep(target, MENU_TRIGGER);
  }

  function queueItemFromPath(event) {
    const path = pathOf(event);
    for (let i = 0; i < path.length; i++) {
      const node = path[i];
      if (node instanceof Element && node.getAttribute('data-lite-queue-menu-item') === 'true') {
        return node;
      }
    }
    const target = event.target;
    return (target && target.closest && target.closest('[data-lite-queue-menu-item="true"]')) || null;
  }

  /** ⋮ on a media card → YouTube sheet → inject Add to queue as the first row. */
  function interceptMediaMenu() {
    document.addEventListener('click', (event) => {
      if (queueItemFromPath(event)) return;
      const trigger = menuTriggerOf(event);
      if (!trigger) return;
      scheduleSheetInject(queueItemFor(trigger));
    }, true);
    document.addEventListener('click', (event) => {
      const item = queueItemFromPath(event);
      if (!item) return;
      const payload = item.dataset.liteQueuePayload;
      const b = bridge();
      event.preventDefault();
      event.stopImmediatePropagation();
      if (payload && b && typeof b.addToQueue === 'function') {
        b.addToQueue(payload);
      } else if (b && typeof b.reportQueueAddFailed === 'function') {
        b.reportQueueAddFailed();
      }
    }, true);
  }

  function parseTime(text) {
    if (!text) return null;
    if (/^\d+$/.test(text)) return parseInt(text, 10);
    const m = text.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/);
    if (!m || (!m[1] && !m[2] && !m[3])) return null;
    return (parseInt(m[1] || '0') * 3600) + (parseInt(m[2] || '0') * 60) + parseInt(m[3] || '0');
  }

  // Cheap per-frame gate refreshed by sync(): watch-page-only work in the
  // observer waits for it, so mutating feed pages never run those queries.
  let watchPath = false;
  // Keep the page player suppressed even when YouTube re-renders it.
  let layoutRaf = 0;
  let layoutReportTimer = 0;
  function scheduleReportLayout() {
    if (layoutReportTimer) return;
    layoutReportTimer = setTimeout(() => {
      layoutReportTimer = 0;
      if (playerShown) reportLayout();
    }, 120);
  }
  function observePlayer() {
    if (typeof MutationObserver !== 'function') return;
    const target = document.documentElement || document.body;
    if (!target) return;
    new MutationObserver(() => {
      if (layoutRaf) return;
      layoutRaf = requestAnimationFrame(() => {
        layoutRaf = 0;
        // The bottom sheet can open on any page; probe for it only within
        // the short window after a menu-trigger click.
        if (lastSheetPayload && Date.now() < sheetWindowUntil) {
          const sheet = sheetRoot();
          if (sheet) ensureQueueMenuItem(sheet, lastSheetPayload);
        }
        // Watch-only work is gated by watchPath: suppress/skip/report are
        // meaningless on feeds, and ensureWatchQueueButton alone is several
        // document-wide querySelectors per frame.
        if (!watchPath) return;
        if (playerShown) {
          suppressPagePlayer();
          skipAdIfPlaying();
          scheduleReportLayout();
        }
        // The queue button must exist even while the native player is hidden
        // (the page player owns the screen then); the steady-state probe is
        // several document-wide querySelectors, so run it at most every 500 ms.
        const now = Date.now();
        if (now - lastQueueBtnProbe >= 500) {
          lastQueueBtnProbe = now;
          ensureWatchQueueButton();
        }
      });
    }).observe(target, { childList: true, subtree: true });
  }

  // Periodic sync as a backstop for SPA updates the history hooks miss.
  let syncTimer = 0;
  // Fast retry cap (~7.5s) for a bridge that appears quickly; afterwards
  // the retry slows to 3s but never dies, so a very late bridge still binds.
  let initAttempts = 0;
  let bridgeWarned = false;
  function startSyncTimer() {
    if (syncTimer) return;
    syncTimer = setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      if (!playerShown && !watchIdOf(location.href)) return;
      sync();
    }, 1000);
  }

  function init() {
    if (!bridge()) {
      if (initAttempts < 25) {
        initAttempts += 1;
        setTimeout(init, 300);
      } else {
        if (!bridgeWarned) {
          bridgeWarned = true;
          console.warn('[player-hook] bridge unavailable; retrying every 3s');
        }
        setTimeout(init, 3000);
      }
      return;
    }
    interceptTimestamps();
    interceptMediaHold();
    interceptMediaMenu();
    observePlayer();
    window.addEventListener('yt-navigate-finish', () => setTimeout(sync, 80), true);
    window.addEventListener('yt-page-data-updated', () => setTimeout(sync, 80), true);
    window.addEventListener('popstate', () => setTimeout(sync, 80));
    // One of four independent history.pushState/replaceState wrappers
    // (nav.js routes cross-tab, watch-id.js reports id flips,
    // display_dislikes.js rebinds vote buttons); kept separate on purpose.
    const _push = history.pushState;
    history.pushState = function () {
      const r = _push.apply(this, arguments);
      setTimeout(sync, 80);
      return r;
    };
    const _replace = history.replaceState;
    history.replaceState = function () {
      const r = _replace.apply(this, arguments);
      setTimeout(sync, 80);
      return r;
    };
    startSyncTimer();
    sync();
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
