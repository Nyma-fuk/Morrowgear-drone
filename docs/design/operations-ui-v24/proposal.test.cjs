const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const P = require('./policy.js');
const root = path.resolve(__dirname, '../../..');
const source = fs.readFileSync(path.join(__dirname, 'app.js'), 'utf8');

// These are logic/HTML-contract tests, not browser rendering or Minecraft checks.
function harness() {
  const elements = new Map();
  const document = { querySelector(selector) {
    if (!elements.has(selector)) elements.set(selector, { innerHTML:'', value:'', open:false,
      showModal(){ this.open=true; }, close(){ this.open=false; } });
    return elements.get(selector);
  }, addEventListener() {} };
  const context = vm.createContext({ window:{OperationsPolicy:P}, document, Date, Set });
  vm.runInContext(source, context);
  return { run: code=>vm.runInContext(code,context), html:()=>elements.get('#app').innerHTML, elements };
}
test('five primary destinations retain every legacy page and new operations views', () => {
  assert.deepEqual(P.nav.map(n=>n.label),['作戦','部隊','基地・補給','履歴','設定']);
  const views=P.nav.flatMap(n=>n.views);
  for(const view of ['地図','任務','戦線','機体','Wing','装備','Dock','補給網','回収','母艦','機器','バイザー','通信']) assert(views.includes(view),view);
});
test('selection summary uses full memberships despite hidden role rows', () => {
  const units=P.roster(100), selected=new Set([1,2,3,4,5,6,7,8,9]);
  assert.deepEqual(P.summary(units,selected,new Set([1,7])),{count:9,full:1,partial:1,ungrouped:0,hidden:7});
});
test('wing capacity is exactly eight and failed joins do not mutate membership', () => {
  const units=P.roster(17);
  assert.equal(P.joinWing(units,17,'W01'),'Wing上限8機');
  assert.equal(units[16].wing,'W03');
  units[7].wing='';
  assert.equal(P.joinWing(units,17,'W01'),'');
  assert.equal(units.filter(u=>u.wing==='W01').length,8);
});
test('target mode snapshots recipients and distinguishes changes', () => {
  const selection=new Set([8,2]); const mode=P.arm('move',selection);
  assert(P.sameRecipients(mode,new Set([2,8])));
  selection.add(9); assert(!P.sameRecipients(mode,selection));
  assert.deepEqual(mode.recipients,[2,8]);
});
test('empty, disconnected and unauthorized selections cannot issue commands', () => {
  const units=P.roster(100);
  assert.equal(P.availability(units,new Set()).common,'対象未選択');
  assert.equal(P.availability(units,new Set([1]),false).common,'通信断');
  assert.equal(P.availability(units,new Set([1]),true,false).common,'操作権限なし');
  assert.equal(P.availability(units,new Set([2])).guard,'警備機が必要');
  assert.equal(P.availability(units,new Set([1])).role,'着艦が必要');
});
test('pagination reaches the final aircraft without losing selection', () => {
  for(const n of [0,1,8,9,100,256]) {
    const list=P.roster(n); const p=P.page(list,999,8);
    assert.equal(p.start,Math.max(0,n-8)); assert.equal(p.items.length,Math.min(n,8));
    if(n)assert.equal(p.items.at(-1).id,n);
    assert.equal(P.page(list,-999,8).start,0);
  }
});
test('one prioritized message is stable and routine completions are quiet by default', () => {
  const events=[{sequence:2,priority:1,kind:'warning'}, {sequence:1,priority:0,kind:'warning'}, {sequence:3,priority:3,kind:'completion'}];
  for(let i=0;i<100;i++)assert.equal(P.topMessage(events).sequence,1);
  events[1].ack=true; assert.equal(P.topMessage(events).sequence,2);
  events[0].ack=true; assert.equal(P.topMessage(events),null);
  assert.equal(P.topMessage(events,true).sequence,3);
});
test('Dock has precisely 27 unique service slots and 36 player slots', () => {
  assert.equal(P.slotLabels.length,27);
  assert.equal(new Set(P.slotLabels).size,27);
  const h=harness(); h.run("state.surface='dock';render()");
  assert.deepEqual([...h.html().matchAll(/data-slot="(\d+)"/g)].map(m=>Number(m[1])),Array.from({length:63},(_,i)=>i));
  for(const label of ['装備・燃料・弾薬','回収出力','共通補給','所持品'])assert(h.html().includes(label));
});
test('inventory pickup, split, placement and cancel conserve items', () => {
  const h=harness(); const before=h.run('state.inventory.reduce((n,s)=>n+(s?.n||0),0)');
  h.run('inventoryClick(18,true);inventoryClick(20,true);restorePicked()');
  assert.equal(h.run('state.inventory[20].n'),1);
  assert.equal(h.run('state.inventory.reduce((n,s)=>n+(s?.n||0),0)'),before);
  assert.equal(h.run('state.picked'),null);
});
test('output-only slots and common supply reject incompatible items', () => {
  const h=harness();h.run('inventoryClick(28);inventoryClick(18)');
  assert.equal(h.run('state.inventory[18].id'),'power_cell');
  assert.equal(h.run('state.picked.id'),'scout_module');
  h.run('restorePicked();inventoryClick(27);inventoryClick(10)');
  assert.equal(h.run('state.inventory[10]'),null);
  assert.equal(h.run('state.picked.id'),'power_cell');
});
test('recipient or navigation changes cancel armed map commands', () => {
  const h=harness(); h.run("handleAction('move');state.selected.add(99);selectionChanged()");
  assert.equal(h.run('state.mode'),null);
  h.run("handleAction('move');handleAction('cancel')");
  assert.equal(h.run('state.mode'),null);
});
test('store confirmation is bound to exact recipients and time', () => {
  const h=harness(); const before=h.run('state.events.length');
  h.run("handleAction('store');state.selected.add(99);handleAction('confirm-store')");
  assert.equal(h.run('state.events.length'),before);
  h.run("handleAction('store');state.storeConfirm.expires=0;handleAction('confirm-store')");
  assert.equal(h.run('state.events.length'),before);
  h.run("handleAction('store');handleAction('confirm-store')");
  assert.equal(h.run('state.events.length'),before+1);
});
test('ungrouped aircraft are not merged into an implicit Wing', () => {
  const h=harness();h.run("state.units[0].wing='';state.units[1].wing='';selectUnit(1,{})");
  assert.equal(h.run('state.selected.size'),1);
});
test('all views generate real existing model and HMI references, without new assets', () => {
  const h=harness();const html=[];
  for(const nav of P.nav)for(const view of nav.views){h.run(`state.nav=${JSON.stringify(nav.id)};state.view=${JSON.stringify(view)};render()`);html.push(h.html());}
  for(const surface of ['dock','hud']){h.run(`state.surface='${surface}';render()`);html.push(h.html());}
  const images=[...html.join('').matchAll(/<img[^>]+src="([^"]+)"/g)].map(m=>m[1]);
  assert(images.length>20);
  for(const src of new Set(images))assert(fs.existsSync(path.resolve(__dirname,src)),src);
  for(const page of html)assert(!page.includes('undefined'),'undefined UI content');
});
test('proposal uses exactly the existing HmiArt palette and no text downscaling', () => {
  const art=fs.readFileSync(path.join(root,'src/client/java/jp/morrowgear/drone/client/HmiArt.java'),'utf8');
  const css=fs.readFileSync(path.join(__dirname,'style.css'),'utf8');
  for(const color of ['111416','1a1e21','23282b','394347','edf3f2','a6b5b8','62d8df','ffbf69','ff7b75','8cddb4']) {
    assert(art.toLowerCase().includes(color));assert(css.includes('#'+color));
  }
  assert(!/font-size:\s*(?:[0-9]|1[01])px/.test(css));
  assert(!/font-size:[^;}]*(?:vw|vh)/.test(css));
  assert(!/letter-spacing:\s*-/.test(css));
});
test('proposal does not call network, TTS, or automatically cycle focus', () => {
  assert(!/\b(?:fetch|XMLHttpRequest|WebSocket|speechSynthesis|setInterval)\b/.test(source));
  const h=harness(); h.run("state.surface='hud';handleAction('focus-unit');handleAction('pin')");
  for(let i=0;i<100;i++)h.run('render()');
  assert.equal(h.run('state.focus'),1);assert.equal(h.run('state.pinned'),true);
  assert.equal((h.html().match(/aria-label="優先通知"/g)||[]).length,1);
});
