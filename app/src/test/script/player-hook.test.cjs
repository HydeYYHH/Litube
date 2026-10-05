const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page(href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa', withNavigation = false) {
  const events = new Map();
  const documentListeners = [];
  const windowListeners = [];
  const bridgeEvents = [];
  let layout = null;
  const classes = new Set();
  const styles = new Map();
  const root = {
    lang: 'en',
    classList: {
      contains: (name) => classes.has(name),
      add: (name) => classes.add(name),
      remove: (name) => classes.delete(name),
      toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); },
    },
    appendChild(node) { styles.set(node.id, node); },
  };
  const player = {
    style: {}, classList: { contains: () => false },
    querySelector: () => null,
    getBoundingClientRect: () => ({ top: 32, height: 180 }),
  };
  const context = {
    URL, console: { warn() {} }, Element: class {},
    location: { href, origin: 'https://m.youtube.com', search: new URL(href).search },
    navigator: { language: 'en' },
    history: { pushState() {}, replaceState() {} },
    document: {
      readyState: 'loading', documentElement: root,
      visibilityState: 'visible',
      addEventListener(name, handler, capture) { events.set(name, handler); documentListeners.push({ name, handler, capture }); },
      querySelector: (selector) => selector === '#movie_player' ? player : null,
      querySelectorAll: () => [],
      getElementById: (id) => styles.get(id) || null,
      createElement: () => ({}),
    },
    lite: { play() { layout = null; }, setPlayerLayout(top, height) { layout = [top, height]; }, setPageHasPlaylist() {},
      prepare() { bridgeEvents.push('prepare'); }, openTab() { bridgeEvents.push('open'); } },
    setTimeout() {}, clearTimeout() {}, setInterval() { return 1; },
    requestAnimationFrame: (fn) => fn(),
    addEventListener(name, handler, capture) { windowListeners.push({ name, handler, capture }); },
    MutationObserver: class { observe() {} },
  };
  context.window = context;
  context.Bridge = context.lite;
  context.top = context;
  context.self = context;
  const assets = path.join(__dirname, '../../main/assets/script');
  vm.runInNewContext([
    fs.readFileSync(path.join(assets, 'core.js'), 'utf8'),
    ...(withNavigation ? [fs.readFileSync(path.join(assets, 'nav.js'), 'utf8')] : []),
    fs.readFileSync(path.join(assets, 'player-hook.js'), 'utf8'),
  ].join('\n'), context);
  events.get('DOMContentLoaded')();
  function click(href) {
    const anchor = { href, getAttribute: () => href };
    const event = { button: 0, defaultPrevented: false, stopped: false,
      target: { closest: selector => selector === 'a' || selector === 'a[href]' ? anchor : null },
      preventDefault() { this.defaultPrevented = true; }, stopImmediatePropagation() { this.stopped = true; },
    };
    for (const listener of [...windowListeners.filter(l => l.capture), ...documentListeners.filter(l => l.capture),
      ...documentListeners.filter(l => !l.capture)]) {
      if (listener.name !== 'click') continue;
      listener.handler(event);
      if (event.stopped) break;
    }
  }
  return { context, player, classes, styles, layout: () => layout, click, bridgeEvents };
}

test('home navigation prepares the selected video before document capture stops propagation', () => {
  const fixture = page('https://m.youtube.com/', true);
  fixture.click('https://m.youtube.com/watch?v=bbbbbbbbbbb');
  assert.deepEqual(fixture.bridgeEvents, ['prepare', 'open']);
});

test('playlist sentinel is encoded only by evaluateJavascript, not by the script', () => {
  const { context } = page();
  assert.equal(context.__playlistNav(-1), 'missing-playlist');
});

test('first layout is reported after play resets native geometry', () => {
  assert.deepEqual(page().layout(), [32, 180]);
});

test('compact watch frees the slot but reports its original geometry after resize', () => {
  const fixture = page();
  let compact = true;
  let height = 460;
  fixture.context.lite.isPlayerCompact = () => compact;
  fixture.player.getBoundingClientRect = () => ({
    top: 48, height: fixture.classes.has('lite-compact-player') ? 0 : height,
  });
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  assert.deepEqual(fixture.layout(), [48, 460]);
  height = 576;
  fixture.context.__syncPlayerCompact();
  assert.deepEqual(fixture.layout(), [48, 576]);
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  assert.equal(fixture.styles.size, 1);
  compact = false;
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
});

test('compact slot is removed on navigation and never applied to Shorts', () => {
  const fixture = page();
  fixture.context.lite.isPlayerCompact = () => true;
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), true);
  fixture.context.location.href = 'https://m.youtube.com/shorts/aaaaaaaaaaa';
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
  fixture.context.location.href = 'https://m.youtube.com/';
  fixture.context.__syncPlayerCompact();
  assert.equal(fixture.classes.has('lite-compact-player'), false);
});

test('playlist next navigates to the target URL and returns a plain sentinel', () => {
  const { context } = page();
  context.ytInitialData = { contents: { singleColumnWatchNextResults: { playlist: { playlist: {
    contents: ['aaaaaaaaaaa', 'bbbbbbbbbbb'].map(videoId => ({ playlistPanelVideoRenderer: {
      videoId, navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: `/watch?v=${videoId}&list=PLtest` } } },
    } })),
  } } } } };
  assert.equal(context.__playlistNav(1), 'navigating');
  assert.equal(context.location.href, 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest');
});

test('playlist next at the last item stops instead of wrapping', () => {
  const { context } = page();
  context.location.href = 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest';
  context.location.search = '?v=bbbbbbbbbbb&list=PLtest';
  context.ytInitialData = { contents: { singleColumnWatchNextResults: { playlist: { playlist: {
    contents: ['aaaaaaaaaaa', 'bbbbbbbbbbb'].map(videoId => ({ playlistPanelVideoRenderer: {
      videoId, navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: `/watch?v=${videoId}&list=PLtest` } } },
    } })),
  } } } } };
  assert.equal(context.__playlistNav(1), 'playlist-end');
  assert.equal(context.location.href, 'https://m.youtube.com/watch?v=bbbbbbbbbbb&list=PLtest');
});

test('shortsNav scrolls the reel container by one viewport', () => {
  const { context } = page();
  const moved = [];
  context.document.querySelector = (selector) => {
    if (String(selector).includes('ytm-shorts')) {
      return { clientHeight: 800, scrollBy(x, y) { moved.push([x, y]); } };
    }
    return null;
  };
  assert.equal(context.__shortsNav(1), 'ok');
  assert.deepEqual(moved, [[0, 800]]);
  assert.equal(context.__shortsNav(-1), 'ok');
  assert.deepEqual(moved[1], [0, -800]);
});

test('mediaHref copies hash t= onto the play URL query', () => {
  const { context } = page();
    context.location.href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa#t=1m30s';
  assert.equal(
    context.__mediaHref(),
    'https://m.youtube.com/watch?v=aaaaaaaaaaa&t=1m30s',
  );
  context.location.href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa?t=90#t=1';
  assert.equal(
    context.__mediaHref(),
    'https://m.youtube.com/watch?v=aaaaaaaaaaa?t=90',
  );
});
