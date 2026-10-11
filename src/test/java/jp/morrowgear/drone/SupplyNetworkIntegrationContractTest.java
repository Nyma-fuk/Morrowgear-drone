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

	@Test void schedulingRunsAfterAllEntitiesAndTopologyChangesWakeItImmediately() throws Exception {
		String runtime = source("SupplyNetworkRuntime.java");
		String registration = runtime.substring(runtime.indexOf("public static synchronized void register()"),
			runtime.indexOf("/** Implement in DroneEntity"));
		assertTrue(registration.contains("ServerTickEvents.END_LEVEL_TICK.register"));
		String droneTick = runtime.substring(runtime.indexOf("public static void tickFromDrone("),
			runtime.indexOf("private static void tickLevel("));
		assertTrue(droneTick.contains("data.requestEvaluation()"));
		assertFalse(droneTick.contains("tick(level,"));
		String levelTick = runtime.substring(runtime.indexOf("private static void tickLevel("),
			runtime.indexOf("public static Configuration configure("));
		assertTrue(levelTick.contains("SupplyNetworkSchedulePolicy.shouldEvaluate"));
		assertTrue(levelTick.contains("data.consumeEvaluationRequest()"));
		assertTrue(runtime.contains("configured = data.registry().configure"));
		assertTrue(runtime.contains("configured = registry.configure"));
	}

	@Test void newWaypointAndModePreemptBeforeChangingAssignment() throws Exception {
		String entity = source("DroneEntity.java");
		String waypoint = entity.substring(entity.indexOf("public void assignWaypoint(BlockPos pos, String missionId"),
			entity.indexOf("public void assignTrackingTarget("));
		assertTrue(waypoint.indexOf("preemptSupplyAssignment()") < waypoint.indexOf("assignMission("));
		String mode = entity.substring(entity.indexOf("public void setMode("), entity.indexOf("public void assignFollowFormation("));
		assertTrue(mode.indexOf("preemptSupplyAssignment()") < mode.indexOf("clearMissionAssignment()"));
		assertTrue(entity.contains("if (!supplyOwnsMission() || role() != DroneRole.CARGO"));
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
		assertTrue(hooks.contains("SUPPLY NETWORK READY / NEXT ROUTE SCAN"));
		assertFalse(hooks.contains("drone.requestDockSlot()"));
		assertFalse(hooks.contains("drone.entityData.set(MODE, DroneMode.DOCK.id())"));
		assertTrue(hooks.contains("SupplyNetworkPolicy.protectedGroup(drone.groupId()) && !drone.isDocked()"));
		assertFalse(hooks.contains("SupplyNetworkPolicy.protectedGroup(groupId()) || role()"));
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

	@Test void sourceDiscoveryIsServerBoundedAndDockUiDoesNotOwnWarehouseSelection() throws Exception {
		String panel = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/SupplyNetworkPanel.java"));
		assertTrue(panel.contains("自動補給 常時ON"));
		assertFalse(panel.contains("Checkbox.builder"));
		assertFalse(panel.contains("設定を保存"));
		assertFalse(panel.contains("buildSourceMap()"));
		assertFalse(panel.contains("補給元を選ぶ"));
		assertFalse(panel.contains("getBlockEntities()"));
		String map = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/SupplyNodeMapScreen.java"));
		assertTrue(map.contains("物流ノード管理"));
		assertTrue(map.contains("new SupplyNodeConfigPayload"));
		assertTrue(map.contains("登録解除"));
		String transport = source("SupplyNetworkTransport.java");
		assertTrue(transport.contains("SupplyNetworkPolicy.DISCOVERY_RADIUS"));
		assertTrue(transport.contains("SupplyNetworkPolicy.DISCOVERY_VERTICAL"));
		assertTrue(transport.contains("registered.put"));
		String runtime = source("SupplyNetworkRuntime.java");
		assertTrue(runtime.contains("selectSource(level, registry, demand, target)"));
		assertTrue(runtime.contains("data.nodes(route.owner(), route.dimension())"));
	}

	@Test void everyOwnedDockAutoEnrollsAndLegacyDisableCannotTurnItOff() throws Exception {
		String runtime = source("SupplyNetworkRuntime.java");
		assertTrue(runtime.contains("ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register"));
		assertTrue(runtime.contains("ensureDock(level, dock)"));
		String configure = runtime.substring(runtime.indexOf("public static Configuration configureDock("),
			runtime.indexOf("public static Configuration ensureDock("));
		assertTrue(configure.contains("current == null ? SupplyNetworkPolicy.defaults() : current.rules(), true"));
		String legacy = runtime.substring(runtime.indexOf("public static boolean disable("),
			runtime.indexOf("/** Called once per level tick"));
		assertTrue(legacy.contains("configureDock(owner, dock, true)"));
		String payload = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/network/SupplyNetworkConfigPayload.java"));
		assertTrue(payload.contains("configureDock(player, BlockPos.of(dock), true)"));
	}

	@Test void completedDeliveryCanRedispatchTheSameAircraftImmediately() throws Exception {
		String runtime = source("SupplyNetworkRuntime.java");
		String unload = runtime.substring(runtime.indexOf("private static ServiceResult unload("),
			runtime.indexOf("private static List<Demand> pendingDemands("));
		assertTrue(unload.indexOf("hooks.stop(drone, job.token(), false, Status.READY)")
			< unload.indexOf("dispatchNext(level, registry, drone, hooks)"));
		String next = runtime.substring(runtime.indexOf("private static boolean dispatchNext("),
			runtime.indexOf("public static void cancel("));
		assertTrue(next.contains("pendingDemands(level, registry)"));
		assertTrue(next.contains("List.of(drone)"));
		assertTrue(next.contains("route().owner().equals(drone.ownerId())"));
		assertTrue(unload.contains("if (!dispatchNext(level, registry, drone, hooks)) hooks.idleAfterRoute(drone)"));
		String hooks = source("DroneEntity.java");
		assertTrue(hooks.contains("idleAfterRoute(DroneEntity drone) { drone.requestLogisticsDockReturn(); }"));
		String returnPath = hooks.substring(hooks.indexOf("private void requestLogisticsDockReturn()"),
			hooks.indexOf("public void setMode(", hooks.indexOf("private void requestLogisticsDockReturn()")));
		assertTrue(returnPath.contains("supplyNetworkToken != null || !cargo.isEmpty()"));
		assertTrue(returnPath.contains("entityData.set(MODE, DroneMode.DOCK.id())"));
		assertTrue(returnPath.contains("requestDockSlot()"));
	}

	@Test void retainedLoadsNeverCollapseToUnassignedAndAllHeldLoadsRetryFallback() throws Exception {
		String entity = source("DroneEntity.java");
		String hooks = entity.substring(entity.indexOf("public static final SupplyNetworkRuntime.CargoHooks"),
			entity.indexOf("private long cargoServiceReadyTick"));
		assertTrue(hooks.contains("CargoMissionPolicy.onNetworkHold(retainCargo)"));
		String runtime = source("SupplyNetworkRuntime.java");
		String held = runtime.substring(runtime.indexOf("if (!registry.current(job) || job.stage() == Stage.HELD)"),
			runtime.indexOf("if (!cargoMatches(job, hooks.cargo(drone)))"));
		assertTrue(held.contains("tryFallback(level, registry, drone, job.token(), hooks)"));
		assertFalse(held.contains("job.status() == Status.DOCK_LOST"));
	}

	@Test void completedDeliveriesAreFairAndDoNotImmediatelyTopOffOneDockForever() throws Exception {
		String runtime = source("SupplyNetworkRuntime.java");
		String pending = runtime.substring(runtime.indexOf("private static List<Demand> pendingDemands("),
			runtime.indexOf("private static boolean dispatchNext("));
		assertTrue(pending.contains("SupplyNetworkPolicy.topOffReady"));
		assertTrue(pending.contains("data.lastDelivery(d.config().route(), d.rule().kind())"));
		String unload = runtime.substring(runtime.indexOf("private static ServiceResult unload("),
			runtime.indexOf("private static List<Demand> pendingDemands("));
		assertTrue(unload.contains("recordDelivery(job.route(), job.kind(), level.getGameTime())"));
	}
}
