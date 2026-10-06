(() => {
  'use strict';
  if (window.__gallery) return;
  window.__gallery = true;
  const post = 'ytm-backstage-post-renderer,ytd-backstage-post-renderer,ytm-post-renderer,ytd-post-renderer';
  const attachment = 'ytm-backstage-image-renderer,ytd-backstage-image-renderer,ytm-post-multi-image-renderer,ytd-post-multi-image-renderer';
  document.addEventListener('click', e => {
    const image = e.target?.closest?.('img');
    if (!image || !image.closest(attachment)) return;
    const root = image.closest(post);
    if (!root || image.closest('a[href*="/watch"],a[href*="/shorts"],button,[role="button"]')) return;
    const images = Array.from(root.querySelectorAll('img')).filter(i => i.closest(attachment));
    const urls = images.map(i => i.currentSrc || i.src);
    const index = images.indexOf(image);
    if (index < 0 || urls.length > 30 || !urls.every(u => /^https:\/\/([^/]+\.)?(ggpht\.com|ytimg\.com|googleusercontent\.com)\//i.test(u))) return;
    if (!window.Bridge?.gallery) return;
    e.preventDefault(); e.stopImmediatePropagation();
    window.Bridge.gallery(JSON.stringify(urls), index);
  }, true);
})();
