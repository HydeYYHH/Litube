const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/shorts-quality.js', 'utf8');
function page(host) {
  const video = { getBoundingClientRect: () => ({width:300,height:500,top:0,bottom:500}), closest: () => host };
  const context = { location: { pathname: '/shorts/fixture' }, innerHeight: 800,
    document: { querySelectorAll: () => [video], querySelector: () => null, addEventListener() {} },
    addEventListener() {}, Lite: { module() {}, text: key => ({auto:'自动',unavailable:'不可用'})[key] } };
  context.window = context;
  vm.runInNewContext(script.replace("Lite.module('shorts-quality', ensure);",
    "window.access={levels,label,apply,player};Lite.module('shorts-quality', ensure);"), context);
  return context.access;
}
test('quality choices come from the active player and reject unavailable selections', () => {
  const applied = [];
  const host = { getAvailableQualityLevels: () => ['hd720','hd720','small','auto',null],
    setPlaybackQualityRange: (min,max) => applied.push([min,max]) };
  const api = page(host);
  assert.equal(api.player(), host);
  assert.equal(JSON.stringify(api.levels(host)), JSON.stringify(['hd720','small']));
  assert.equal(api.apply(host, 'hd1080'), false); assert.deepEqual(applied, []);
  assert.equal(api.apply(host, 'hd720'), true); assert.deepEqual(applied, [['hd720','hd720']]);
  assert.equal(api.apply(host, 'default'), true); assert.deepEqual(applied[1], ['default','default']);
});
test('legacy quality setters and API failures degrade without invented choices', () => {
  let chosen;
  const host = { getAvailableQualityLevels: () => ['small'], setPlaybackQuality: level => { chosen=level; } };
  const api = page(host);
  assert.equal(api.apply(host, 'small'), true); assert.equal(chosen, 'small');
  host.setPlaybackQuality = () => { throw Error('not ready'); };
  assert.equal(api.apply(host, 'small'), false);
  host.getAvailableQualityLevels = () => { throw Error('not ready'); };
  assert.equal(api.levels(host).length, 0); assert.equal(api.apply(null, 'default'), false);
});
test('labels use actual quality metadata and translated automatic mode', () => {
  const api = page(null);
  assert.equal(api.label('auto',null), '自动'); assert.equal(api.label('hd1080',null), '1080p');
  assert.equal(api.label('highres',{getAvailableQualityData:()=>[{quality:'highres',qualityLabel:'2160p60'}]}), '2160p60');
  assert.equal(api.label(null,null), '不可用');
});
