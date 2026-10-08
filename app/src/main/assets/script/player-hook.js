(() => {
  'use strict';
  // Top frame only: an iframe's URL rarely resolves a watch id, and its
  // sync() would hide the native player.
  if (window.top !== window.self) return;
  if (window.__playerHook) return;
  window.__playerHook = true;
  if (!window.Lite) return;

  const MEDIA_HOLD_MS = 420;
  const MOVE_CANCEL_PX = 12;
  // Begin the same native task on the selected link, before navigation and
  // watch-page rendering. Window capture runs before nav.js can stop the
  // document's click handlers.
  window.addEventListener('click', event => {
    if (event.defaultPrevented || event.button !== 0) return;
    const link = event.target.closest && event.target.closest('a[href]');
    const href = link && link.href;
    const id = href && Lite.id(href);
    if (!id || id === Lite.id()) return;
    if (/\/shorts(?:\/|$)/.test(new URL(href, location.href).pathname)) return;
    try { Lite.bridge()?.prepare?.(href); } catch {}
  }, true);
  const MEDIA_CONTEXT_BLOCK_MS = 400;
  let activeId = null;
  let playerShown = false;
  let bridgeWarned = false;

  const MEDIA_BASE_URL = 'https://m.youtube.com';
  const THUMB_BASE_URL = 'https://i.ytimg.com/vi/';
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

  // A/B tests reshuffle these selector groups; when one stops matching,
  // the hooks degrade silently — say so once so logcat shows why.
  const warnedGroups = new Set();
  function warnSelectorMiss(group) {
    if (warnedGroups.has(group)) return;
    warnedGroups.add(group);
    console.warn('[player-hook] selector group no longer matches: ' + group);
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
      // Native playback streams its own URLs: pause, mute, and hide so the
      // page player cannot race the audio focus.
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

  // A drag over the watch content must not reach YouTube's fullscreen
  // gesture logic on the suppressed page player; taps and scrolling need
  // no JS events. The scheduler re-runs this after every re-render.
  const GESTURE_TRAP = 'gestureTrap';
  const GESTURE_TYPES =
    ['touchmove', 'touchend', 'touchcancel', 'pointermove', 'pointerup', 'pointercancel'];

  function trapWatchGestures() {
    for (const node of document.querySelectorAll('.watch-below-the-player')) {
      if (!(node instanceof Element) || node.dataset[GESTURE_TRAP] === 'true') continue;
      node.dataset[GESTURE_TRAP] = 'true';
      for (const type of GESTURE_TYPES) {
        node.addEventListener(type, (event) => event.stopPropagation(),
          { capture: true, passive: true });
      }
    }
  }

  let lastLayoutKey = '';
  let lastPlaylist = false;
  const COMPACT_CLASS = 'lite-compact-player';

  function syncCompactPlayer(b) {
    const root = document.documentElement;
    if (!root) return;
    const compact = !!(Lite.id() && !new URL(location.href).pathname.startsWith('/shorts/') &&
      typeof b.isPlayerCompact === 'function' && b.isPlayerCompact());
    if (compact && !document.getElementById('lite-compact-player-style')) {
      const style = document.createElement('style');
      style.id = 'lite-compact-player-style';
      style.textContent = `
        html.${COMPACT_CLASS} .player-container,
        html.${COMPACT_CLASS} #player-container-id,
        html.${COMPACT_CLASS} ytm-player {
          height: 0 !important;
          min-height: 0 !important;
          padding-bottom: 0 !important;
          overflow: hidden !important;
        }
        html.${COMPACT_CLASS} .watch-below-the-player {
          padding-top: 0 !important;
          margin-top: 0 !important;
        }
      `;
      root.appendChild(style);
    }
    root.classList.toggle(COMPACT_CLASS, compact);
  }

  let observedPlayer = null;
  const layoutObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(ensure) : null;

  function reportLayout(b) {
    const root = document.documentElement;
    const compact = root && root.classList.contains(COMPACT_CLASS);
    try {
      const p = pagePlayer();
      if (!p || (typeof b.setPlayerBounds !== 'function' && typeof b.setPlayerLayout !== 'function')) return;
      if (layoutObserver && observedPlayer !== p) {
        layoutObserver.disconnect();
        layoutObserver.observe(p);
        observedPlayer = p;
      }
      // Probe the original slot, including after rotation or a page re-render.
      // Never feed our collapsed height back into the native layout policy.
      if (compact) root.classList.remove(COMPACT_CLASS);
      const r = p.getBoundingClientRect();
      const viewport = window.visualViewport;
      const viewportWidth = Math.round(viewport ? viewport.width : window.innerWidth);
      const left = Math.round(r.left - (viewport ? viewport.offsetLeft : 0));
      const top = Math.round(r.top - (viewport ? viewport.offsetTop : 0));
      const width = Math.round(r.width);
      const height = Math.round(r.height);
      if (height <= 0) return;
      const key = [left, top, width, height, viewportWidth].join('x');
      if (key === lastLayoutKey) return;
      lastLayoutKey = key;
      if (typeof b.setPlayerBounds === 'function' && width > 0 && viewportWidth > 0) {
        b.setPlayerBounds(left, top, width, height, viewportWidth);
      } else b.setPlayerLayout(top, height);
    } catch {} finally {
      if (compact) root.classList.add(COMPACT_CLASS);
    }
  }

  function hideNative(b) {
    if (!playerShown) return;
    playerShown = false;
    activeId = null;
    lastLayoutKey = '';
    lastPlaylist = false;
    if (typeof b.hidePlayer === 'function') b.hidePlayer();
    if (typeof b.setPageHasPlaylist === 'function') {
      try { b.setPageHasPlaylist(false); } catch {}
    }
  }

  function reportPlaylist(b) {
    if (typeof b.setPageHasPlaylist !== 'function') return;
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

  function sync(b) {
    if (Lite.isShorts()) {
      hideNative(b);
      restorePagePlayer();
      skipAdIfPlaying();
      syncCompactPlayer(b);
      return;
    }
    trapWatchGestures();
    const id = Lite.id();
    if (id) {
      suppressPagePlayer();
      skipAdIfPlaying();
      if (id !== activeId || !playerShown) {
        lastLayoutKey = '';
        activeId = id;
        playerShown = true;
        // Native decides the surface: full overlay vs. mini-player.
        if (typeof b.play === 'function') b.play(mediaHref());
      }
      reportLayout(b);
      reportPlaylist(b);
    } else {
      hideNative(b);
      restorePagePlayer();
    }
    syncCompactPlayer(b);
  }

  /** Returns false until the bridge shows up; the backoff chain retries. */
  function ensure() {
    const bridge = Lite.bridge();
    if (!bridge) {
      if (!bridgeWarned) {
        bridgeWarned = true;
        console.warn('[player-hook] bridge unavailable; retrying');
      }
      return false;
    }
    sync(bridge);
    return true;
  }
  window.__syncPlayerCompact = ensure;

  // Timestamp links (?t=1m30s / &t=90) → seek the native player instead of navigating.
  function interceptTimestamps() {
    document.addEventListener('click', (e) => {
      const a = e.target.closest && e.target.closest('a[href]');
      if (!a) return;
      const href = a.getAttribute('href') || '';
      if (!/[?&#]t=/.test(href)) return;
      const targetId = Lite.id(href);
      if (!targetId || targetId !== activeId) return;
      const m = href.match(/[?&#]t=([^&]+)/);
      const seconds = parseTime(m && m[1]);
      if (seconds == null) return;
      const b = Lite.bridge();
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

  /** URL of the entry relative to the current one, or a JSON sentinel the
   *  host maps to WebView back-navigation. */
  function playlistEntryUrl(dir) {
    const list = playlistData();
    if (!list || !Array.isArray(list.contents) || list.contents.length === 0) {
      return '"missing-playlist"';
    }
    const id = Lite.id();
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
        // Full page load: an SPA hop would need YouTube's router state
        // replayed; a reload re-runs the document-start hooks.
        location.href = url;
        return true;
      }
    } catch {}
    return false;
  }

  // Host → page: navigate the YouTube playlist relatively.
  window.__playlistNav = function (dir) {
    const jsonUrl = playlistEntryUrl(dir);
    if (goToEntryUrl(jsonUrl)) return 'navigating';
    return JSON.parse(jsonUrl);
  };

  // -- long-press media card → native menu --

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
    return node.matches('button') && ENGLISH_MENU_ARIA.includes(aria);
  }

  function textOf(root, selectors) {
    for (const selector of selectors) {
      const el = queryDeep(root, selector);
      const text = el && el.textContent && el.textContent.replace(/\s+/g, ' ').trim();
      if (text) return text;
    }
    return '';
  }

  function mediaLinkOf(root) {
    for (const selector of MEDIA_LINKS) {
      const link = (root instanceof Element && root.matches(selector))
        ? root
        : queryDeep(root, selector);
      const href = link && (link.getAttribute('href') || link.href);
      if (href && Lite.id(href)) return href;
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
        for (const sibling of siblings) {
          const nested = sibling.querySelector && (
            sibling.querySelector(MEDIA_ROOT) || sibling.querySelector(MEDIA_INFO)
          );
          if (nested) return nested;
        }
      }
      node = parent;
    }
    return null;
  }

  /** Long-press path: the card root is captured at touchstart and the
   *  payload is only scraped once the hold actually fires. */
  function queuePayloadForRoot(root) {
    const href = mediaLinkOf(root);
    const videoId = href && Lite.id(href);
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

  // Touchstart is the input path: one cheap selector decides whether to
  // arm; scraping waits for the hold.
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

  let mediaHold = null;
  let mediaContextUntil = 0;

  function clearMediaHold() {
    if (mediaHold && mediaHold.timerId) clearTimeout(mediaHold.timerId);
    mediaHold = null;
  }

  function emitMediaMenu() {
    const hold = mediaHold;
    if (!hold || hold.triggered) return;
    hold.triggered = true;
    // Scrapes the root captured at touchstart; re-renders under the
    // finger during the hold are fine.
    const payload = queuePayloadForRoot(hold.root);
    clearMediaHold();
    mediaContextUntil = Date.now() + MEDIA_CONTEXT_BLOCK_MS;
    const b = Lite.bridge();
    if (payload && b && typeof b.showMediaItemMenu === 'function') {
      b.showMediaItemMenu(JSON.stringify(payload));
    } else if (b && typeof b.reportQueueAddFailed === 'function') {
      b.reportQueueAddFailed();
    }
  }

  function mediaRootOf(event) {
    const path = pathOf(event);
    for (const node of path) {
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
      for (const node of hits) {
        if (!(node instanceof Element)) continue;
        if (isMenuTrigger(node)) return null;
        const root = closestDeep(node, MEDIA_ROOT);
        if (root) return root;
      }
    }
    return null;
  }

  function interceptMediaHold() {
    document.addEventListener('touchstart', (event) => {
      const root = mediaRootOf(event);
      if (!(root instanceof Element)) { clearMediaHold(); return; }
      if (mediaHold) return;
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
      // Only pay the mediaRootOf scan when the press is on a media card.
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
      for (const node of pathOf(event)) {
        if (onMenuTrigger(node)) return;
      }
      event.preventDefault();
      event.stopImmediatePropagation();
      mediaContextUntil = 0;
    }, true);
  }

  // -- ⋮ overflow sheet → inject Add to queue as the first row --

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
      document.querySelector('.menu-content[role="dialog"]') ||
      document.querySelector('bottom-sheet-layout') ||
      document.querySelector('ytm-bottom-sheet-renderer') ||
      document.querySelector('ytm-app-bottom-sheet-layout');
  }

  function styleQueueMenuItem(menuItem, template) {
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
    const label = Lite.text('addToQueue');
    Lite.menuIcon(menuItem, Lite.queueIcon, template);
    menuText.textContent = label;
    menuButton.setAttribute('aria-label', label);
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
    const marked = '[data-injected="queue-item"]';
    const existing = Array.from(menuContainer.children).filter(
      (child) => child instanceof Element && child.getAttribute('data-injected') === 'queue-item',
    );
    existing.forEach((node, index) => { if (index > 0) node.remove(); });
    if (!payload || !payload.videoId) {
      existing.forEach((node) => node.remove());
      return true;
    }
    let item = existing[0];
    const template = Array.from(menuContainer.children).reverse().find(
      child => child instanceof Element && !child.hasAttribute('data-injected') && isSheetItem(child));
    if (!(item instanceof Element)) {
      if (!(template instanceof Element)) {
        warnSelectorMiss('sheet-menu-item');
        return false;
      }
      item = template.cloneNode(true);
      item.setAttribute('data-injected', 'queue-item');
    }
    item.dataset.queuePayload = JSON.stringify(payload);
    if (!styleQueueMenuItem(item, template)) return false;
    if (item.parentElement !== menuContainer || item !== menuContainer.firstElementChild) {
      menuContainer.insertBefore(item, menuContainer.firstElementChild);
    }
    return true;
  }

  let sheetRetry = null;
  let lastSheetPayload = null;

  function scheduleSheetInject(payload) {
    lastSheetPayload = payload;
    if (sheetRetry) sheetRetry.cancel();
    sheetRetry = Lite.retry(() => {
      const sheet = sheetRoot();
      return !!(sheet && ensureQueueMenuItem(sheet, lastSheetPayload));
    }, 9);
  }

  function menuTriggerOf(event) {
    for (const node of pathOf(event)) {
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
    for (const node of pathOf(event)) {
      if (node instanceof Element && node.getAttribute('data-injected') === 'queue-item') {
        return node;
      }
    }
    const target = event.target;
    return (target && target.closest && target.closest('[data-injected="queue-item"]')) || null;
  }

  /** ⋮ on a media card → YouTube sheet → inject Add to queue as the first row. */
  function interceptMediaMenu() {
    document.addEventListener('click', (event) => {
      if (queueItemFromPath(event)) return;
      const trigger = menuTriggerOf(event);
      if (!trigger) return;
      const root = mediaCardOf(trigger);
      scheduleSheetInject(root ? queuePayloadForRoot(root) : null);
    }, true);
    document.addEventListener('click', (event) => {
      const item = queueItemFromPath(event);
      if (!item) return;
      const payload = item.dataset.queuePayload;
      const b = Lite.bridge();
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

  function init() {
    interceptTimestamps();
    interceptMediaHold();
    interceptMediaMenu();
    window.addEventListener('resize', ensure);
    window.addEventListener('scroll', ensure, { passive: true });
    if (window.visualViewport) {
      window.visualViewport.addEventListener('resize', ensure);
      window.visualViewport.addEventListener('scroll', ensure);
    }
    Lite.module('player', ensure);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
