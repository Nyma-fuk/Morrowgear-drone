const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { resolve } = require('node:path');
const { spawnSync } = require('node:child_process');
const { test } = require('node:test');

const root = resolve(__dirname, '..');
const client = resolve(root, 'src/client/java/jp/morrowgear/drone/client');
const dashboard = readFileSync(resolve(client, 'TacticalDashboard.java'), 'utf8');
const hud = readFileSync(resolve(client, 'VisorHudOverlay.java'), 'utf8');
const art = readFileSync(resolve(client, 'HmiArt.java'), 'utf8');
const wingPolicy = readFileSync(resolve(root, 'src/main/java/jp/morrowgear/drone/WingMembershipPolicy.java'), 'utf8')
  .replace(/^package [^;]+;/, '');

// These methods use the repository's one-tab method indentation; no implementation is copied.
function method(source, name) {
  const declaration = new RegExp('^\\t(?:private|public|protected|static)[^\\n]*\\b' + name + '\\(', 'm');
  const start = source.search(declaration);
  assert.notEqual(start, -1, name);
  const end = source.indexOf('\n\t}', start);
  assert.notEqual(end, -1, name + ' closing brace');
  return source.slice(start, end + 3);
}

test('Java syntax, actual fitting, HUD geometry, selection, confirmation and Wing capacity (no game build)', () => {
  const helpers = ['fitText', 'hudSideWidth', 'hudSidesFit', 'hudOverviewWidth']
    .map(name => method(art, name)).join('\n');
  const script = `
import java.util.function.ToIntFunction;
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
${helpers}
${wingPolicy}
record DroneEntity(int id, String group) { int getId() { return id; } String groupId() { return group; } }
record DroneCommandPayload(int id, String action) {}
class ClientPlayNetworking { static List<DroneCommandPayload> sent = new ArrayList<>(); static void send(DroneCommandPayload payload) { sent.add(payload); } }
class HmiCommandHarness {
  static class Level { long now = 100; long getGameTime() { return now; } }
  static class Client { Level level = new Level(); }
  Client client = new Client();
  Set<Integer> selected = new LinkedHashSet<>();
  Set<Integer> confirmStoreSelection = Set.of();
  long confirmStoreUntil;
  ${dashboard.match(/private static final String\[\] ACTIONS = \{[^}]+\};/)[0]}
  void notifyAction(String message) {}
  void issueFollowFormation() {}
  List<DroneEntity> drones() { return List.of(); }
  String actionLabel(int index, List<DroneEntity> drones) { return ACTIONS[index]; }
  ${method(dashboard, 'execute')}
  ${method(dashboard, 'selectionWingSummary')}
  void verify() {
    selected.add(1);
    execute(5);
    require(ClientPlayNetworking.sent.isEmpty(), "initial store must confirm");
    selected.clear(); selected.add(2);
    execute(5);
    require(ClientPlayNetworking.sent.isEmpty(), "changed recipient must reconfirm");
    execute(5);
    require(ClientPlayNetworking.sent.equals(List.of(new DroneCommandPayload(2, "decommission"))), "store only confirmed recipient");
    ClientPlayNetworking.sent.clear();
    execute(5); execute(1); execute(5);
    require(ClientPlayNetworking.sent.equals(List.of(new DroneCommandPayload(2, "standby"))), "other command cancels confirmation");
    client.level.now += 61;
    execute(5);
    require(ClientPlayNetworking.sent.size() == 1, "expired confirmation");
    selected.clear(); execute(5);
    require(ClientPlayNetworking.sent.size() == 1, "empty selection sends nothing");
    List<DroneEntity> roster = List.of(new DroneEntity(1, "WING-A"), new DroneEntity(2, "WING-A"),
      new DroneEntity(3, "WING-B"), new DroneEntity(4, "WING-B"), new DroneEntity(5, ""));
    selected.addAll(List.of(1, 2, 3, 5));
    require(selectionWingSummary(roster, roster.stream().filter(d -> selected.contains(d.id())).toList())
      .equals("WING 1 FULL 1 PART / UNGROUPED 1"), "full, partial and unassigned selection");
    selected.add(4);
    require(selectionWingSummary(roster, roster).equals("WING 2 FULL 0 PART / UNGROUPED 1"), "all selected groups");
  }
}
void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
void checkSyntax() throws Exception {
  var compiler = ToolProvider.getSystemJavaCompiler();
  var diagnostics = new DiagnosticCollector<JavaFileObject>();
  try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
    var units = files.getJavaFileObjectsFromStrings(List.of(${['TacticalDashboard.java', 'VisorHudOverlay.java', 'HmiArt.java']
      .map(name => JSON.stringify(resolve(client, name).replaceAll('\\', '/'))).join(', ')}));
    var task = (JavacTask)compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, units);
    task.parse();
    require(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR), diagnostics.getDiagnostics().toString());
  }
}
void checkHmi() {
  ToIntFunction<String> measure = s -> s.codePointCount(0, s.length()) * 6;
  String[] labels = {"", "A", "RETURN TO ME", "WING-123456 / ENGINEER", "\\u65e5\\u672c\\u8a9e", "X\\uD83D\\uDE80Y"};
  for (String label : labels) for (int width = -10; width <= 512; width++) {
    String fitted = fitText(label, width, measure);
    require(measure.applyAsInt(fitted) <= Math.max(0, width), "overflow at " + width);
    require(fitted.codePoints().noneMatch(c -> c >= 0xD800 && c <= 0xDFFF), "split surrogate");
    if (width >= measure.applyAsInt(label)) require(label.equals(fitted), "unnecessary trimming");
  }
  require(fitText("TO DOCK", 42, measure).equals("TO DOCK"), "exact boundary");
  require(fitText("TO DOCK", 41, measure).equals("TO ..."), "ellipsis boundary");
  require(fitText("TO DOCK", 17, measure).isEmpty(), "ellipsis overflow");
  for (int width = 320; width <= 3840; width++) {
    int side = hudSideWidth(width);
    require(side >= 0 && 42 + side <= width - 42, "side bounds");
    if (hudSidesFit(width)) require(42 + side + 16 <= width - 42 - side, "side overlap");
    int middle = hudOverviewWidth(width);
    require(middle >= 0 && middle <= 920, "overview bound");
    if (middle >= 320) require((width - middle) / 2 >= 42 + side + 16, "overview overlap");
  }
  require(!hudSidesFit(640), "focus replaces command on narrow window");
  require(hudOverviewWidth(960) < 320, "constrained overview suppressed");
  require(hudOverviewWidth(1920) >= 320, "desktop overview retained");
  require(WingMembershipPolicy.MAX_MEMBERS == 8, "Wing capacity remains eight");
  require(WingMembershipPolicy.evaluate("WING-A", "WING-B", 7) == WingMembershipPolicy.Result.ACCEPTED, "eighth member accepted");
  require(WingMembershipPolicy.evaluate("WING-A", "WING-B", 8) == WingMembershipPolicy.Result.WING_FULL, "ninth member refused");
  new HmiCommandHarness().verify();
}
try { checkSyntax(); checkHmi(); System.out.println("HMI_LOGIC_PASS"); } catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
/exit
`;
  const result = spawnSync('jshell', ['--feedback', 'concise', '-'], {
    input: script, encoding: 'utf8', cwd: root, timeout: 30000,
  });
  assert.ifError(result.error);
  assert.equal(result.status, 0, result.stdout + result.stderr);
  assert.match(result.stdout, /HMI_LOGIC_PASS/, result.stdout + result.stderr);
  assert.doesNotMatch(result.stdout, /Error:/);
});

