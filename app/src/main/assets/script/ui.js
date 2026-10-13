(() => {
  'use strict';
  if (window.top !== window.self || !window.Lite || window.__ui) return;
  window.__ui = true;

  const back = history.back;
  const go = history.go;
  const MENU = 'bottom-sheet-layout,ytm-bottom-sheet-renderer,ytm-app-bottom-sheet-layout,ytm-menu-popup-renderer,.menu-content[role="dialog"]';
  const ITEMS = 'ytm-menu-service-item-renderer,yt-list-item-view-model,toggleable-list-item-view-model,.bottom-sheet-media-menu-item,[data-injected="queue-item"]';
  const SEARCH = 'ytm-searchbox,ytm-searchbox-renderer,.searchbox,ytm-search-suggestions-section';
  let generation = null;
  let reported = null;
  let scheduled = false;

  function visible(node) {
    if (!(node instanceof Element) || node.closest('[hidden],[aria-hidden="true"]')) return false;
    const style = getComputedStyle(node);
    const rect = node.getBoundingClientRect();
    return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
  }

  function menu() {
    return Array.from(document.querySelectorAll(MENU)).find(node => visible(node) && node.querySelector(ITEMS)) || null;
  }

  function search() {
    const hash = /^#(?:searching|search)(?:[=&]|$)/.test(location.hash);
    return Array.from(document.querySelectorAll(SEARCH)).find(node => visible(node) &&
      (hash || node.contains(document.activeElement) && document.activeElement.matches('input'))) || null;
  }

  function kind() {
    return menu() ? 'media_menu' : search() ? 'search' : 'none';
  }

  function geometry() {
    const sheet = menu();
    const content = sheet?.querySelector('.menu-content,[role="dialog"]');
    const nodes = sheet ? content && visible(content) ? [content] : Array.from(sheet.querySelectorAll(ITEMS))
      : search() ? Array.from(document.querySelectorAll(SEARCH)) : [];
    const regions = nodes.filter(visible).slice(0, 32).map(node => {
      const r = node.getBoundingClientRect();
      return [r.left, r.top, r.right, r.bottom].map(edge => Math.round(edge * 100) / 100);
    });
    return JSON.stringify({ viewportWidth: innerWidth, regions });
  }

  function sync() {
    scheduled = false;
    if (document.readyState === 'loading') return;
    const bridge = Lite.bridge();
    if (!bridge || typeof bridge.setTransientUi !== 'function') return;
    if (generation === null) generation = bridge.currentDocumentGeneration();
    const value = kind();
    const bounds = geometry();
    const stamp = value + bounds;
    if (reported === stamp) return;
    reported = stamp;
    bridge.setTransientUi(value, generation, bounds);
  }

  function schedule() {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(sync);
  }

  function dismiss(target = null) {
    const sheet = target ? null : menu();
    const panel = target || sheet || search();
    if (!visible(panel)) return false;
    const selectors = target
      ? '.dialog-close-button,.dialog-dismiss-button,.dismiss-button,.close-button,button[aria-label="Close"],button[aria-label="Dismiss"]'
      : sheet
      ? '.bottom-sheet-overlay,.bottom-sheet-scrim,.popup-backdrop,.menu-overlay,tp-yt-iron-overlay-backdrop'
      : '.searchbox-back-button,ytm-back-button button,button[aria-label="Back"]';
    const control = Array.from((sheet ? document : panel).querySelectorAll(selectors)).find(visible);
    if (control) {
      control.click();
      schedule();
      return true;
    }
    if (target && typeof target.close === 'function') {
      target.close();
      schedule();
      return true;
    }
    // Close hash-based panels through the page history.
    if (/^#(?:searching|search|menu|dialog|bottom-sheet)(?:[=&]|$)/.test(location.hash) && history.length > 1) {
      back.call(history);
      schedule();
      return true;
    }
    if (sheet) {
      // Dispatch dismissal to the sheet's backdrop handler.
      const content = sheet.querySelector('.menu-content,[role="dialog"]') || sheet;
      const rect = content.getBoundingClientRect();
      const x = Math.max(1, Math.min(innerWidth - 1, innerWidth / 2));
      const y = rect.top > 1 ? 1 : Math.min(innerHeight - 1, rect.bottom + 1);
      if (sheet !== content) {
        // Target the sheet backdrop itself; the content behind it may be a video link.
        sheet.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, clientX: x, clientY: y }));
        schedule();
        return true;
      }
    }
    return false;
  }

  Lite.ui = { kind, dismiss, originalBack: () => back.call(history), originalGo: delta => go.call(history, delta) };
  function init() {
    new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['hidden', 'aria-hidden', 'class', 'style', 'open'] });
    sync();
  }
  window.addEventListener('hashchange', schedule);
  window.addEventListener('popstate', schedule);
  window.addEventListener('resize', schedule);
  document.addEventListener('scroll', schedule, true);
  document.addEventListener('transitionend', schedule, true);
  document.addEventListener('animationend', schedule, true);
  window.addEventListener('tabVisibilityChanged', () => { reported = null; schedule(); });
  document.addEventListener('focusin', schedule, true);
  document.addEventListener('focusout', schedule, true);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, { once: true });
  else init();
})();
