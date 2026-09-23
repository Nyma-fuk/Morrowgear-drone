const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const out = path.join(root, 'build', 'ui-policy-checks');
function run(command, args) {
  const result = spawnSync(command, args, {cwd: root, encoding: 'utf8', windowsHide: true});
  assert.equal(result.status, 0, `${result.error || ''}\n${result.stdout}\n${result.stderr}`);
}
before(() => {
  fs.mkdirSync(out, {recursive: true});
  run('javac', ['-encoding', 'UTF-8', '-d', out,
    'src/main/java/jp/morrowgear/drone/CarrierUiPolicy.java', 'tools/CarrierUiPolicyChecks.java',
    'src/main/java/jp/morrowgear/drone/CarrierUiInputPolicy.java', 'tools/CarrierUiFlowChecks.java',
    'src/main/java/jp/morrowgear/drone/SupplyNetworkUiPolicy.java', 'tools/SupplyNetworkUiChecks.java']);
});
for (const name of ['consent','expiry','heartbeat','generation','range','guest','cancel','mode','saved','negative','slots','slot_bounds','subset_coverage','layout'])
  test(`carrier UI policy: ${name}`, () => run('java', ['-cp', out, 'CarrierUiPolicyChecks', name]));
for (const name of ['numbers','canonical','fresh_snapshot','target','changed_rules','enabled','timeout','reset'])
  test(`supply UI policy: ${name}`, () => run('java', ['-cp', out, 'SupplyNetworkUiChecks', name]));
