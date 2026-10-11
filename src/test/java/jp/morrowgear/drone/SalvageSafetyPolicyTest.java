package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SalvageSafetyPolicyTest {
	@Test void engineerClearsTheEntireTowEnvelopeRatherThanOnlyTheDisabledAircraft() {
		double loadWidth = 3.0;
		double radius = SalvageClearance.corridorRadius(loadWidth);
		assertTrue(radius >= loadWidth * 0.5 + SalvageTowPolicy.ROPE_LENGTH);
		assertTrue(radius > loadWidth * 0.5 + 0.65);
	}
    @Test void onlyCommittedLoadsRejectOperatorFlightChanges() {
        for (SalvageState state : SalvageState.values()) {
            assertFalse(SalvageSafetyPolicy.committed(state, false));
            assertEquals(state.ordinal() >= SalvageState.HOOK.ordinal(), SalvageSafetyPolicy.committed(state, true));
        }
    }

    @Test void solarMayPreemptInterceptAndReturnButNotAttachmentOrTransfer() {
        assertTrue(SalvageSafetyPolicy.mayDivertToSolar(SalvageState.INTERCEPT));
        assertTrue(SalvageSafetyPolicy.mayDivertToSolar(SalvageState.RETURN));
        for (SalvageState state : new SalvageState[]{SalvageState.HOOK, SalvageState.HOIST,
            SalvageState.DELIVER, SalvageState.SERVICE}) assertFalse(SalvageSafetyPolicy.mayDivertToSolar(state));
        assertFalse(SalvageInterceptPolicy.controlsNavigation(SalvageState.INTERCEPT, true, true, false));
        assertTrue(SalvageInterceptPolicy.controlsNavigation(SalvageState.INTERCEPT, true, false, false));
    }

    @Test void busyEngineersIncludingMaterialWaitAreNeverBorrowed() {
        for (EngineerState state : EngineerState.values())
            assertEquals(state == EngineerState.IDLE, SalvageSafetyPolicy.engineerAvailable(state));
    }

    @Test void onlyApproachToTheSameObstructedLostTargetCanBePromoted() {
        var lost = java.util.UUID.randomUUID();
        var other = java.util.UUID.randomUUID();
        for (EngineerState state : EngineerState.values()) {
            assertEquals(state == EngineerState.IDLE || state == EngineerState.APPROACH,
                SalvageSafetyPolicy.engineerAvailable(state, lost, lost, true, true));
            assertEquals(state == EngineerState.IDLE,
                SalvageSafetyPolicy.engineerAvailable(state, other, lost, true, true));
            assertEquals(state == EngineerState.IDLE,
                SalvageSafetyPolicy.engineerAvailable(state, lost, lost, false, true));
            assertEquals(state == EngineerState.IDLE,
                SalvageSafetyPolicy.engineerAvailable(state, lost, lost, true, false));
            assertEquals(state == EngineerState.IDLE,
                SalvageSafetyPolicy.engineerAvailable(state, null, lost, true, true));
        }
    }

    @Test void repairResumeRejectsMissingChangedRepairedLostAndRecoveringTargets() {
        for (int bits = 0; bits < 32; bits++) {
            boolean alive = (bits & 1) != 0, owned = (bits & 2) != 0, damaged = (bits & 4) != 0;
            boolean lost = (bits & 8) != 0, recovering = (bits & 16) != 0;
            assertEquals(bits == 7, SalvageSafetyPolicy.repairResumable(alive, owned, damaged, lost, recovering));
        }
    }

    @Test void promotionPreservesSnapshotBeforeClearingRepairAndUsesSharedResumeValidation() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java")).replace("\r\n", "\n");
        assertTrue(source.contains("best.clearanceResumeTask = best.currentTaskSnapshot();\n\t\tbest.clearEngineerAssignment();"));
        assertTrue(source.contains("boolean obstructed = SalvageClearance.obstruction(level, load) != null;"));
        assertTrue(source.contains("load.getUUID(), load.isPowerLost(), obstructed)"));
        assertTrue(source.contains("case ENGINEER -> role() == DroneRole.ENGINEER && task.targetId() != null"));
        assertTrue(source.contains("SalvageSafetyPolicy.repairResumable(target.isAlive()"));
    }

    @Test void unloadPreservesRelationshipButDestructionAndDimensionTransferDoNot() {
        assertTrue(SalvageSafetyPolicy.preserveOnRemoval(false, true));
        assertFalse(SalvageSafetyPolicy.preserveOnRemoval(true, false));
        assertFalse(SalvageSafetyPolicy.preserveOnRemoval(true, true));
        assertFalse(SalvageSafetyPolicy.preserveOnRemoval(false, false));
    }

    @Test void eitherPartnerGetsBoundedLoadGraceRatherThanImmediateReleaseOrPermanentFreeze() {
        for (int tick = 0; tick < SalvageSafetyPolicy.PARTNER_LOAD_GRACE_TICKS; tick++)
            assertTrue(SalvageSafetyPolicy.waitForPartner(tick));
        assertFalse(SalvageSafetyPolicy.waitForPartner(SalvageSafetyPolicy.PARTNER_LOAD_GRACE_TICKS));
        assertFalse(SalvageSafetyPolicy.waitForPartner(-1));
    }

    @Test void operatorGuardsPrecedeMutationWhileInternalRescueTransitionsStayAccessible() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java")).replace("\r\n", "\n");
		for (String signature : new String[]{"public void setMode(DroneMode mode)",
            "public void requestManualDockReturn()",
            "public void assignTrackingTarget(LivingEntity target, String missionId, int expected, int index, BlockPos origin, long assignedTick)"})
			assertTrue(source.contains(signature + " {\n\t\tif (rejectProtectedFlightCommand()) return;"), signature);
		assertTrue(source.contains("public void assignWaypoint(BlockPos pos, String missionId, int expected, int index, BlockPos origin, long assignedTick) {\n"
			+ "\t\tif (!(unitId() + \"-cargo\").equals(missionId) && rejectProtectedFlightCommand()) return;"));
        assertTrue(source.contains("case HOOK -> {\n\t\t\t\tsetModeInternal(DroneMode.STANDBY)"));
        assertTrue(source.contains("setSalvageState(SalvageState.RETURN);\n\t\t\t\t\tsetModeInternal(DroneMode.DOCK)"));
        assertTrue(source.contains("assignWaypointInternal(BlockPos.containing(SalvageInterceptPolicy.approachPosition(nearest.position()"));
        assertTrue(source.contains("serviceReturn || solarServiceFlight, dockTarget != null"));
        assertTrue(source.contains("candidate.salvageSuspendedBy != null"));
        assertTrue(source.contains("reason == RemovalReason.UNLOADED_TO_CHUNK || reason == RemovalReason.UNLOADED_WITH_PLAYER"));
        assertTrue(source.contains("SalvageSafetyPolicy.preserveOnRemoval(reason.shouldDestroy(), unloaded)"));
    }
}
