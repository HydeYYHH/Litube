/**
 * Settings-page entries — about, downloads, extension — cloned from the
 * ytm-settings template row; the return value is the report the native
 * side logs.
 */
(() => {
  'use strict';
  const report = { ok: false, skipped: false, failures: [] };

  if (!window.Lite) {
    report.failures.push({ element: 'runtime', reason: 'lite core not loaded' });
    return report;
  }

  function isSettingsPage() {
    try {
      if (document.querySelector('ytm-settings')) return true;
      if (document.querySelector('[data-mode="settings"]')) return true;
      return (location.pathname || '').toLowerCase().includes('/select_site');
    } catch {
      return false;
    }
  }

  if (!isSettingsPage()) {
    report.ok = true;
    report.skipped = true;
    report.reason = 'not_settings_page';
    return report;
  }

  // Same glyph family as the settings rows themselves.
  const ICONS = {
    about:
      'M444-288h72v-240h-72v240Zm35.79-312q15.21 0 25.71-10.29t10.5-25.5q0-15.21-10.29-25.71t-25.5-10.5q-15.21 0-25.71 10.29t-10.5 25.5q0 15.21 10.29 25.71t25.5 10.5Zm.49 504Q401-96 331-126t-122.5-82.5Q156-261 126-330.96t-30-149.5Q96-560 126-629.5q30-69.5 82.5-122T330.96-834q69.96-30 149.5-30t149.04 30q69.5 30 122 82.5T834-629.28q30 69.73 30 149Q864-401 834-331t-82.5 122.5Q699-156 629.28-126q-69.73 30-149 30Zm-.28-72q130 0 221-91t91-221q0-130-91-221t-221-91q-130 0-221 91t-91 221q0 130 91 221t221 91Zm0-312Z',
    download:
      'M480-336 288-528l51-51 105 105v-246h72v246l105-105 51 51-192 192ZM264-192q-30 0-51-21t-21-51v-72h72v72h432v-72h72v72q0 30-21 51t-51 21H264Z',
    extension:
      'M384-144H216q-29.7 0-50.85-21.15Q144-186.3 144-216v-168q40-2 68-29.5t28-66.5q0-39-28-66.5T144-576v-168q0-29.7 21.15-50.85Q186.3-816 216-816h168q0-40 27.77-68 27.78-28 68-28Q520-912 548-884.16q28 27.84 28 68.16h168q29.7 0 50.85 21.15Q816-773.7 816-744v168q40 0 68 27.77 28 27.78 28 68Q912-440 884.16-412q-27.84 28-68.16 28v168q0 29.7-21.15 50.85Q773.7-144 744-144H576q-2-40-29.38-68t-66.5-28q-39.12 0-66.62 28-27.5 28-29.5 68Zm-168-72h112q20-45 61.5-70.5T480-312q49 0 90.5 25.5T632-216h112v-240h72q9.6 0 16.8-7.2 7.2-7.2 7.2-16.8 0-9.6-7.2-16.8-7.2-7.2-16.8-7.2h-72v-240H504v-72q0-9.6-7.2-16.8-7.2-7.2-16.8-7.2-9.6 0-16.8 7.2-7.2 7.2-7.2 16.8v72H216v112q45 20 70.5 61.5T312-480q0 50.21-25.5 91.6Q261-347 216-328v112Zm264-264Z',
  };

  function bridgeAction(action) {
    const b = Lite.bridge();
    if (b && typeof b[action] === 'function') b[action]();
  }

  const BUTTONS = [
    { id: 'downloaderButton', icon: ICONS.download, label: 'downloads', action: 'download' },
    { id: 'extensionButton', icon: ICONS.extension, label: 'extension', action: 'extension' },
    { id: 'aboutButton', icon: ICONS.about, label: 'about', action: 'about' },
  ];

  function build(template, def) {
    const button = template.cloneNode(true);
    button.id = def.id;
    Lite.strip(button);
    const text = button.querySelector('.ytAttributedStringHost, .yt-core-attributed-string');
    const label = Lite.text(def.label);
    if (text) text.textContent = label;
    button.setAttribute('aria-label', label);
    if (!Lite.icon(button, def.icon)) return null;
    Lite.fit(button);
    button.addEventListener('click', (event) => {
      event.preventDefault();
      event.stopImmediatePropagation();
      bridgeAction(def.action);
    }, true);
    return button;
  }

  /** Idempotent: existing rows short-circuit; downloads/extension go
   *  before the template row, about at the tail. */
  function ensure(settings, template) {
    for (const def of BUTTONS) {
      if (document.getElementById(def.id)) continue;
      const button = build(template, def);
      if (!button) {
        report.failures.push({ element: 'template_button', reason: 'clone or insert failed' });
        return false;
      }
      if (def.id === 'aboutButton') {
        const children = settings.children;
        settings.insertBefore(button, children[children.length - 1]);
      } else {
        settings.insertBefore(button, template);
      }
    }
    return true;
  }

  function injectOnce() {
    const settings = document.querySelector('ytm-settings');
    const template = settings && settings.firstElementChild;
    if (!(settings instanceof Element) || !(template instanceof Element) ||
        !template.querySelector('svg, c3-icon')) {
      report.reason = 'missing_settings_root';
      report.failures.push({ element: 'ytm-settings', reason: 'settings list root missing' });
      return false;
    }
    report.reason = 'injected';
    return ensure(settings, template);
  }

  // Bounded retry for late settings DOM; later navigations re-inject.
  // The report the native side logs must reflect the final state, so a
  // retry that lands clears the earlier failure.
  report.ok = injectOnce();
  if (!report.ok) {
    Lite.retry(() => {
      if (!injectOnce()) return false;
      report.ok = true;
      report.failures = [];
      report.reason = 'injected';
      return true;
    }, 6);
  }
  return report;
})()
