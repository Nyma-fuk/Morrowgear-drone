package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class SupplyNetworkIntegrationContractTest {
	private static String source(String file) throws Exception {
		return Files.readString(Path.of("src/main/java/jp/morrowgear/drone", file));
	}

	@Test void physicalArrivalInterceptsBeforeUnfilteredCargoOperations() throws Exception {
		String entity = source("DroneEntity.java");
		String cargoTick = entity.substring(entity.indexOf("private void tickCargoMission("), entity.indexOf("private void resetCargoAccessTiming("));
		assertTrue(cargoTick.indexOf("guardSupplyAssignment(level)") < cargoTick.indexOf("assignWaypoint("));
		assertTrue(cargoTick.indexOf("supplyNetworkToken != null && (combatActive()") < cargoTick.indexOf("assignWaypoint("));
		assertTrue(cargoTick.indexOf("level.hasChunkAt(endpoint)") < cargoTick.indexOf("level.getBlockEntity(endpoint)"));
		assertTrue(cargoTick.indexOf("SupplyNetworkRuntime.serviceEndpoint(") < cargoTick.indexOf("loadCargo(container)"));
		assertTrue(cargoTick.indexOf("SupplyNetworkRuntime.serviceEndpoint(") < cargoTick.indexOf("unloadCargo(container)"));
		assertTrue(cargoTick.contains("supply != SupplyNetworkRuntime.ServiceResult.NOT_NETWORK"));
		assertTrue(cargoTick.contains("cargoSource().equals(cargoTarget()) && supplyNetworkToken == null"));
	}

	@Test void usesSharedContainerLeaseAndNeverAddsAnotherCoordinator() throws Exception {
		String entity = source("DroneEntity.java");
		String hooks = entity.substring(entity.indexOf("public static final SupplyNetworkRuntime.CargoHooks"), entity.indexOf("private long cargoServiceReadyTick"));
		assertTrue(hooks.contains("CARGO_ACCESS.request(new ContainerAccessKey("));
		assertTrue(hooks.contains("drone.releaseCargoAccess()"));
		assertFalse(source("SupplyNetworkRuntime.java").contains("new ContainerAccessCoordinator"));
	}

	@Test void persistedTokenAndCargoTaskGenerationAreBothWired() throws Exception {
		String entity = source("DroneEntity.java");
		assertTrue(entity.contains("output.store(\"SupplyNetworkToken\", SupplyNetworkSavedData.TOKEN_CODEC, supplyNetworkToken)"));
		assertTrue(entity.contains("input.read(\"SupplyNetworkToken\", SupplyNetworkSavedData.TOKEN_CODEC)"));
		assertTrue(entity.contains("\"#supply:\" + supplyNetworkToken.id() + \":\" + supplyNetworkToken.generation()"));
		assertTrue(entity.contains("SupplyNetworkRuntime.tickFromDrone(level, this, SUPPLY_CARGO_HOOKS)"));
	}

	@Test void newWaypointAndModePreemptBeforeChangingAssignment() throws Exception {
		String entity = source("DroneEntity.java");
		String waypoint = entity.substring(entity.indexOf("public void assignWaypoint(BlockPos pos, String missionId"),
			entity.indexOf("public void assignTrackingTarget("));
		assertTrue(waypoint.indexOf("preemptSupplyAssignment()") < waypoint.indexOf("assignMission("));
		String mode = entity.substring(entity.indexOf("public void setMode("), entity.indexOf("public void assignFollowFormation("));
		assertTrue(mode.indexOf("preemptSupplyAssignment()") < mode.indexOf("clearMissionAssignment()"));
		assertTrue(entity.contains("if (!supplyOwnsMission() || SupplyNetworkPolicy.protectedGroup(groupId())"));
	}

	@Test void supplyNeverAssignsOrClearsWingMembershipAndPreservesServiceTasks() throws Exception {
		String entity = source("DroneEntity.java");
		String hooks = entity.substring(entity.indexOf("public static final SupplyNetworkRuntime.CargoHooks"), entity.indexOf("private long cargoServiceReadyTick"));
		assertFalse(hooks.contains("assignGroup("));
		assertFalse(hooks.contains("entityData.set(GROUP"));
		assertFalse(hooks.contains("taskStack.clear()"));
		assertFalse(hooks.contains("serviceReturn = false"));
		assertFalse(hooks.contains("clearSolarService("));
		assertTrue(hooks.contains("task.kind() == DroneTaskStack.Kind.CARGO"));
	}

	@Test void schedulerCannotMoveItemsAndFallbackSearchRemainsBounded() throws Exception {
		String runtime = source("SupplyNetworkRuntime.java");
		String scheduler = runtime.substring(runtime.indexOf("public static void tick("), runtime.indexOf("private static boolean eligible("));
		assertFalse(scheduler.contains("removeItem("));
		assertFalse(scheduler.contains("insertNetworkSupply("));
		assertFalse(runtime.contains("getEntitiesOfClass("));
		assertFalse(runtime.contains("getChunk("));
		assertTrue(runtime.contains("registry.configurations().stream().filter"));
		assertTrue(runtime.contains("SupplyNetworkPolicy.MAX_ROUTE_DISTANCE"));
	}
}
