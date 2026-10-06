const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/script/ads.js', 'utf8');
function button(label, href = '', aria = null) {
  const attributes = { href, 'aria-label': aria }; const styles = {}, priorities = {};
  return { textContent: label, attributes,
    style: { setProperty: (k,v,p='') => { styles[k]=v;priorities[k]=p; },
      getPropertyValue: k => styles[k] || '', getPropertyPriority: k => priorities[k] || '',
      removeProperty: k => { delete styles[k];delete priorities[k]; } },
    getAttribute: k => attributes[k] ?? null, setAttribute: (k,v) => { attributes[k]=v; },
    removeAttribute: k => { delete attributes[k]; }, hidden: () => styles.display === 'none' };
}
function page(controls=[], options={}) {
  let run, css='', clicks=0, observe, timer, delay;
  const video={duration:12,currentTime:0};
  const context={URL,location:{href:'https://m.youtube.com/watch?v=abc'},document:{hidden:false,
    querySelectorAll:()=>[{querySelectorAll:()=>controls}],
    querySelector: selector => !options.ad ? null : selector.includes('ad-showing') ? {querySelector:()=>video} : {click:()=>clicks++},
    getElementById:()=>css?{}:null,createElement:()=>({}),documentElement:{append:style=>{css=style.textContent;} } },
    MutationObserver:class {constructor(fn){observe=fn;}observe(){}disconnect(){}},
    setInterval:fn=>{timer=fn;return 1;},clearInterval:()=>{timer=null;},
    setTimeout:fn=>{delay=fn;},
  };
  if(!options.noCore) context.Lite={module:(_,fn)=>{run=fn;}};
  context.window=context;vm.runInNewContext(script,context);
  return {context,video,run:()=>run(),mutate:()=>{observe();delay();},tick:()=>timer?.(),
    css:()=>css,clicks:()=>clicks,reinject:()=>vm.runInNewContext(script,context)};
}
test('application links hide promotions independently of their language and preserve other links',()=>{
  const labels=['アプリを開く','앱 열기','فتح التطبيق','ऐप खोलें','Unbekannte Sprache','🦉'];
  const controls=labels.map(x=>button(x,'intent://watch#Intent;package=com.google.android.youtube;end'));
  controls.push(button('?', 'vnd.youtube://watch?v=abc'),button('?', 'https://play.google.com/store/apps/details?id=com.google.android.youtube&hl=ar'),
    button('?', 'https://apps.apple.com/jp/app/youtube/id544007664'),
    button('?', 'https://m.youtube.com/watch?v=abc&feature=mweb_c3_open_app_123'),
    button('Account','intent://accounts.google.com#Intent;package=com.android.chrome;end'),
    button('Other app','https://play.google.com/store/apps/details?id=com.android.chrome'),
    button('Other app','intent://watch#Intent;package=com.google.android.youtube.fake;end'),
    button('Lookalike','https://youtube.com.evil.test/watch?feature=mweb_c3_open_app'));
  const p=page(controls);p.run();
  assert.deepEqual(controls.map(x=>x.hidden()),[true,true,true,true,true,true,true,true,true,true,false,false,false,false]);
});
test('localized fallback labels and visible text work without hiding ordinary translated controls',()=>{
  const labels=['OPEN IN APP','在应用中打开','打開應用程式','アプリで開く','앱에서 열기','Abrir la aplicación',
    'Ouvrir l’application','Ouvrir app','فتح تطبيق','In der App öffnen','Abrir no app','Открыть приложение','فتح التطبيق',
    'ऐप खोलें','เปิดแอป','Mở ứng dụng','Uygulamayı aç','Buka aplikasi','Apri l’app','Otwórz aplikację',
    '\u200fفتح التطبيق\u200f','ＯＰＥＮ　ＡＰＰ', 'פתיחת האפליקציה','باز کردن برنامه','অ্যাপ খুলুন',
    'अ‍ॅप उघडा','ஆப்ஸைத் திற','యాప్ తెరవండి','ಆ್ಯಪ್ ತೆರೆಯಿರಿ','ആപ്പ് തുറക്കുക','ایپ کھولیں',
    'යෙදුම විවෘත කරන්න','បើកកម្មវិធី','ເປີດແອັບ','အက်ပ်ဖွင့်ပါ',
    'Buksan ang app','Otevřít aplikaci','Otvoriť aplikáciu','Deschide aplicația',
    'Відкрити застосунок','Отвори апликацију','Otvori aplikaciju','Öppna appen','Åpne appen',
    'Avaa sovellus','Alkalmazás megnyitása','Obre l’aplicació','Fungua programu','Άνοιγμα εφαρμογής'];
  const controls=labels.map(x=>button(x));
  const normal=['Sign in','登录','登入','ログイン','로그인','Iniciar sesión','Se connecter','Anmelden',
    'تسجيل الدخول','כניסה','ورود','সাইন ইন','Conectare','Σύνδεση',
    'Settings','设置','設定','Open with','Tap to unmute'].map(x=>button(x));
  const visibleText=button('Open app','','Unknown translated accessibility label');
  const p=page([...controls,...normal,visibleText]);p.run();p.run();
  for (const control of controls) assert.ok(control.hidden(), control.textContent);
  assert.ok(normal.every(x=>!x.hidden()));assert.ok(visibleText.hidden());
});
test('blank and fragment links do not inherit promotional query parameters from the page',()=>{
  const controls=[button('Account'),button('Search','#search')];const p=page(controls);
  p.context.location.href='https://m.youtube.com/watch?v=abc&feature=mweb_c3_open_app_1';p.run();
  assert.ok(controls.every(x=>!x.hidden()));
});
test('a reused promotion control restores its prior display and accessibility when it becomes a sign-in control',()=>{
  const control=button('Open app');control.style.setProperty('display','inline-flex');control.setAttribute('aria-hidden','false');
  const p=page([control]);p.run();assert.ok(control.hidden());
  control.textContent='ログイン';p.run();assert.equal(control.style.getPropertyValue('display'),'inline-flex');
  assert.equal(control.attributes['aria-hidden'],'false');
});
test('structural prompt rules install before scheduling and remain separate from unsupported relational selectors',()=>{
  const p=page();const plain=p.css().split('tp-yt-paper-dialog:has')[0];
  assert.ok(plain.includes('.ytp-unmute'));assert.ok(plain.includes('package=com.google.android.youtube'));
  assert.ok(!p.css().includes('.ytp-mute-button'));assert.ok(!p.css().includes('[aria-label='));
});
test('ad skipping is scoped to active advertising playback and reinjection stays single',()=>{
  const p=page([],{ad:true});p.reinject();p.run();assert.equal(p.clicks(),1);assert.equal(p.video.currentTime,12);
});
test('structural hiding runs without the page scheduler and hands over when the scheduler starts',()=>{
  const control=button('Unknown','intent://watch#Intent;package=com.google.android.youtube;end');
  const p=page([control],{noCore:true});assert.ok(control.hidden());assert.ok(p.css().includes('.ytp-unmute'));
  control.textContent='Account';control.attributes.href='';p.mutate();assert.ok(!control.hidden());
  let registered=0;p.context.Lite={module:()=>registered++};p.tick();p.reinject();assert.equal(registered,1);
});