for (const name of ['unsolicited_preview','queued_preview','fast_start','slow_server','cancel_queued','cancel_late_ack','unknown_timeout',
    'scope_and_order','ack_authority','ack_target','queued_start_revoked','start_ack_identity','saved_generation','selection_cancels',
    'opening_enter','rebuilt_keys','native_input','projection_restore','odd_projection','destroyed_cargo','cargo_scope',
    'move_queue','move_scope','move_saved','move_stopped','move_timeout','move_cancel','move_exclusive'])
  test(`carrier command behavior: ${name}`, () => run('java', ['-cp', out, 'CarrierUiFlowChecks', name]));
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
test('hidden slots have render, hover, click, drag, release, wheel and hotbar guards', () => {
  const source = read('src/client/java/jp/morrowgear/drone/client/HmiContainerScreen.java');
  for (const method of ['extractContents','extractSlots','isHovering','slotClicked','checkHotbarKeyPressed','mouseClicked','mouseDragged','mouseReleased','mouseScrolled'])
    assert.ok(source.includes(method), method);
  assert.match(source, /inventoryEditable\(\) && \(id < 0 \|\| id < menu\.slots\.size\(\) && slotVisible\(menu\.slots\.get\(id\)\)\)/);
  assert.match(source, /return inventoryEditable\(\) && super\.checkHotbarKeyPressed/);
  const positionAssignment = /slot\.[xy]\s*=(?!=)/;
  assert.match('slot.x = 3;', positionAssignment);
  assert.match('slot.y=3;', positionAssignment);
  assert.doesNotMatch('slot.x == x && slot.y == y', positionAssignment);
  assert.doesNotMatch(source, positionAssignment);
  assert.match(source, /if \(controlPress\) \{ controlPress = false; cancelSlotGesture\(\); return true; \}/);
});
test('carrier binds all command actions and uses actual menu identity', () => {
  const source = read('src/client/java/jp/morrowgear/drone/client/CarrierScreen.java');
  for (const action of ['PREVIEW_MINING','PREVIEW_COMBAT','ACTIVATE','RESUME_PREVIEW','STOP','BOARD','EXIT','MOVE','REFUEL','CHARGE_WEAPON','RESERVE_BAY','RELEASE_BAY','ALLOW_GUEST','REVOKE_GUEST','RECOVER_CABIN','CARGO_PAGE'])
    assert.ok(source.includes(`Action.${action}`), action);
  assert.match(source, /new CarrierCommandPayload\(menu\.containerId, menu\.shipId/);
  assert.match(source, /isOwnedBy\(minecraft\.player\.getUUID\(\)\)/);
});

test('recovery paging uses cargo authorization without permitting destroyed ship operations', () => {
  const source = read('src/client/java/jp/morrowgear/drone/client/CarrierScreen.java');
  assert.match(source, /private boolean cargoReady\(\) \{ return currentMenu\(\) && linked\(\) && confirmation\.cargoAccessible\(now\(\)\); \}/);
  assert.match(source, /private boolean ready\(\) \{ return cargoReady\(\) && !view\(\)\.destroyed\(\); \}/);
  assert.match(source, /boolean canPage = cargoReady\(\) && !confirmation\.busy\(\)/);
  assert.match(source, /if \(!cargoReady\(\) \|\| cargoPending\(\) \|\| !menu\.getCarried\(\)\.isEmpty\(\)/);
});
test('shared mining/combat completion does not claim saved mining progress has finished', () => {
  const source = read('src/client/java/jp/morrowgear/drone/client/CarrierScreen.java');
  const completion = source.match(/case COMPLETE\s*->\s*([^;]+);/);
  assert.ok(completion, 'COMPLETE status must have an explicit label');
  assert.equal(completion[1], '"直前の作業が終わりました"');
  assert.doesNotMatch(completion[1], /採掘|tab|cursor|total|previewMode/);
  assert.match(source, /button\("前の採掘を続ける"[^;]+this::resume\)/);
});
test('native slot coordinates match the compact backend contract without shrinking or changing GUI scale', () => {
  const menu = read('src/main/java/jp/morrowgear/drone/carrier/CarrierMenu.java');
  const screen = read('src/client/java/jp/morrowgear/drone/client/CarrierScreen.java');
  assert.match(menu, /CARGO_Y = 18, SUPPLY_Y = 126, PLAYER_Y = 150/);
  assert.match(menu, /addStandardInventorySlots\(inventory, 8, PLAYER_Y\)/);
  assert.match(screen, /super\(menu, inventory, title, 230\)/);
  assert.match(screen, /CarrierUiPolicy\.inventoryTop\(height, inventoryVisible\(\) && playerPage\)/);
  assert.match(screen, /CarrierUiPolicy\.slotVisible\(slot.index, height, playerPage\)/);
  assert.doesNotMatch(screen, /guiScale\(|setGuiScale|pose\(\)\.scale|slot\.[xy]\s*=(?!=)/);
});
test('small movement view exposes altitude and zoom without map input leaking through its secondary page', () => {
  const screen = read('src/client/java/jp/morrowgear/drone/client/CarrierScreen.java');
  assert.match(screen, /if \(moveControls && small\(\)\)/);
  for (const label of ['高度 -8','高度 +8','地図に戻る','拡大','縮小']) assert.ok(screen.includes(`button("${label}"`));
  assert.match(screen, /if \(tab == Tab.MOVE\) button\("高度"/);
  assert.match(screen, /diagnostics \|\| choosingTab \|\| moveControls \|\| tab == Tab.CARGO/);
  assert.match(screen, /!choosingTab && !diagnostics && !moveControls/g);
  const controls = [{x:8,y:78,w:94,h:20},{x:108,y:78,w:94,h:20},{x:8,y:108,w:194,h:20},
    {x:6,y:156,w:70,h:20},{x:82,y:156,w:232,h:20}];
  for (const r of controls) assert.ok(r.x >= 0 && r.y >= 0 && r.x + r.w <= 320 && r.y + r.h <= 180);
  for (let i = 0; i < controls.length; i++) for (let j = i + 1; j < controls.length; j++) {
    const a = controls[i], b = controls[j];
    assert.ok(a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y);
  }
});
test('supply route editor polls only while open and never infers the current Dock target', () => {
  const dock = read('src/client/java/jp/morrowgear/drone/client/DockScreen.java');
  const source = read('src/client/java/jp/morrowgear/drone/client/SupplyNetworkPanel.java');
  assert.match(dock, /if \(network != null\) network.tick\(\)/);
  assert.match(dock, /return network == null && width >= 300 && height >= 166/);
  assert.match(source, /private Long dock, source;/);
  assert.match(source, /SupplyNetworkClient.request\(\)/);
  assert.match(source, /if \(!client.level.hasChunkAt\(pos\)\) continue/);
  assert.match(source, /target.isOwnedBy\(client.player.getUUID\(\)\)/);
  assert.match(source, /container.stillValid\(client.player\)/);
  assert.match(source, /!container.isLocked\(\)/);
  assert.match(source, /ClientPlayNetworking.canSend\(SupplyNetworkConfigPayload.TYPE\)/);
  assert.match(source, /new SupplyNetworkConfigPayload\(from, dock, enabled, savedRules\)/);
  assert.match(source, /List<Rule> savedRules = enabled \? rules\(\) : current.rules\(\)/);
  assert.match(source, /value < 0 \? "未確認"/);
});
test('all literal button and map icons use existing approved HMI images', () => {
  for (const file of ['CarrierScreen.java','DockScreen.java','SupplyNetworkPanel.java']) {
    const source = read(`src/client/java/jp/morrowgear/drone/client/${file}`);
    const patterns = [/button\("[^"]*",\s*"([^"]+)"/g, /HmiArt.icon\(g,\s*"([^"]+)"/g];
    for (const pattern of patterns) for (const match of source.matchAll(pattern))
      assert.ok(fs.existsSync(path.join(root, `src/main/resources/assets/morrowgear_drone/textures/gui/symbols/${match[1]}.png`)), `${file}: ${match[1]}`);
  }
});
