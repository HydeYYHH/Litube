const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
test('sign-in links and history routes stay in their originating document', () => {
  const opened = [], changes = [], handlers = {};
  const context = { URL, location: { href: 'https://m.youtube.com/watch?v=abc', hash: '' },
    history: { pushState: (_, __, url) => changes.push(url), replaceState() {} },
    document: { addEventListener: (type, fn) => { handlers[type] = fn; } },
    Bridge: { openTab: url => opened.push(url) } };
  context.window = context; vm.runInNewContext(fs.readFileSync('app/src/main/assets/script/nav.js','utf8'),context);
  context.history.pushState({}, '', '/signin?continue=watch');
  context.history.pushState({}, '', 'https://accounts.google.com/ServiceLogin');
  let prevented = false;
  handlers.click({ target: { closest: selector => selector === 'a' ? { href: 'https://m.youtube.com/signin', getAttribute: () => '/signin' } : null },
    preventDefault: () => { prevented = true; }, stopImmediatePropagation() {} });
  assert.equal(prevented, false); assert.deepEqual(opened, []);
  assert.deepEqual(changes, ['/signin?continue=watch','https://accounts.google.com/ServiceLogin']);
  context.history.pushState({}, '', '/results?search_query=test');
  assert.deepEqual(opened, ['https://m.youtube.com/results?search_query=test']);
});

test('cross-tab settings closes hash-driven source menus before the tab is opened', () => {
  const events = [], opened = [], handlers = {};
  const location = { href: 'https://m.youtube.com/watch?v=abcdefghijk#bottom-sheet', hash: '#bottom-sheet' };
  let menu = true;
  const context = { URL, location,
    history: { pushState() {}, replaceState(_, __, url) { location.href = url; location.hash = new URL(url).hash; } },
    document: { addEventListener: (type, fn) => { handlers[type] = fn; } },
    HashChangeEvent: class { constructor(type, init) { Object.assign(this, init); this.type = type; } },
    dispatchEvent(event) { events.push(event); menu = location.hash === '#bottom-sheet'; },
    Bridge: { openTab(url) { opened.push({ url, menu }); } } };
  context.window = context;
  const script = fs.readFileSync('app/src/main/assets/script/nav.js', 'utf8');
  vm.runInNewContext(script, context);
  context.history.pushState({}, '', '/select_site');
  assert.deepEqual(opened, [{ url: 'https://m.youtube.com/select_site', menu: false }]);
  assert.equal(events.length, 1);
  assert.equal(events[0].oldURL, 'https://m.youtube.com/watch?v=abcdefghijk#bottom-sheet');
  assert.equal(events[0].newURL, location.href);
  assert.equal(location.hash, '');
  vm.runInNewContext(script, context);
  context.history.pushState({}, '', '/select_site');
  assert.equal(events.length, 1);
});
