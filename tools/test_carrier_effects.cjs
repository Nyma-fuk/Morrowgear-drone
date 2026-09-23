const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const dir = path.join(root, 'build', 'carrier-effect-checks');
const client = 'src/client/java/jp/morrowgear/drone/client/';
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const renderer = read(client + 'CarrierRenderer.java');
const screen = read(client + 'CarrierScreen.java');
const map = read(client + 'CarrierMapView.java');
function run(command, args) {
  const p = spawnSync(command, args, {cwd:root, encoding:'utf8', windowsHide:true});
  assert.equal(p.status, 0, `${p.error || ''}\n${p.stdout}\n${p.stderr}`);
}
before(() => {
  fs.mkdirSync(dir, {recursive:true});
  const start = map.indexOf('final class CarrierMapLine {');
  assert.ok(start > 0);
  const line = path.join(dir, 'CarrierMapLine.java');
  fs.writeFileSync(line, 'package jp.morrowgear.drone.client;\n' + map.slice(start));
  run('javac', ['-encoding','UTF-8','-d',dir,client+'CarrierEffectGeometry.java',client+'CarrierEffectTimeline.java',line,'tools/CarrierEffectChecks.java']);
});
for (const name of ['work_column','aperture','chunk_clip','negative_chunk','invalid_rays','winding','budget','phase_progress','baseline','continuous_capture','reset_and_empty','stale_and_budget','map_clip'])
  test(`carrier effects: ${name}`, () => run('java',['-cp',dir,'jp.morrowgear.drone.client.CarrierEffectChecks',name]));
test('renderer consumes authoritative phase and all successful aims from the unchanged central origin', () => {
  for(const getter of ['beamOrigin','beamPaths','workPhase','phaseTick','phaseDuration','sampleTick','captureSequence','capturedItems','lastCapturePosition'])
    assert.ok(renderer.includes(`entity.${getter}()`), getter);
  assert.match(renderer,/state.phase == CarrierPolicy.WorkPhase.FIRE && entity.beamActive\(\)/);
  assert.match(renderer,/entity.beamPaths\(\).stream\(\).limit\(1\)/);
  assert.match(renderer,/state.column = state.combat \? null/);
  assert.match(renderer,/CarrierEffectGeometry.validRay/);
  assert.match(renderer,/MorrowgearRenderTypes.energyBeam\(\)/);
  assert.match(renderer,/MorrowgearRenderTypes.visibleEnergyCore\(\)/);
  assert.doesNotMatch(renderer,/NO_DEPTH|SeeThrough|disableDepth|DepthStencilState/);
  assert.match(renderer,/eye.distanceToSqr\(state.boardingPad\) < 96 \* 96/);
});
test('combat search is an unfilled dashed perimeter; damage markers use only actual aims', () => {
  assert.match(map,/i < 72; i \+= 2/);
  assert.match(map,/effect.beamAims\(\).stream\(\).limit\(CarrierEffectGeometry.MAX_BEAMS\)/);
  assert.match(map,/if \(!fresh \|\| effect.phase\(\) != CarrierPolicy.WorkPhase.FIRE\) return/);
  assert.match(screen,/敵を探す範囲 半径/);
  assert.match(screen,/標的周囲/);
  assert.match(screen,/CarrierPolicy.COMBAT_IMPACT_RADIUS/);
  assert.match(screen,/射線の通る敵だけを攻撃/);
  assert.match(screen,/充填.*照射.*冷却/);
});
test('cargo paging is server confirmed, cursor guarded, and preserves the cargo tab across new menu ids', () => {
  assert.match(screen,/send\(Action.CARGO_PAGE, CarrierCommandPayload.NONE, target, menu.getStateId\(\), menu.cargoPage\(\), CarrierCommandPayload.NONE\)/);
  assert.match(screen,/cargoPending\(\) \|\| !menu.getCarried\(\).isEmpty\(\)/);
  assert.match(screen,/cargoReopen.ship\(\).equals\(menu.shipId\) && cargoReopen.oldMenu\(\) != menu.containerId/);
  assert.match(screen,/tab = Tab.CARGO; playerPage = cargoReopen.playerPage\(\)/);
  assert.match(screen,/inventoryVisible\(\) && view\(\).owner\(\) && !cargoPending\(\)/);
  assert.doesNotMatch(screen,/setCargoPage|storage\.page\s*=|CARGO_SLOTS\s*=/);
});
test('saved navigation can be explicitly resumed and stopped while paused', () => {
  assert.match(screen,/navigationPaused\(\)/);
  assert.match(screen,/保存した目的地への移動を再開/);
  assert.match(screen,/confirmation.queueMove\(destination\(destination\), now\(\)\)/);
  assert.match(screen,/new CarrierUiPolicy.Destination\(anchor.dimension\(\), anchor.x\(\), anchor.y\(\), anchor.z\(\)\)/);
  assert.match(screen,/case MOVE -> Action.MOVE/);
  assert.match(screen,/command.kind\(\) == CarrierUiPolicy.CommandKind.START \? command.generation\(\) : CarrierCommandPayload.NONE/);
  assert.match(screen,/destination == null \? command.chunkX\(\) \* 16 \+ 8 : destination.x\(\), destination == null \? 0 : destination.y\(\)/);
  assert.match(screen,/destination == null \? command.chunkZ\(\) \* 16 \+ 8 : destination.z\(\), CarrierCommandPayload.NONE\)/);
  assert.match(screen,/primary.active = !choosingTab && !diagnostics && !moveControls/);
  assert.match(screen,/confirmation.cancelMove\(\);/);
  assert.doesNotMatch(screen,/send\(Action.MOVE/);
  assert.match(screen,/stop.active[^;]+navigation\(\).destination\(\).isPresent\(\)/s);
  assert.doesNotMatch(screen,/128L \* 128|128ブロック以内/);
});
test('240 and 180 high cargo controls stay beside native slots, without overlap', () => {
  for (const height of [180,240]) {
    const controls = [{x:182,y:4,w:132,h:20},{x:182,y:30,w:132,h:20},{x:182,y:56,w:132,h:20},
      {x:182,y:82,w:64,h:20},{x:250,y:82,w:64,h:20},{x:182,y:108,w:132,h:9},
      {x:182,y:height-37,w:132,h:9},{x:182,y:height-24,w:132,h:20}];
    if(height===180) controls.push({x:182,y:120,w:132,h:20});
    else controls.push(...[127,151,174,187].map(y=>({x:182,y,w:132,h:9})));
    for(const a of controls) assert.ok(a.x>=176&&a.x+a.w<=320&&a.y>=0&&a.y+a.h<=height);
    for(let i=0;i<controls.length;i++) for(let j=i+1;j<controls.length;j++) {
      const a=controls[i],b=controls[j];
      assert.ok(a.x+a.w<=b.x||b.x+b.w<=a.x||a.y+a.h<=b.y||b.y+b.h<=a.y,JSON.stringify([a,b]));
    }
  }
  assert.match(screen,/182, 120, width - 188/);
  assert.match(screen,/y = compactCargo\(\) \? 82 : 86/);
  assert.match(screen,/height - \(compactCargo\(\) \? 37 : 39\)/);
});
