package jp.morrowgear.drone;

import java.util.UUID;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/** Clears derived runtime state without touching any persisted mission or inventory data. */
final class RuntimeCacheLifecycle {
	private static boolean registered;

	private RuntimeCacheLifecycle() {}

	static synchronized void register() {
		if (registered) return;
		registered = true;
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> clearOwner(handler.player.getUUID()));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> clearAll());
	}

	static void clearOwner(UUID owner) {
		MorrowgearDrone.clearDroneRosterCache(owner);
		ThreatAssessment.clearOwner(owner);
		NavigationEnvironment.clearOwner(owner);
		DefenseAllocation.clearOwner(owner);
		ThreatSensorRuntime.clearOwner(owner);
	}

	static void clearAll() {
		MorrowgearDrone.clearDroneRosterCache();
		ThreatAssessment.clear();
		NavigationEnvironment.clear();
		DefenseAllocation.clear();
		ThreatSensorRuntime.clear();
		OrbitAltitudePlanner.clear();
		BaseDefenseOperation.clear();
		FleetThreatNetwork.clear();
		MissionDataLink.clear();
		SalvageMissionRegistry.clear();
		FieldOperationRegistry.clear();
		CombatManeuverRegistry.clear();
		CombatAirspaceRegistry.clear();
		CombatTelemetry.clear();
		CombatTheaterCoordinator.clear();
	}
}
