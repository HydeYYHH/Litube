(() => {
  'use strict';
  if (window.__shortsQuality) return;
  window.__shortsQuality = true;
  const rows = 'ytm-menu-service-item-renderer,yt-list-item-view-model,toggleable-list-item-view-model';
  const mark = '[data-injected="shorts-quality"]';
  const qualityIcon = 'M120-160v-640h720v640H120Zm80-80h560v-480H200v480Zm80-80v-240h80v80h80v-80h80v240h-80v-80h-80v80h-80Zm280 0v-240h120q40 0 40 40v160q0 40-40 40H560Zm80-80h40v-80h-40v80Z';
  const heights = { tiny: 144, small: 240, medium: 360, large: 480, hd720: 720,
    hd1080: 1080, hd1440: 1440, hd2160: 2160, hd2880: 2880, hd4320: 4320 };
  let preference = null, lastPlayer = null, lastVideo = '', panel = null;
  const isShorts = () => /^\/shorts(?:\/|$)/.test(location.pathname);
  function player() {
    for (const video of document.querySelectorAll('video')) {
      const rect = video.getBoundingClientRect();
      if (rect.width < 40 || rect.height < 40 || rect.bottom <= 0 || rect.top >= innerHeight) continue;
      const host = video.closest('.html5-video-player');
      if (typeof host?.getAvailableQualityLevels === 'function') return host;
    }
    const host = document.querySelector('#movie_player');
    if (typeof host?.getAvailableQualityLevels === 'function') return host;
    return document.querySelector('#player')?.getPlayer?.() || null;
  }
  function levels(host) {
    try {
      return [...new Set(host?.getAvailableQualityLevels?.() || [])]
        .filter(value => typeof value === 'string' && value !== 'auto' && value !== 'default');
    } catch (_) { return []; }
  }
  function label(level, host) {
    if (level === 'auto' || level === 'default') return Lite.text('auto');
    try {
      const data = host?.getAvailableQualityData?.()?.find(item => item.quality === level || item.qualityId === level);
      if (data?.qualityLabel) return String(data.qualityLabel);
    } catch (_) {}
    return heights[level] ? heights[level] + 'p' : level || Lite.text('unavailable');
  }
  function apply(host, level) {
    if (!host || (level !== 'default' && !levels(host).includes(level))) return false;
    try {
      if (typeof host.setPlaybackQualityRange === 'function') host.setPlaybackQualityRange(level, level);
      else if (typeof host.setPlaybackQuality === 'function') host.setPlaybackQuality(level);
      else return false;
      return true;
    } catch (_) { return false; }
  }
  function close() {
    if (!panel) return;
    for (const [node, display] of panel.children) node.style.display = display;
    panel.node.remove();
    panel = null;
  }
  function open(container) {
    close();
    const host = player(), available = levels(host), url = location.pathname;
    const node = document.createElement('div');
    node.dataset.injected = 'shorts-quality-panel';
    node.setAttribute('role', 'dialog');
    node.setAttribute('aria-label', Lite.text('quality'));
    node.style.cssText = 'padding:0 12px 12px;max-height:70vh;overflow-y:auto;color:inherit;font:400 14px sans-serif';
    const header = document.createElement('div');
    header.style.cssText = 'display:flex;align-items:center;justify-content:space-between;padding:0 4px';
    const title = document.createElement('span');
    title.textContent = Lite.text('quality');
    title.style.cssText = 'font-size:18px;font-weight:500';
    const done = document.createElement('button');
    done.textContent = '×'; done.setAttribute('aria-label', Lite.text('close'));
    done.style.cssText = 'width:48px;height:48px;background:none;border:0;color:inherit;font:24px sans-serif';
    done.onclick = close;
    header.append(title, done); node.appendChild(header);
    const writable = typeof host?.setPlaybackQualityRange === 'function' || typeof host?.setPlaybackQuality === 'function';
    if (!available.length || !writable) {
      const text = document.createElement('p'); text.textContent = Lite.text('unavailable'); node.appendChild(text);
    } else {
      const selected = available.includes(preference) ? preference : 'default';
      for (const level of ['default', ...available]) {
        const button = document.createElement('button');
        button.dataset.quality = level;
        button.textContent = label(level, host);
        button.setAttribute('role', 'radio');
        button.setAttribute('aria-checked', String(level === selected));
        button.style.cssText = 'display:flex;align-items:center;gap:16px;width:100%;min-height:48px;padding:12px 16px;border:0;border-radius:8px;background:none;color:inherit;text-align:start;font:inherit';
        if (level === selected) button.style.background = 'rgba(128,128,128,.18)';
        button.onclick = () => {
          if (!isShorts() || location.pathname !== url || player() !== host) { close(); return; }
          if (apply(host, level)) { preference = level; close(); }
          else { button.textContent = Lite.text('unavailable'); button.disabled = true; }
        };
        node.appendChild(button);
      }
    }
    const children = Array.from(container.children).map(child => [child, child.style.display]);
    for (const [child] of children) child.style.display = 'none';
    container.appendChild(node);
    panel = { node, container, children, url, host };
    done.focus();
  }
  function ensure() {
    if (!isShorts()) { close(); document.querySelectorAll(mark).forEach(node => node.remove()); return true; }
    const host = player();
    if (panel && (panel.url !== location.pathname || panel.host !== host || !panel.container.isConnected)) close();
    if (host && (host !== lastPlayer || location.pathname !== lastVideo)) {
      const available = levels(host);
      if (!preference || (available.length && apply(host, available.includes(preference) ? preference : 'default'))) {
        lastPlayer = host; lastVideo = location.pathname;
      }
    }
    for (const sheet of document.querySelectorAll('bottom-sheet-layout,ytm-bottom-sheet-renderer,ytm-app-bottom-sheet-layout,.menu-content[role="dialog"],ytm-menu-popup-renderer')) {
      // Some custom sheet hosts use display:contents and have no own bounds.
      const template = Array.from(sheet.querySelectorAll(rows)).find(item => {
        const rect = item.getBoundingClientRect();
        return !item.closest('[data-injected]') && rect.width > 0 && rect.height > 0;
      });
      if (!template?.parentElement) continue;
      const container = template.parentElement;
      let item = container.querySelector(mark);
      if (!item) {
        item = template.cloneNode(true); Lite.strip(item);
        item.dataset.injected = 'shorts-quality';
        item.removeAttribute('id');
        const text = item.querySelector('.yt-core-attributed-string,[role="text"],.menu-item-text,.ytListItemViewModelTitle');
        const button = item.querySelector('button,[role="button"]');
        if (!text || !button) continue;
        text.textContent = Lite.text('quality');
        item.querySelectorAll('.ytListItemViewModelTrailing,.menu-item-secondary-text').forEach(node => node.remove());
        button.setAttribute('aria-label', Lite.text('quality'));
        Lite.menuIcon(item, qualityIcon, template);
        const nativeRows = Array.from(container.children).filter(row => !row.hasAttribute('data-injected'));
        const controls = nativeRows.filter(row => row.querySelector('.ytListItemViewModelSelectionText,.menu-item-secondary-text'));
        const cancel = nativeRows.find(row => row.querySelector('.menu-cancel-button'));
        container.insertBefore(item, controls.length ? controls[controls.length - 1].nextSibling : cancel || null);
      }
      const text = item.querySelector('.yt-core-attributed-string,[role="text"],.menu-item-text,.ytListItemViewModelTitle');
      const button = item.querySelector('button,[role="button"]');
      const title = Lite.text('quality');
      if (text && text.textContent !== title) text.textContent = title;
      if (button?.getAttribute('aria-label') !== title) button?.setAttribute('aria-label', title);
    }
    return true;
  }
  window.addEventListener('click', event => {
    const item = event.target?.closest?.(mark);
    if (!item || !isShorts()) return;
    event.preventDefault(); event.stopImmediatePropagation(); open(item.parentElement);
  }, true);
  document.addEventListener('keydown', event => {
    if (panel && event.key === 'Escape') { event.preventDefault(); close(); }
  });
  document.addEventListener('visibilitychange', () => { if (document.hidden) close(); });
  window.addEventListener('tabVisibilityChanged', event => { if (!event.detail?.active) close(); });
  Lite.module('shorts-quality', ensure);
})();
