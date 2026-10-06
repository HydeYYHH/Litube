const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const res = path.join(__dirname, '../../main/res');
const core = fs.readFileSync(path.join(__dirname, '../../main/assets/script/core.js'), 'utf8');
const catalog = JSON.parse(fs.readFileSync(path.join(__dirname, '../../../../scripts/locales.json'), 'utf8')).locales;
const folders = Object.fromEntries(catalog.map(entry => [entry.tag, entry.resource]));
const labels = { download: 'download', downloaded: 'web_downloaded', addToQueue: 'add_to_queue',
  openWith: 'open_with', chat: 'web_chat', about: 'about', extension: 'extension',
  downloads: 'downloads', closeChat: 'web_close_chat', quality: 'player_info_quality',
  auto: 'player_quality_auto', unavailable: 'info_unavailable', close: 'close' };

function resources(folder) {
  const source = fs.readFileSync(path.join(res, folder, 'strings.xml'), 'utf8');
  const strings = new Map();
  for (const entry of source.matchAll(/<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    assert.ok(!strings.has(entry[1]), `duplicate resource: ${folder}/${entry[1]}`);
    const text = entry[2].replace(/&(amp|lt|gt|quot|apos);/g, (_, entity) =>
      ({ amp: '&', lt: '<', gt: '>', quot: '"', apos: "'" })[entity])
      .replace(/\\([\\'"nt])/g, (_, escaped) => ({ n: '\n', t: '\t' })[escaped] ?? escaped);
    assert.ok(text.trim(), `empty resource: ${folder}/${entry[1]}`);
    assert.doesNotMatch(text, /[\[【［]\s*[\d\u200b-\u200f\u2066-\u2069\s]+[\]】］]/,
      `unexpected draft boundary: ${folder}/${entry[1]}`);
    assert.doesNotMatch(text, /\uFFFD|987650\d{3}/, `broken encoding or format argument: ${folder}/${entry[1]}`);
    strings.set(entry[1], text);
  }
  return strings;
}

function page(lang = '', browserLanguage = 'en') {
  const styles = new Map();
  const root = { lang, appendChild: node => styles.set(node.id, node) };
  const context = { document: { documentElement: root, visibilityState: 'visible', addEventListener() {},
      getElementById: id => styles.get(id), createElement: () => ({}) },
    navigator: { language: browserLanguage }, addEventListener() {}, setInterval() {},
    MutationObserver: class { observe() {} } };
  context.window = context;
  vm.runInNewContext(core, context);
  return { root, text: context.Lite.text };
}

test('all supported Android locales cover the catalog and preserve typed format arguments', () => {
  const base = resources('values');
  const argumentsOf = text => Array.from(text.matchAll(/%\d+\$[dsf]/g), m => m[0]).sort();
  for (const folder of Object.values(folders)) {
    const localized = resources(folder);
    assert.deepEqual([...localized.keys()].sort(), [...base.keys()].sort(), folder);
    for (const [key, text] of localized) {
      assert.deepEqual(argumentsOf(text), argumentsOf(base.get(key)), `${folder}/${key}`);
    }
  }
});

test('Android locale configuration and script variants match the shared catalog', () => {
  assert.equal(new Set(catalog.map(entry => entry.tag.toLowerCase())).size, catalog.length);
  assert.equal(new Set(Object.values(folders)).size, catalog.length);
  const config = fs.readFileSync(path.join(res, 'xml/locales_config.xml'), 'utf8');
  const tags = [...config.matchAll(/android:name="([^"]+)"/g)].map(entry => entry[1]);
  assert.deepEqual(tags, catalog.map(entry => entry.displayTag || entry.tag));
  for (const entry of catalog) {
    for (const mirror of entry.mirrors || []) {
      assert.deepEqual(resources(mirror), resources(entry.resource), mirror);
    }
  }
});

test('injected page labels match Android wording in every supported language', () => {
  for (const [lang, folder] of Object.entries(folders)) {
    const p = page(lang);
    const localized = resources(folder);
    for (const [key, resource] of Object.entries(labels)) {
      assert.equal(p.text(key), localized.get(resource), `${lang}/${key}; run scripts/sync-web-translations.py`);
    }
  }
});

test('page locale handles script tags, regions, underscores, live changes and English fallback', () => {
  const p = page('', 'fr-CA');
  assert.equal(p.text('download'), 'Télécharger');
  for (const tag of ['zh-TW', 'zh-HK', 'zh-MO', 'zh-Hant', 'ZH_hant_TW', 'zh-Hant-HK']) {
    p.root.lang = tag; assert.equal(p.text('download'), '下載', tag);
  }
  for (const tag of ['zh-CN', 'zh-SG', 'zh-Hans-TW', 'zh_Hans_HK']) {
    p.root.lang = tag; assert.equal(p.text('download'), '下载', tag);
  }
  p.root.lang = ' pt_BR '; assert.equal(p.text('download'), 'Baixar');
  p.root.lang = 'en-TW'; assert.equal(p.text('download'), 'Download');
  for (const [legacy, modern] of [['iw-IL', 'he-IL'], ['in-ID', 'id-ID'], ['tl-PH', 'fil-PH'],
    ['no-NO', 'nb-NO'], ['jw-ID', 'jv-ID'], ['sh-RS', 'sr-Latn-RS']]) {
    p.root.lang = legacy; const expected = p.text('download');
    p.root.lang = modern; assert.equal(p.text('download'), expected, legacy);
  }
  p.root.lang = 'pt-Latn-PT'; assert.equal(p.text('download'), 'Transferir');
  p.root.lang = 'sr-Latn-RS'; const latin = p.text('download');
  p.root.lang = 'sr-Cyrl-RS'; assert.notEqual(p.text('download'), latin);
  const cyrillic = p.text('download');
  for (const tag of ['sr-RS-u-nu-latn', 'sr-Cyrl-RS-x-latn']) {
    p.root.lang = tag; assert.equal(p.text('download'), cyrillic, tag);
  }
  p.root.lang = 'pt-PT-u-nu-latn'; assert.equal(p.text('download'), 'Transferir');
  p.root.lang = 'zh-CN-x-hant'; assert.equal(p.text('download'), '下载');
  for (const tag of ['es-MX', 'es_AR', 'es-Latn-CO', 'es-419']) {
    p.root.lang = tag;
    assert.equal(p.text('quality'), resources(folders['es-419']).get('player_info_quality'), tag);
  }
  p.root.lang = 'xx';
  for (const [key, resource] of Object.entries(labels)) assert.equal(p.text(key), resources('values').get(resource));
});

function castPage(folder, manifest = '') {
  const strings = resources(folder);
  const template = fs.readFileSync(path.join(__dirname, '../../main/assets/cast/player.html'), 'utf8');
  const script = template.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace('__MANIFEST_URL__', JSON.stringify(manifest))
    .replace('__SOURCE_ERROR__', JSON.stringify(strings.get('web_source_unavailable')))
    .replace('__PLAYBACK_ERROR__', JSON.stringify(strings.get('player_error')));
  const video = { style: {} }, error = { style: {} };
  let errorHandler;
  const player = { updateSettings() {}, initialize() {}, on: (_, handler) => { errorHandler = handler; } };
  const factory = () => ({ create: () => player }); factory.events = { ERROR: 'error' };
  const context = { document: { getElementById: id => id === 'video' ? video : error, addEventListener() {} },
    dashjs: { MediaPlayer: factory }, setInterval: () => 1, clearInterval() {}, addEventListener() {} };
  context.window = context; vm.runInNewContext(script, context);
  return { video, error, fail: event => errorHandler(event) };
}

test('cast page source failures and playback errors use localized labels in all languages', () => {
  for (const folder of Object.values(folders)) {
    const strings = resources(folder);
    const unavailable = castPage(folder);
    assert.equal(unavailable.error.textContent, strings.get('web_source_unavailable'));
    assert.equal(unavailable.error.style.display, 'block');
    const playing = castPage(folder, 'http://192.168.0.2/manifest.mpd');
    playing.fail({}); assert.equal(playing.error.textContent, strings.get('player_error'));
    playing.fail({ error: { message: 'MEDIA_ERR_DECODE' } });
    assert.equal(playing.error.textContent, strings.get('player_error') + ': MEDIA_ERR_DECODE');
  }
});
