package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Source wiring guards plus allocator policy checks; not live entity execution. */
final class DynamicDockReturnContractTest {
	private static final Path JAVA = Path.of("src/main/java/jp/morrowgear/drone");

	private String entitySection(String start, String end) throws Exception {
		String source = Files.readString(JAVA.resolve("DroneEntity.java"));
		return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
	}

	@Test void allSelectionSendsReturnWithoutRequiringAnExistingReservation() throws Exception {
		String ui = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
		String execute = ui.substring(ui.indexOf("private void execute("), ui.indexOf("private String actionLabel("));
		assertTrue(execute.contains("for (int id : selected) ClientPlayNetworking.send(new DroneCommandPayload(id, ACTIONS[index]))"));
		assertFalse(execute.contains("hasDock()"));
		String server = Files.readString(JAVA.resolve("MorrowgearDrone.java"));
		String simple = server.substring(server.indexOf("if (!DroneCommandPolicy.isSimpleAction"),
			server.indexOf("MorrowgearRuntimeVerifier.register()"));
		assertFalse(simple.contains("hasDock()"));
		assertTrue(simple.contains("if (payload.action().equals(\"dock\")) drone.requestManualDockReturn()"));
	}

	@Test void manualReturnPreemptsEveryWeaponWithoutChangingAutomaticSetMode() throws Exception {
		String manual = entitySection("public void requestManualDockReturn()", "public void setMode(");
		assertTrue(manual.indexOf("emergencyLaunchedFromDock = false") < manual.indexOf("clearEmergencyInterception()"));
		assertTrue(manual.contains("clearCombatState()"));
		assertTrue(manual.contains("clearSolarService(false)"));
		assertTrue(manual.contains("manualDockReturn = true"));
		assertTrue(manual.contains("setMode(DroneMode.DOCK)"));
		String mode = entitySection("public void setMode(", "public void assignFollowFormation(");
		assertTrue(mode.contains("if (mode != DroneMode.DOCK) manualDockReturn = false"));
		assertTrue(mode.contains("taskStack.queue(currentTaskSnapshot(mode))"));
		assertFalse(mode.contains("clearCombatState()"));
		assertTrue(entitySection("private void updateEmergencyInterception(", "private void clearEmergencyInterception()")
			.contains("if (manualDockReturn) return"));
		assertTrue(entitySection("private void updateCombat(", "private void clearCombatState()")
			.contains("if (manualDockReturn && !isDocked()) return"));
		assertTrue(entitySection("private void updateSolarService(", "private double solarMissionDistance(")
			.contains("if (manualDockReturn || isDocked() || serviceReturn"));
	}

	@Test void lostDockAndReloadKeepTheOutstandingRequestAndServiceMission() throws Exception {
		String clear = entitySection("public void clearDock()", "private void releaseDockReservation()");
		assertTrue(clear.contains("requestDockSlot()"));
		assertTrue(clear.contains("entityData.set(DOCK_HOLDING, true)"));
		assertFalse(clear.contains("setMode(DroneMode.STANDBY)"));
		assertFalse(clear.contains("serviceReturn = false"));
		String entity = Files.readString(JAVA.resolve("DroneEntity.java"));
		assertTrue(entity.contains("output.putBoolean(\"ManualDockReturn\", manualDockReturn)"));
		assertTrue(entity.contains("manualDockReturn = input.getBooleanOr(\"ManualDockReturn\", false)"));
		assertTrue(entity.contains("serviceReturn = input.getBooleanOr(\"ServiceReturn\", false);"));
		String allocation = entitySection("private void maintainDockAllocation(", "private void enterDockHolding(");
		assertEquals(2, allocation.split("dock.isOwnedBy\\(owner\\)", -1).length - 1);
		assertTrue(allocation.contains("DockAllocationRuntime.docks(level, owner)"));
	}

	@Test void busyQueuePromotesOrdinaryReturnAfterServiceReservation() {
		UUID ordinary = new UUID(0, 1);
		UUID service = new UUID(0, 2);
		UUID yielding = new UUID(0, 3);
		var request = new DockAllocationPolicy.Request(ordinary, 0, 1);
		var urgent = new DockAllocationPolicy.Request(service, 1, 2);
		var parked = new DockAllocationPolicy.Request(yielding, 201, 1);
		assertEquals(2, DockAllocationPolicy.queuePosition(List.of(request, urgent), ordinary));
		assertEquals(1, DockAllocationPolicy.queuePosition(List.of(request, parked), ordinary));
		assertFalse(DockAllocationPolicy.mayYield(true, false, false, false, 100, 199));
		assertTrue(DockAllocationPolicy.mayYield(true, false, false, false, 100, 200));
		assertFalse(DockAllocationPolicy.mayYield(true, true, false, false, 100, 200));
	}

	@Test void standbyCancelsHoldingOutsideTheUndockingBranchButNotRequiredService() throws Exception {
		String mode = entitySection("public void setMode(", "public void assignFollowFormation(");
		assertTrue(mode.contains("if (mode != DroneMode.STANDBY) {\n\t\t\tentityData.set(DOCKED, false);\n\t\t}"));
		int cancellation = mode.indexOf("dockRequestTick = -1L");
		assertTrue(cancellation > mode.indexOf("if (serviceReturn)"));
		assertTrue(mode.substring(cancellation).contains("entityData.set(DOCK_HOLDING, false)"));
		assertTrue(mode.substring(mode.indexOf("if (serviceReturn)"), cancellation).contains("return;"));
	}

	@Test void runtimeProbeRequiresLateDockActualLandingNotJustAReservation() throws Exception {
		String airborne = entitySection("void beginAirborneServiceReturnForVerification(",
			"void resetCombatForVerification(");
		assertTrue(airborne.contains("beginServiceReturn(reason)"));
		assertFalse(airborne.contains("setDockedForVerification(true)"));
		String verifier = Files.readString(JAVA.resolve("MorrowgearRuntimeVerifier.java"));
		String dynamic = verifier.substring(verifier.indexOf("private static final class DynamicDockVerificationSession"),
			verifier.indexOf("private static final class DockExhaustionVerificationSession"));
		assertTrue(dynamic.contains("ordinary.requestManualDockReturn()"));
		assertTrue(dynamic.contains("service.beginAirborneServiceReturnForVerification"));
		assertTrue(dynamic.contains("!service.hasDock() && !service.isDocked()"));
		assertTrue(dynamic.contains("if (elapsed < 40) return"));
		assertTrue(dynamic.contains("noDockHoldingObserved && lateDockCreated && serviceLandingObserved && ordinary.isDocked()"));
		assertTrue(dynamic.contains("ordinary.dockPos().equals(dockPos.offset(10, 0, 0))"));
		assertTrue(dynamic.contains("DockServicePolicy.serviceEnvelope(ordinary.position()"));
	}
}
