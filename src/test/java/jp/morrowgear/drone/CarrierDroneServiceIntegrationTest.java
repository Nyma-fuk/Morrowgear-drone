package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class CarrierDroneServiceIntegrationTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/jp/morrowgear/drone", name));
    }
    private String service() throws Exception {
        String entity = source("DroneEntity.java");
        return entity.substring(entity.indexOf("boolean carrierServiceAvailable("), entity.indexOf("protected void customServerAiStep("));
    }

    private static boolean supplyToken(Tree expression) {
        return switch (expression) {
            case IdentifierTree name -> name.getName().contentEquals("supplyNetworkToken");
            case MemberSelectTree member -> member.getIdentifier().contentEquals("supplyNetworkToken");
            case ParenthesizedTree parentheses -> supplyToken(parentheses.getExpression());
            default -> false;
        };
    }

    private List<String> supplyTokenWrites(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Source contract tests require the build JDK's Java parser");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///DroneEntity.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return source; }
        };
        List<String> writes = new ArrayList<>();
        List<String> inspected = new ArrayList<>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            // Parse only: Minecraft types need not resolve, and no annotation processing or compilation runs.
            var task = (JavacTask)compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(input));
            for (var unit : task.parse()) new TreeScanner<Void, Void>() {
                @Override public Void visitMethod(MethodTree method, Void unused) {
                    String name = method.getName().toString();
                    if (!name.toLowerCase(Locale.ROOT).contains("carrier")) return null;
                    inspected.add(name);
                    new TreeScanner<Void, Void>() {
                        private void record(Tree target, Tree write) {
                            if (supplyToken(target)) writes.add(name + ": " + write);
                        }
                        @Override public Void visitAssignment(AssignmentTree tree, Void unused) {
                            record(tree.getVariable(), tree);
                            return super.visitAssignment(tree, unused);
                        }
                        @Override public Void visitCompoundAssignment(CompoundAssignmentTree tree, Void unused) {
                            record(tree.getVariable(), tree);
                            return super.visitCompoundAssignment(tree, unused);
                        }
                        @Override public Void visitUnary(UnaryTree tree, Void unused) {
                            switch (tree.getKind()) {
                                case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT ->
                                    record(tree.getExpression(), tree);
                                default -> { }
                            }
                            return super.visitUnary(tree, unused);
                        }
                    }.scan(method.getBody(), null);
                    return null;
                }
            }.scan(unit, null);
        }
        assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR),
            () -> "Could not parse source: " + diagnostics.getDiagnostics());
        assertFalse(inspected.isEmpty(), "No carrier methods were inspected");
        return writes;
    }

    @Test void exteriorServiceRunsBeforeUnchangedGlobalOwnerGuard() throws Exception {
        String entity = source("DroneEntity.java");
        String ai = entity.substring(entity.indexOf("protected void customServerAiStep("), entity.indexOf("private boolean tickFlightPower("));
        assertTrue(ai.indexOf("tickCarrierService(level, owner)") < ai.indexOf("if (owner == null || owner.level() != level) return;"));
        assertTrue(ai.contains("if (owner == null || owner.level() != level) return;"));
        assertFalse(service().contains("ThreatAssessment.assess("));
        assertFalse(service().contains("targetPosition(level, owner"));
        assertFalse(service().contains("owner.position()"));
    }

    @Test void interruptNeverRewritesHomeWingMissionQueueOrSupplyToken() throws Exception {
        String service = service();
        for (String forbidden : new String[] {"entityData.set(DOCK_POS", "entityData.set(GROUP", "entityData.set(MISSION_ID",
            "assignWaypoint(", "clearMissionAssignment(", "taskStack.queue(", "taskStack.suspend(",
            "taskStack.resume(", "taskStack.clear(", "clearSolarService("})
            assertFalse(service.contains(forbidden), forbidden);
        assertEquals(List.of(), supplyTokenWrites(source("DroneEntity.java")), "Carrier methods must not write supply tokens");
        assertTrue(service.contains("taskStack.pendingCount() == 0"));
        assertTrue(service.contains("supplyNetworkToken == null"));
        assertTrue(source("DroneEntity.java").contains("drone.carrierService == null && !drone.solarServiceAssigned()"));
    }

    @Test void tokenWriteGuardAllowsComparisonsCommentsAndStringLiterals() throws Exception {
        assertEquals(List.of(), supplyTokenWrites("""
            class DroneEntity {
                Object supplyNetworkToken;
                void carrierProbe() {
                    if (supplyNetworkToken == null || this.supplyNetworkToken != null) { }
                    String diagnostic = "supplyNetworkToken = null;";
                    // supplyNetworkToken = null;
                    /* this.supplyNetworkToken = null; */
                }
            }
            """));
    }

    @Test void tokenWriteGuardRejectsActualWritesRegardlessOfWhitespaceOrQualification() throws Exception {
        for (String statement : List.of("supplyNetworkToken=1;", "this.supplyNetworkToken = 1;",
            "this.supplyNetworkToken /* comment */\n = 1;", "(this.supplyNetworkToken) = 1;",
            "supplyNetworkToken += 1;", "++supplyNetworkToken;", "this.supplyNetworkToken--;")) {
            String fixture = "class DroneEntity { int supplyNetworkToken; void carrierProbe() { " + statement + " } }";
            assertEquals(1, supplyTokenWrites(fixture).size(), statement);
        }
    }

    @Test void tokenWriteGuardCatchesAnInjectedWriteInTheRealCarrierInterrupt() throws Exception {
        String entity = source("DroneEntity.java");
        String declaration = "private boolean carrierWorkUnchanged() {";
        assertTrue(entity.contains(declaration));
        String mutated = entity.replace(declaration, declaration + "\n this.supplyNetworkToken = null;");
        List<String> writes = supplyTokenWrites(mutated);
        assertEquals(1, writes.size());
        assertTrue(writes.getFirst().startsWith("carrierWorkUnchanged:"));
    }

    @Test void tokenWriteGuardFailsClosedOnMalformedSource() {
        assertThrows(AssertionError.class,
            () -> supplyTokenWrites("class DroneEntity { void carrierProbe() { supplyNetworkToken = ; } }"));
    }

    @Test void physicalContactAndLeaseAreCheckedAgainAtEveryTransfer() throws Exception {
        String adapter = source("CarrierDroneServiceAdapter.java");
        assertTrue(adapter.contains("drone.carrierServiceAssignedTo(carrier.getUUID())"));
        assertTrue(adapter.contains("CarrierServiceBay.stable(position(), velocity(), carrier.bayPosition(lease.slot())"));
        assertTrue(adapter.contains("l.identity().equals(identity)"));
        assertEquals(4, adapter.split("return canReceive\\(\\) \\?", -1).length - 1);
        assertTrue(adapter.contains("material.getCount() == 1"));
        assertTrue(service().contains("CarrierDroneServiceAdapter.supplyDemand(role(), securityLoadout(), kind"));
    }

    @Test void approachUsesExistingBoundedFlightAndNeverTeleports() throws Exception {
        String service = service();
        assertTrue(service.contains("DroneNavigator.localDetour(level, this, target, false)"));
        assertTrue(service.contains("FlightDynamics.steerMovingOrbit("));
        assertTrue(service.contains("smoothFlightMotion("));
        assertTrue(service.contains("setNoGravity(true)"));
        for (String forbidden : new String[] {"setPos(", "teleport", "getChunk(", "createPath(", "getEntitiesOfClass("})
            assertFalse(service.contains(forbidden), forbidden);
        assertTrue(service.contains("carrierTerrainLoaded(level, blockPosition())"));
        assertTrue(service.contains("MAX_RECOVERY_TICKS"));
    }

    @Test void restoredSessionsExpireBeforeFlyingAndRecoveryDoesNotConsumeQueuedTasks() throws Exception {
        String entity = source("DroneEntity.java");
        assertTrue(entity.contains("output.store(\"CarrierService\", CarrierDroneServiceAdapter.Session.CODEC, carrierService)"));
        assertTrue(entity.contains("input.read(\"CarrierService\", CarrierDroneServiceAdapter.Session.CODEC)"));
        String service = service();
        assertTrue(service.contains("flyCarrierLocal(level, approach, carrierServiceVelocity, false)"));
        assertTrue(service.indexOf("if (carrierServiceRestored) releaseCarrierService") < service.indexOf("flyCarrierLocal(level, approach,"));
        assertTrue(service.contains("resumeCarrierTask(level, owner)"));
        assertTrue(service.contains("dock.isOwnedBy(ownerId())"));
        assertTrue(service.contains("RECOVERY WAIT / HOME UNAVAILABLE"));
    }

    @Test void manualCommandsReleaseTheInterruptBeforeNewAssignments() throws Exception {
        String entity = source("DroneEntity.java");
        String waypoint = entity.substring(entity.indexOf("public void assignWaypoint(BlockPos pos, String missionId"),
            entity.indexOf("public void assignTrackingTarget("));
        assertTrue(waypoint.indexOf("preemptCarrierService()") < waypoint.indexOf("assignMission("));
        String mode = entity.substring(entity.indexOf("public void setMode("), entity.indexOf("public void assignFollowFormation("));
        assertTrue(mode.indexOf("preemptCarrierService()") < mode.indexOf("clearMissionAssignment()"));
        String preempt = entity.substring(entity.indexOf("private void preemptSupplyAssignment("), entity.indexOf("private void guardSupplyAssignment("));
        assertTrue(preempt.contains("preemptCarrierService()"));
    }

    @Test void bridgeDoesNotRegisterCarrierOrAutoReserveAndCabinExceptionIsOwnerSpecific() throws Exception {
        String adapter = source("CarrierDroneServiceAdapter.java");
        assertTrue(source("MorrowgearDrone.java").contains("CarrierDroneServiceAdapter.install()"));
        assertTrue(adapter.contains("CarrierModule.installDronePorts(new CarrierDroneServiceAdapter())"));
        assertFalse(adapter.contains("CarrierModule.register("));
        assertFalse(adapter.contains(".reserveBay("));
        assertTrue(adapter.contains("CarrierInterior.inside(owner.level())"));
        assertTrue(adapter.contains("exterior.getEntity(shipId) instanceof CarrierEntity"));
        assertTrue(adapter.contains("ship.owner.equals(owner.getUUID())"));
        assertTrue(source("SupplyNetworkRuntime.java").contains(
            "owner == null || !CarrierDroneServiceAdapter.ownerSupportsExterior(level, owner)"));
        assertTrue(adapter.contains("return ownedCabinCarrier(exterior, owner) != null"));
        assertTrue(adapter.contains("exterior.hasChunkAt(carrier.blockPosition())"));
    }
}
