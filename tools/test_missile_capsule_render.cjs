const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawnSync} = require('node:child_process');
const root = path.resolve(__dirname, '..');
const dir = path.join(root, 'build', 'missile-capsule-checks');
const state = fs.readFileSync(path.join(root, 'src/client/java/jp/morrowgear/drone/client/MissileRenderState.java'), 'utf8');
const renderer = fs.readFileSync(path.join(root, 'src/client/java/jp/morrowgear/drone/client/MissileRenderer.java'), 'utf8');
function run(command, args) {
  const result = spawnSync(command, args, {cwd: root, encoding: 'utf8', windowsHide: true});
  assert.equal(result.status, 0, `${result.error || ''}\n${result.stdout}\n${result.stderr}`);
}
before(() => {
  fs.mkdirSync(dir, {recursive: true});
  // Compile the exact independent profile used by the renderer, without bootstrapping Minecraft.
  const start = state.indexOf('final class MissileCapsuleShape {');
  assert.ok(start >= 0);
  const profile = path.join(dir, 'MissileCapsuleShape.java');
  fs.writeFileSync(profile, 'package jp.morrowgear.drone.client;\nimport java.util.List;\n' + state.slice(start));
  const basisStart = renderer.indexOf('Vec3 direction = state.velocity');
  const basisEnd = renderer.indexOf('float flash =', basisStart);
  const bodyStart = renderer.indexOf('(pose, out) -> {', basisEnd) + '(pose, out) -> {'.length;
  const bodyEnd = renderer.indexOf('\n\t\t});', bodyStart);
  const methodsStart = renderer.indexOf('private static void cap(');
  assert.ok(basisStart > 0 && basisEnd > basisStart && bodyStart > basisEnd && bodyEnd > bodyStart && methodsStart > bodyEnd);
  const capture = path.join(dir, 'CapturedMissileGeometry.java');
  fs.writeFileSync(capture, 'package jp.morrowgear.drone.client;\nimport static jp.morrowgear.drone.client.MissileGeometryChecks.*;\n'
    + 'final class CapturedMissileGeometry {\nstatic void shell(State state, PoseStack.Pose pose, VertexConsumer out) {\n'
    + renderer.slice(basisStart, basisEnd) + renderer.slice(bodyStart, bodyEnd) + '\n}\n' + renderer.slice(methodsStart));
  run('javac', ['-encoding', 'UTF-8', '-d', dir, profile, capture, 'tools/MissileCapsuleChecks.java', 'tools/MissileGeometryChecks.java']);
});
for (const name of ['proportions','stepped_shell','embedded_band','surface_geometry','short_exhaust','cold_eject','smoke_budget','smoke_fade'])
  test(`missile capsule: ${name}`, () => run('java', ['-cp', dir, 'jp.morrowgear.drone.client.MissileCapsuleChecks', name]));
for (const name of ['actual_normals','closed_mesh','caps_visible'])
  test(`missile emitted geometry: ${name}`, () => run('java', ['-cp', dir, 'jp.morrowgear.drone.client.MissileGeometryChecks', name]));
test('render passes keep ordinary depth and separate lit smoke from emission', () => {
  assert.match(renderer, /RenderTypes.entityCutout\(WHITE_TEXTURE\)/);
  assert.match(renderer, /RenderTypes.entityTranslucent\(WHITE_TEXTURE\)/);
  assert.match(renderer, /RenderTypes.entityTranslucentEmissive\(WHITE_TEXTURE\)/);
  assert.doesNotMatch(renderer, /DepthStencilState|CompareOp|SeeThrough|disableDepth|MorrowgearRenderTypes/);
  const smoke = renderer.slice(renderer.indexOf('private static void smoke('), renderer.indexOf('private static void cap('));
  assert.match(smoke, /state.lightCoords/);
  assert.doesNotMatch(smoke, /F000F0|Emissive|emitBeam|FULL_BRIGHT/);
  assert.match(smoke, /MissileCapsuleShape.smokeCount\(state.trail.size\(\)\)/);
  assert.match(smoke, /MissileCapsuleShape.historyIndex\(i, state.trail.size\(\)\)/);
  assert.match(smoke, /point.position\(\)/);
});
test('only the band and short motor are emissive, with no history, pointed nose or fins', () => {
  const emission = renderer.slice(renderer.indexOf('RenderTypes.entityTranslucentEmissive'), renderer.indexOf('boolean cold ='));
  assert.doesNotMatch(emission, /state.trail|emitBeam|cameraOffset/);
  assert.match(emission, /MissileCapsuleShape.exhaustVisible\(state.motorIgnited, state.impacted\)/);
  assert.match(renderer, /state.motorIgnited = entity.motorIgnited\(\)/);
  assert.doesNotMatch(renderer, /emitBeam|for \(int fin|0\.012|-1\.65/);
  assert.match(renderer, /a.z\(\), b.z\(\), a.radius\(\), b.radius\(\)/);
  assert.match(renderer, /MissileTrailHistory.MAX_POINTS/g);
  assert.match(renderer, /entity.attachedRenderPosition\(partialTick\)/);
});
test('cold ejection uses synced EJECT phase and a short normally lit white-grey puff', () => {
  assert.match(renderer, /state.ejecting = entity.flightPhase\(\) == MorrowgearMissileEntity.FlightPhase.EJECT/);
  assert.match(renderer, /MissileCapsuleShape.ejectVisible\(state.ejecting, state.motorIgnited, state.impacted, state.ageInTicks\)/);
  const cold = renderer.slice(renderer.indexOf('private static void coldEject('), renderer.indexOf('private static void puff('));
  assert.match(cold, /MissileCapsuleShape.COLD_EJECT_PUFFS/);
  assert.match(cold, /0xD7DCDD/);
  assert.match(cold, /state.lightCoords/);
  assert.doesNotMatch(cold, /F000F0|FULL_BRIGHT|Emissive|emitBeam/);
});
