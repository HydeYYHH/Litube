(() => {
  'use strict';
  if (window.__ads) return;
  window.__ads = true;
  const headers = ['ytm-mobile-topbar-renderer', 'ytm-mobile-topbar', 'ytm-mobile-app-bar', '#masthead'];
  const appLinks = ['a[href^="intent:"][href*="package=com.google.android.youtube;"]',
    'a[href^="vnd.youtube:"]', 'a[href^="youtube:"]'];
  const css = `#masthead-ad,ytd-ad-slot-renderer,ytm-ad-slot-renderer,ad-slot-renderer,
    ytm-companion-ad-renderer,ytd-display-ad-renderer,ytd-promoted-sparkles-web-renderer,
    ytm-promoted-sparkles-web-renderer,yt-mealbar-promo-renderer,
    ytm-app-promo-renderer,ytm-app-install-ad-renderer,ytm-open-in-app-renderer,
    .video-ads.ytp-ad-module,#related #player-ads,#player-ads,.ytp-unmute,
    ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"] {display:none!important}
    ${[].concat(...headers.map(root => appLinks.map(link => root + ' ' + link))).join(',')} {display:none!important}
    tp-yt-paper-dialog:has(yt-mealbar-promo-renderer),
    ytd-rich-item-renderer:has(ytd-display-ad-renderer) {display:none!important}`;
  const appLabels = new Set([
    'open app', 'open in app', 'open in the app', 'open the app', 'use app', 'use the app',
    '打开应用', '在应用中打开', '開啟應用程式', '打開應用程式', '在應用程式中開啟',
    'アプリを開く', 'アプリで開く', '앱 열기', '앱에서 열기',
    'abrir aplicación', 'abrir la aplicación', 'abrir en la aplicación', 'abrir app',
    'abrir no app', 'abrir aplicativo', 'abrir o aplicativo',
    "ouvrir l'application", "ouvrir dans l'application", "ouvrir l'appli", 'ouvrir app',
    'app öffnen', 'in der app öffnen', 'открыть приложение', 'открыть в приложении',
    'apri app', "apri l'app", "apri nell'app", 'buka aplikasi', 'buka di aplikasi',
    'mở ứng dụng', 'mở trong ứng dụng', 'เปิดแอป', 'เปิดในแอป',
    'uygulamayı aç', 'uygulamada aç', 'فتح التطبيق', 'فتح تطبيق', 'فتح في التطبيق',
    'ऐप खोलें', 'ऐप में खोलें', 'openen in app', 'app openen', 'otwórz aplikację', 'otwórz w aplikacji',
    'פתיחת האפליקציה', 'פתח אפליקציה', 'פתיחה באפליקציה', 'باز کردن برنامه', 'باز کردن در برنامه',
    'অ্যাপ খুলুন', 'अ‍ॅप उघडा', 'એપ ખોલો', 'ઍપ ખોલો', 'அப்பைத் திற', 'ஆப்ஸைத் திற',
    'యాప్ తెరవండి', 'ಅಪ್ಲಿಕೇಶನ್ ತೆರೆಯಿರಿ', 'ಆ್ಯಪ್ ತೆರೆಯಿರಿ', 'ആപ്പ് തുറക്കുക',
    'एप खोल्नुहोस्', 'ਐਪ ਖੋਲ੍ਹੋ', 'ایپ کھولیں', 'යෙදුම විවෘත කරන්න',
    'បើកកម្មវិធី', 'ເປີດແອັບ', 'အက်ပ်ဖွင့်ပါ', 'buksan ang app',
    'otevřít aplikaci', 'otvoriť aplikáciu', 'deschide aplicația', 'deschide în aplicație',
    'відкрити додаток', 'відкрити застосунок', 'отвори апликацију', 'otvori aplikaciju',
    'otvorite aplikaciju', 'odpri aplikacijo', 'отвори апликација', 'отваряне на приложението',
    'åpne app', 'åpne appen', 'åbn app', 'åbn appen', 'öppna app', 'öppna appen',
    'avaa sovellus', 'alkalmazás megnyitása', 'abrir a aplicación', 'obre l’aplicació',
    'abrir a aplicação', 'abertura na aplicação', 'app oopmaak', 'fungua programu',
    'άνοιγμα εφαρμογής', 'открыть в приложении', 'aç uygulamayı',
  ].map(normalized));
  function normalized(value) {
    return String(value || '').normalize('NFKC').replace(/[\u200b-\u200f\u202a-\u202e\u2066-\u2069]/g, '')
      .replace(/[’‘]/g, "'").replace(/\s+/g, ' ').trim().toLowerCase();
  }
  const hiddenPromotions = new WeakMap();
  function appLink(href) {
    href = String(href || '').trim();
    if (!href || href[0] === '#') return false;
    if (/^(vnd\.youtube:|youtube:)/i.test(href)) return true;
    if (/^intent:/i.test(href)) return /(?:^|;)package=com\.google\.android\.youtube(?:;|$)/i.test(href);
    try {
      const url = new URL(href, location.href);
      if (url.protocol !== 'https:') return false;
      if (url.hostname === 'play.google.com' && url.pathname === '/store/apps/details')
        return url.searchParams.get('id') === 'com.google.android.youtube';
      if (url.hostname === 'apps.apple.com') return /(?:^|\/)id544007664(?:\/|$)/.test(url.pathname);
      if (url.hostname === 'youtube.com' || url.hostname.endsWith('.youtube.com'))
        return /^mweb.*open_app/.test(url.searchParams.get('feature') || url.searchParams.get('itc_campaign') || '');
    } catch (_) {}
    return false;
  }
  function hideAppPromotions() {
    const surfaces = headers.concat(['[role="dialog"]', '[role="menu"]', 'bottom-sheet-layout']);
    for (const header of document.querySelectorAll(surfaces.join(','))) {
      for (const control of header.querySelectorAll('a,button,[role="button"]')) {
        const labels = [control.getAttribute('aria-label'), control.textContent].map(normalized);
        const button = control.closest?.('yt-list-item-view-model,ytm-menu-service-item-renderer,ytm-menu-navigation-item-renderer') || control;
        if (appLink(control.getAttribute('href') || '') || labels.some(label => appLabels.has(label))) {
          if (!hiddenPromotions.has(button)) hiddenPromotions.set(button, {
            display: button.style.getPropertyValue('display'), priority: button.style.getPropertyPriority('display'),
            aria: button.getAttribute('aria-hidden'),
          });
          button.style.setProperty('display', 'none', 'important');
          button.setAttribute('aria-hidden', 'true');
        } else if (hiddenPromotions.has(button)) {
          const previous = hiddenPromotions.get(button);
          if (previous.display) button.style.setProperty('display', previous.display, previous.priority);
          else button.style.removeProperty('display');
          if (previous.aria === null) button.removeAttribute('aria-hidden');
          else button.setAttribute('aria-hidden', previous.aria);
          hiddenPromotions.delete(button);
        }
      }
    }
  }
  function installStyle() {
    if (!document.getElementById('lite-ad-rules') && document.documentElement) {
      const style = document.createElement('style'); style.id = 'lite-ad-rules';
      style.textContent = css; document.documentElement.append(style);
    }
  }
  function ensure() {
    installStyle();
    hideAppPromotions();
    const player = document.querySelector('.ad-showing,.ad-interrupting');
    if (player) {
      const skip = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button');
      if (skip) skip.click();
      const video = player.querySelector('video');
      if (video && Number.isFinite(video.duration) && video.duration > 0) video.currentTime = video.duration;
    }
    return true;
  }
  installStyle();
  if (window.Lite && typeof window.Lite.module === 'function') {
    window.Lite.module('ads', ensure);
  } else {
    // Keep structural rules active when the page scheduler cannot start.
    let pending = false;
    const observer = new MutationObserver(() => {
      if (pending) return;
      pending = true;
      setTimeout(() => { pending = false; tick(); }, 100);
    });
    const timer = setInterval(tick, 1000);
    function tick() {
      if (window.Lite && typeof window.Lite.module === 'function') {
        observer.disconnect(); clearInterval(timer);
        window.Lite.module('ads', ensure);
      } else if (!document.hidden) ensure();
    }
    observer.observe(document, { childList: true, subtree: true, characterData: true,
      attributes: true, attributeFilter: ['href', 'aria-label'] });
    tick();
  }
})();
