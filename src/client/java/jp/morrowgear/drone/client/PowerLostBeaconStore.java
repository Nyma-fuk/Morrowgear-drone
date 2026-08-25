package jp.morrowgear.drone.client;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jp.morrowgear.drone.PowerLostBeaconLeasePolicy;
import jp.morrowgear.drone.network.PowerLostBeaconPayload;
import net.minecraft.world.phys.Vec3;

final class PowerLostBeaconStore {
	private static final Map<String, Beacon> BEACONS = new ConcurrentHashMap<>();

	private PowerLostBeaconStore() {
	}

	static void accept(PowerLostBeaconPayload payload) {
		if (!payload.active()) {
			BEACONS.remove(payload.unitId());
			return;
		}
		BEACONS.put(payload.unitId(), new Beacon(payload.unitId(), payload.dimension(),
			new Vec3(payload.x(), payload.y(), payload.z()), payload.serverTick()));
	}

	static List<Beacon> snapshot(long currentServerTick) {
		BEACONS.entrySet().removeIf(entry -> !PowerLostBeaconLeasePolicy.isCurrent(
			entry.getValue().detectedTick(), currentServerTick));
		return BEACONS.values().stream().sorted(Comparator.comparing(Beacon::unitId)).toList();
	}

	static void clear() {
		BEACONS.clear();
	}

	record Beacon(String unitId, String dimension, Vec3 position, long detectedTick) {
	}
}
