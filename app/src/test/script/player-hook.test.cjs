const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function page() {
  const events = new Map();
  let layout = null;
  const player = {
    style: {}, classList: { contains: () => false },
    querySelector: () => null,
    getBoundingClientRect: () => ({ top: 32, height: 180 }),
  };
  const context = {
    URL, console: { warn() {} }, Element: class {},
    location: { href: 'https://m.youtube.com/watch?v=aaaaaaaaaaa', origin: 'https://m.youtube.com', search: '?v=aaaaaaaaaaa' },
    navigator: { language: 'en' },
    history: { pushState() {}, replaceState() {} },
    document: {
      readyState: 'loading', documentElement: { lang: 'en' },
      addEventListener: (name, handler) => events.set(name, handler),
      querySelector: (selector) => selector === '#movie_player' ? player : null,
      getElementById: () => null,
    },
    lite: { play() { layout = null; }, setPlayerLayout(top, height) { layout = [top, height]; }, setPageHasPlaylist() {} },
    setTimeout() {}, setInterval() { return 1; },
    addEventListener() {},
    MutationObserver: class { observe() {} },
  };
  context.window = context;
  context.top = context;
  context.self = context;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/assets/script/player-hook.js'), 'utf8'), context);
  events.get('DOMContentLoaded')();
  return { context, layout: () => layout };
}

test('playlist sentinel is encoded only by evaluateJavascript, not by the script', () => {
  const { context } = page();
  assert.equal(context.__litePlaylistNav(-1), 'missing-playlist');
});

test('first layout is reported after play resets native geometry', () => {
  assert.deepEqual(page().layout(), [32, 180]);
});

test('playlist next navigates to the target URL and returns a plain sentinel', () => {
  const { context } = page();
  context.ytInitialData = { contents: { singleColumnWatchNextResults: { playlist: { playlist: {
    contents: ['aaaaaaaaaaa', 'bbbbbbbbbbb'].map(videoId => ({ playlistPanelVideoRenderer: {
      videoId, navigationEndpoint: { commandMetadata: { webCommandMetadata: { url: `/watch?v=${videoId}&list=PLtest` } } },
    } })),
  } } } } };
  assert.equal(context.__litePlaylistNav(1), 'navigating');
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
  assert.equal(context.__litePlaylistNav(1), 'playlist-end');
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
  assert.equal(context.__liteShortsNav(1), 'ok');
  assert.deepEqual(moved, [[0, 800]]);
  assert.equal(context.__liteShortsNav(-1), 'ok');
  assert.deepEqual(moved[1], [0, -800]);
});

test('mediaHref copies hash t= onto the play URL query', () => {
  const { context } = page();
    context.location.href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa#t=1m30s';
  assert.equal(
    context.__liteMediaHref(),
    'https://m.youtube.com/watch?v=aaaaaaaaaaa&t=1m30s',
  );
  context.location.href = 'https://m.youtube.com/watch?v=aaaaaaaaaaa?t=90#t=1';
  assert.equal(
    context.__liteMediaHref(),
    'https://m.youtube.com/watch?v=aaaaaaaaaaa?t=90',
  );
});