test('wire command IDs stay stable while return and dock labels distinguish destinations', () => {
  const actions = dashboard.match(/String\[\] ACTIONS = \{([^}]+)\}/)[1];
  assert.deepEqual([...actions.matchAll(/"([^"]+)"/g)].map(match => match[1]),
    ['follow', 'standby', 'return', 'dock', 'orbit', 'decommission']);
  assert.match(method(dashboard, 'actionLabel'), /case "return" -> "RETURN TO ME"/);
  assert.match(method(dashboard, 'actionLabel'), /case "dock" -> "TO DOCK"/);
  assert.match(method(dashboard, 'execute'), /new DroneCommandPayload\(id, ACTIONS\[index\]\)/);
});

test('storage confirmation snapshots recipients and expires on changed selection or another command', () => {
  const execute = method(dashboard, 'execute');
  assert.match(execute, /now >= confirmStoreUntil \|\| !selected\.equals\(confirmStoreSelection\)/);
  assert.match(execute, /confirmStoreSelection = Set\.copyOf\(selected\)/);
  assert.match(execute, /confirmStoreUntil = 0;\s*confirmStoreSelection = Set\.of\(\)/);
  assert.match(method(dashboard, 'extractWidgetRenderState'),
    /if \(!selected\.equals\(confirmStoreSelection\)\) confirmStoreUntil = 0/);
});

