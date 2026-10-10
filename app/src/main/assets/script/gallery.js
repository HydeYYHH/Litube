(() => {
  'use strict';
  if (window.__gallery) return;
  window.__gallery = true;
  const post = 'ytm-backstage-post-renderer,ytd-backstage-post-renderer,ytm-post-renderer,ytd-post-renderer';
  const attachment = 'ytm-backstage-image-renderer,ytd-backstage-image-renderer,ytm-post-multi-image-renderer,ytd-post-multi-image-renderer';
  const single = 'ytm-backstage-image-renderer,ytd-backstage-image-renderer';
  const multi = 'ytm-post-multi-image-renderer,ytd-post-multi-image-renderer';
  const allowed = value => {
    try {
      const url = new URL(value);
      return url.protocol === 'https:' && !url.username && !url.password &&
        (!url.port || url.port === '443') && ['ggpht.com', 'ytimg.com', 'googleusercontent.com']
          .some(host => url.hostname === host || url.hostname.endsWith('.' + host));
    } catch (_) { return false; }
  };
  function source(renderer) {
    // Renderer data retains the full-size source before a lazy image loads.
    const thumbnails = renderer.data?.image?.thumbnails || [];
    const best = thumbnails.filter(item => allowed(item.url))
      .sort((a, b) => (b.width || 0) - (a.width || 0))[0];
    if (best) return best.url;
    const image = renderer.matches?.('img') ? renderer : renderer.querySelector?.('img');
    return [image?.currentSrc, image?.src, image?.getAttribute?.('data-src')].find(allowed);
  }
  function payload(target) {
    const container = target?.closest?.(attachment);
    if (!container || target.closest('a[href],button')) return null;
    const image = target.closest('img') || container.querySelector('img');
    const clicked = target.closest(single) || image?.closest(single) || image;
    const root = container.closest(post) || container.closest(multi);
    if (!clicked || !root) return null;
    // Image wrappers may have role=button; carousel controls keep their action.
    const control = target.closest('[role="button"]');
    if (control && control !== container && !control.contains(image)) return null;
    let renderers = Array.from(root.querySelectorAll(single));
    if (!renderers.length) renderers = Array.from(root.querySelectorAll('img')).filter(i => i.closest(attachment));
    const entries = renderers.map(renderer => ({ renderer, url: source(renderer) })).filter(item => item.url);
    const index = entries.findIndex(item => item.renderer === clicked);
    if (index < 0 || entries.length > 30) return null;
    return { root, urls: entries.map(item => item.url), index };
  }
  const consume = event => { event.preventDefault(); event.stopImmediatePropagation(); };
  let press = null, opened = null, gesture = null;
  function open(item, event) {
    if (!item || !window.Bridge?.gallery) return;
    window.Bridge.gallery(JSON.stringify(item.urls), item.index);
    opened = { root: item.root, at: Date.now() };
    consume(event);
  }
  function start(event, point, id) {
    const item = point && payload(event.target);
    gesture = item ? { root: item.root, at: Date.now(), cancelled: false } : null;
    press = item ? { target: event.target, id,
      x: point.clientX, y: point.clientY, at: Date.now() } : null;
  }
  function cancel() {
    press = null;
    if (gesture) { gesture.cancelled = true; gesture.at = Date.now(); }
  }
  function move(point, id) {
    if (press && press.id === id && (!point ||
        Math.hypot(point.clientX - press.x, point.clientY - press.y) > 12)) cancel();
  }
  function end(event, point, id) {
    move(point, id);
    const tap = press;
    press = null;
    if (!tap || tap.id !== id || Date.now() - tap.at > 500) { cancel(); return; }
    open(payload(tap.target), event);
  }
  // Window capture precedes the watch gesture trap and the page image handlers.
  // Open on release instead of waiting for a synthesized click.
  if (window.PointerEvent) {
    window.addEventListener('pointerdown', event => {
      if (event.isPrimary === false || event.button !== 0) { cancel(); return; }
      start(event, event, event.pointerId);
    }, true);
    window.addEventListener('pointermove', event => move(event, event.pointerId), true);
    window.addEventListener('pointerup', event => end(event, event, event.pointerId), true);
    window.addEventListener('pointercancel', cancel, true);
  } else {
    window.addEventListener('touchstart', event => {
      if (event.touches.length !== 1) { cancel(); return; }
      const point = event.touches[0];
      start(event, point, point?.identifier);
    }, { capture: true, passive: true });
    window.addEventListener('touchmove', event => {
      if (event.touches.length !== 1) cancel();
      else move(event.touches[0], event.touches[0].identifier);
    }, { capture: true, passive: true });
    window.addEventListener('touchend', event => {
      const point = event.changedTouches[0];
      end(event, point, point?.identifier);
    }, { capture: true, passive: false });
    window.addEventListener('touchcancel', cancel, true);
  }
  window.addEventListener('blur', cancel, true);
  window.addEventListener('click', event => {
    if (event.button != null && event.button !== 0) return;
    const item = payload(event.target);
    if (item && event.detail !== 0 && gesture?.cancelled && gesture.root === item.root &&
        Date.now() - gesture.at < 700) { consume(event); return; }
    if (item && opened?.root === item.root && Date.now() - opened.at < 700) {
      consume(event); return; // Compatibility click for an already handled tap.
    }
    open(item, event); // Keyboard / accessibility activation.
  }, true);
})();
