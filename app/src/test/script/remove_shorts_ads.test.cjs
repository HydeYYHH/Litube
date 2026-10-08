const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const filter = fs.readFileSync('app/src/main/assets/script/remove_shorts_ads.js', 'utf8');

test('ad filter preserves real entries and handles empty responses and repeated injection', async () => {
  const original = async () => new Response(JSON.stringify({ entries: [{ id: 1 }, { adClientParams: 'ad' }, { command: { reelWatchEndpoint: { adSlots: [1] } } }], playerResponse: { adSlots: [1], videoDetails: { id: 'video' } } }), { headers: { 'Content-Length': '999' } });
  const context = { fetch: original, document: {}, Response, Headers };
  context.window = context;
  vm.runInNewContext(filter, context); const wrapped = context.fetch; vm.runInNewContext(filter, context);
  assert.equal(context.fetch, wrapped);
  const result = await context.fetch('/youtubei/v1/reel/reel_watch_sequence'); const body = await result.json();
  assert.deepEqual(body.entries, [{ id: 1 }]); assert.equal(body.playerResponse.adSlots, undefined);
  assert.equal(result.headers.get('content-length'), null); assert.equal(body.playerResponse.videoDetails.id, 'video');
  const emptyContext = { fetch: async () => new Response(null, { status: 204 }), document: {}, Response, Headers };
  emptyContext.window = emptyContext; vm.runInNewContext(filter, emptyContext);
  assert.equal((await emptyContext.fetch('/youtubei/v1/reel/reel_watch_sequence')).status, 204);
});