test('route editing cannot also send a waypoint, work or guard command', () => {
  const click = method(dashboard, 'onClick');
  assert.match(click, /page == 0 && !armedRoute && doubleClick/);
  assert.match(click, /armedWork = armedWork == WORK_TYPES\[i\][^;]+;\s*armedRoute = false;\s*routeDraft\.clear\(\)/);
  assert.match(click, /armedGuard = !armedGuard;\s*armedRoute = false;\s*routeDraft\.clear\(\)/);
});

test('selection summaries use complete memberships and disclose hidden recipients', () => {
  const summary = method(dashboard, 'selectionWingSummary');
  assert.match(summary, /drones\.stream\(\)\.filter\(d -> d\.groupId\(\)\.equals\(id\)\)/);
  assert.match(summary, /allMatch\(d -> selected\.contains\(d\.getId\(\)\)\)/);
  const panel = method(dashboard, 'drawCommandPanel');
  assert.match(panel, /HIDDEN BY FILTER/);
  assert.match(panel, /NO DOCK/);
  assert.match(panel, /FLEET TASK FORCE/);
});

test('existing Wing capacity, drag dispatch and additive selection contracts remain present', () => {
  const drop = method(dashboard, 'completeWingDrop');
  assert.match(method(dashboard, 'beginWingCardInteraction'), /WingMembershipPolicy\.MAX_MEMBERS/);
  assert.match(drop, /"wing_join:"/);
  assert.match(drop, /"wing_leave"/);
  assert.match(drop, /"wing_create:WING-"/);
  const click = method(dashboard, 'onClick');
  assert.match(click, /event\.hasControlDown\(\)/);
  assert.match(click, /event\.hasShiftDown\(\)/);
  assert.match(click, /selectionToggleRect/);
  assert.equal((dashboard.match(/String\[\] PAGES = \{([^}]+)\}/)[1].match(/"[^"]+"/g) || []).length, 8);
});

test('HUD prioritizes focus and does not force the operation panel between overlapping side panels', () => {
  assert.match(method(hud, 'extractRenderState'), /!inspecting \|\| HmiArt\.hudSidesFit\(width\)/);
  assert.match(method(hud, 'drawOperationOverview'), /if \(panelWidth < 320\) return/);
  assert.match(method(hud, 'drawCommandContext'), /contextGroup\.isBlank\(\) \? List\.of\(pinned\)/);
  assert.match(method(hud, 'drawInspectionContext'), /if \(compact\)/);
  assert.doesNotMatch(method(hud, 'drawCommandContext'), /"LINK " \+ link/);
});

test('warning text wraps, beacon-only alerts remain visible and marker budgets include urgent units', () => {
  const rail = method(hud, 'drawActionRail');
  assert.match(rail, /Math\.max\(1, plan\.alertRows\(\)\)/);
  assert.match(rail, /stacked \? 48 : 32/);
  assert.match(rail, /lineY \+ \(stacked \? 22 : 0\)/);
  assert.doesNotMatch(rail, /RTB ASSIGNED/);
  assert.match(method(hud, 'drawProximity'), /if \(drawn >= budget\) continue;\s*drawn\+\+/);
  assert.doesNotMatch(method(hud, 'drawCluster'), /groupId\(\)/);
  assert.match(method(dashboard, 'fitText'), /HmiArt\.fitText/);
  assert.match(method(hud, 'fit'), /HmiArt\.fitText/);
});
