const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const root=path.resolve(__dirname,'..');
const P=require(path.join(root,'docs/design/equipment-hmi-v23/hmi/state.cjs'));
const results=[];
function test(name,fn){try{fn();results.push({name,pass:true});}catch(e){results.push({name,pass:false,error:e.message});}}
function single(){const s=P.create();s.selected=new Set(['MG-001']);return s;}
for(const count of [0,1,8,9,24,100,256]) {
  test(`${count} units: unique IDs and eight-slot Wing capacity`,()=>{const s=P.create(count);assert.equal(new Set(s.units.map(u=>u.id)).size,count);s.wings.forEach(w=>assert.ok(s.units.filter(u=>u.wing===w.id).length<=8));});
  test(`${count} units: scoped all selection`,()=>{const s=P.create(count);P.selectScope(s,'all');assert.equal(s.selected.size,count);});
  test(`${count} units: HUD bounded markers and fronts`,()=>{const s=P.create(count);for(const width of[390,768,1280,1920]){const h=P.hud(s,width);assert.ok(h.markers<=8);assert.ok(h.fronts.length<=3);assert.equal(h.unitCount,count);}});
  test(`${count} units: home bindings unique and service references valid`,()=>{const s=P.create(count);assert.equal(new Set(s.units.map(u=>u.dock)).size,count);s.stations[0].slots.filter(Boolean).forEach(id=>assert.ok(s.units.some(u=>u.id===id)));});
}
for(const role of P.roles)test(`filter ${role} preserves existing selections`,()=>{const s=P.create(100);const ids=[...s.selected];s.role=role;assert.ok(P.visible(s).every(u=>u.role===role));assert.deepEqual([...s.selected],ids);});
test('filtered all differs from global all',()=>{const s=P.create(100);s.role='scout';P.selectScope(s,'filtered');assert.equal(s.selected.size,17);P.selectScope(s,'all');assert.equal(s.selected.size,100);});
test('page selection does not choose hidden rows',()=>{const s=P.create(100);s.page=2;P.selectScope(s,'page');assert.deepEqual([...s.selected],s.units.slice(16,24).map(u=>u.id));});
test('sort keeps IDs selected',()=>{const s=P.create();const ids=[...s.selected];s.sort='flight';P.visible(s);assert.deepEqual([...s.selected],ids);});
test('shift range across pages',()=>{const s=P.create();P.select(s,'MG-002');P.select(s,'MG-013',{range:true});assert.equal(s.selected.size,12);});
test('range anchor hidden by filter',()=>{const s=P.create();P.select(s,'MG-002');s.role='security';P.select(s,'MG-005',{range:true});assert.equal(s.selected.size,2);});
test('checkbox toggle independent of ordering',()=>{const s=P.create();P.select(s,'MG-003',{toggle:true});assert.ok(!s.selected.has('MG-003'));s.sort='role';P.select(s,'MG-003',{toggle:true});assert.ok(s.selected.has('MG-003'));});
test('full Wing rejects join atomically',()=>{const s=P.create();const before=JSON.stringify(s.units);assert.equal(P.moveToWing(s,['MG-001'],'W2').ok,false);assert.equal(JSON.stringify(s.units),before);});
test('leave Wing keeps individual mission',()=>{const s=P.create();const m=s.units[0].mission;assert.ok(P.moveToWing(s,['MG-001'],null).ok);assert.equal(s.units[0].wing,null);assert.equal(s.units[0].mission,m);});
test('join assigned Wing enters rejoin with that mission',()=>{const s=P.create();P.moveToWing(s,['MG-009'],null);assert.ok(P.moveToWing(s,['MG-001'],'W2').ok);assert.equal(s.units[0].state,'REJOIN');assert.equal(s.units[0].mission,'M2');});
test('new manual Wing never exceeds eight',()=>{const s=P.create();P.selectScope(s,'all');assert.equal(P.newWing(s).ok,false);assert.equal(s.wings.length,3);});
test('new manual Wing supports nonconsecutive IDs',()=>{const s=P.create();s.selected=new Set(['MG-001','MG-014','MG-021']);const r=P.newWing(s);assert.ok(r.ok);assert.equal(s.units.filter(u=>u.wing===r.id).length,3);});
test('deleted participant rejected before join',()=>{const s=P.create();assert.equal(P.moveToWing(s,['NO-UNIT'],null).ok,false);});
test('removed beacon and pinned focus disappear together',()=>{const s=P.create();s.focus=s.pinned='MG-024';P.removeUnit(s,'MG-024');assert.equal(P.hud(s).lost,0);assert.equal(P.hud(s).focus,null);});
for(const action of ['follow','hold','return','route','move','work','cargo','guard','salvage','store','role']) {
  test(`offline blocks ${action}`,()=>{const s=P.create();s.connected=false;assert.ok(P.eligible(s,action));});
  test(`permission blocks ${action}`,()=>{const s=P.create();s.permission=false;assert.ok(P.eligible(s,action));});
  test(`empty selection blocks ${action}`,()=>{const s=P.create();s.selected.clear();assert.ok(P.eligible(s,action));});
}
test('pending command cannot be double submitted',()=>{const s=single();assert.ok(P.issue(s,'follow').ok);assert.equal(P.issue(s,'hold').ok,false);assert.equal(s.units[0].state,'PATROL');});
test('selected set is captured at command time',()=>{const s=single();P.issue(s,'follow');s.selected=new Set(['MG-009']);P.ack(s);assert.equal(s.units[0].state,'FOLLOW');assert.equal(s.units[8].state,'WORK');});
test('rejected command does not mutate mission',()=>{const s=single();P.issue(s,'follow');P.ack(s,false);assert.equal(s.units[0].state,'PATROL');});
test('connection loss after send remains unconfirmed',()=>{const s=single();P.issue(s,'follow');s.connected=false;assert.equal(P.ack(s).ok,false);assert.equal(s.units[0].state,'PATROL');});
test('stale revision fails closed',()=>{const s=single();P.issue(s,'follow');P.removeUnit(s,'MG-015');assert.equal(P.ack(s).ok,false);assert.equal(s.units[0].state,'PATROL');});
test('deleted target fails closed even with unchanged revision',()=>{const s=single();P.issue(s,'follow');s.units.shift();assert.equal(P.ack(s).ok,false);});
test('whole Wing route updates Wing board and unit mission consistently',()=>{const s=P.create();const r=s.route;assert.ok(P.issue(s,'route',{route:r}).ok);P.ack(s);assert.ok(s.units.slice(0,8).every(u=>u.mission===s.wings[0].mission));});
test('individual order does not overwrite entire Wing mission',()=>{const s=single();P.issue(s,'route',{route:s.route});P.ack(s);assert.equal(s.wings[0].mission,'M1');assert.notEqual(s.units[0].mission,'M1');});
for(const n of [1,2,3,8])test(`route ${n} points accepted`,()=>{const s=single();assert.ok(P.issue(s,'route',{route:Array.from({length:n},(_,i)=>({x:i*12,y:84,z:i*3}))}).ok);});
for(const route of [[],Array.from({length:9},()=>({x:1,y:84,z:2})),[{x:NaN,y:84,z:2}],[{x:1,y:500,z:2}]])test('invalid route rejected '+route.length,()=>{assert.equal(P.issue(single(),'route',{route}).ok,false);});
test('cargo same-container rejects',()=>{const s=P.create();assert.equal(P.issue(s,'cargo',{source:'C1',destination:'C1'}).ok,false);});
test('cargo full target rejects',()=>{const s=P.create();assert.equal(P.issue(s,'cargo',{source:'C1',destination:'C3'}).ok,false);});
test('cargo available route accepted',()=>{assert.ok(P.issue(P.create(),'cargo',{source:'C1',destination:'C2'}).ok);});
test('airborne role change blocked',()=>{assert.ok(P.eligible(single(),'role'));});
test('docked role change valid and identity preserved',()=>{const s=P.create();s.selected=new Set(['MG-008']);assert.ok(P.issue(s,'role',{role:'engineer'}).ok);P.ack(s);assert.equal(s.units[7].role,'engineer');assert.equal(s.units[7].id,'MG-008');});
test('invalid role rejected',()=>{const s=P.create();s.selected=new Set(['MG-008']);assert.equal(P.issue(s,'role',{role:'UNKNOWN'}).ok,false);});
test('airborne itemization is not offered',()=>{assert.ok(P.eligible(single(),'store'));});
test('docked itemization removes marker source',()=>{const s=P.create();s.selected=new Set(['MG-008']);P.issue(s,'store');P.ack(s);assert.ok(!s.units.some(u=>u.id==='MG-008'));});
test('compact HUD retains rescue alert',()=>{const s=P.create(100);s.settings.density='compact';assert.equal(P.hud(s).markers,2);assert.equal(P.hud(s).lost,1);});
test('markers off does not hide critical beacon summary',()=>{const s=P.create();s.settings.markers=false;assert.equal(P.hud(s).markers,0);assert.equal(P.hud(s).lost,1);});
test('front priority and stable tie ordering',()=>{const s=P.create();s.fronts.push({id:'F3',priority:'CRITICAL'});assert.deepEqual(P.hud(s).fronts.map(f=>f.id),['F2','F3','F1']);});
for(const count of [0,8,24,100,256])test(`${count} units: front counts and Dock references use actual IDs`,()=>{const s=P.create(count);s.units.forEach(u=>assert.ok(s.docks.some(d=>d.id===u.dock)));s.fronts.forEach(f=>assert.equal(f.engaged,s.units.filter(u=>u.state==='COMBAT'&&u.target===f.id).length));});
test('guard accepts current front and preserves home Wing',()=>{const s=P.create();s.selected=new Set(['MG-005']);assert.ok(P.issue(s,'guard',{target:'F2'}).ok);P.ack(s);assert.equal(s.units[4].target,'F2');assert.equal(s.units[4].wing,'W1');assert.equal(s.units[4].resume,'M1');});
test('guard rejects a vanished front',()=>{const s=P.create();assert.equal(P.issue(s,'guard',{target:'MISSING'}).ok,false);});
test('salvage carries target and destination into the mission',()=>{const s=P.create();s.selected=new Set(['MG-006']);assert.ok(P.issue(s,'salvage',{target:'MG-024',destination:'D6'}).ok);P.ack(s);assert.equal(s.units[5].target,'MG-024');assert.equal(s.missions.at(-1).payload.destination,'D6');});
test('salvage rejects a recovered or vanished beacon',()=>{const s=P.create();assert.equal(P.issue(s,'salvage',{target:'MG-001'}).ok,false);});
test('cargo rejects a missing container',()=>{const s=P.create();assert.equal(P.issue(s,'cargo',{source:'C1',destination:'MISSING'}).ok,false);});
const out=path.join(root,'docs/design/equipment-hmi-v23/verification');fs.mkdirSync(out,{recursive:true});
const report={suite:'HMI design simulator; not Minecraft runtime',total:results.length,passed:results.filter(r=>r.pass).length,failed:results.filter(r=>!r.pass),results};
fs.writeFileSync(path.join(out,'interaction-policy.json'),JSON.stringify(report,null,2));
console.log(JSON.stringify({total:report.total,passed:report.passed,failed:report.failed},null,2));
if(report.failed.length)process.exitCode=1;
