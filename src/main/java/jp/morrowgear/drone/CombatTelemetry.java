package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;

final class CombatTelemetry {
	private static final Map<Key, Engagement> ENGAGEMENTS = new HashMap<>();

	private CombatTelemetry() {}

	static Snapshot observe(ServerLevel level, UUID owner, int targetId, List<DroneEntity> fleet) {
		Key key = new Key(level.dimension().toString(), owner, targetId);
		Engagement engagement = ENGAGEMENTS.computeIfAbsent(key, ignored -> new Engagement());
		long tick = level.getGameTime();
		if (engagement.lastTick == tick) return engagement.snapshot();
		engagement.lastTick = tick;

		Map<UUID, UnitSample> current = new HashMap<>();
		for (DroneEntity drone : fleet) {
			if (!drone.combatActive() || drone.combatTargetId() != targetId) continue;
			UnitSample sample = new UnitSample(drone.getHealth() / Math.max(1.0f, drone.getMaxHealth()),
				drone.batteryPercent(), drone.weaponPowerPercent());
			current.put(drone.getUUID(), sample);
			UnitSample previous = engagement.units.get(drone.getUUID());
			if (previous != null) {
				engagement.healthLoss += Math.max(0.0, previous.healthRatio() - sample.healthRatio()) * 100.0;
				engagement.flightDrain += Math.max(0, previous.flightBattery() - sample.flightBattery());
				engagement.weaponDrain += Math.max(0, previous.weaponPower() - sample.weaponPower());
			}
		}
		for (UUID unit : engagement.units.keySet()) {
			if (!current.containsKey(unit) && level.getEntity(unit) == null && engagement.lost.add(unit)) {
				engagement.destroyed++;
			}
		}
		engagement.units.clear();
		engagement.units.putAll(current);
		prune(tick);
		return engagement.snapshot();
	}

	static void clear() { ENGAGEMENTS.clear(); }

	private static void prune(long now) {
		ENGAGEMENTS.entrySet().removeIf(entry -> now - entry.getValue().lastTick > 400L);
	}

	private static final class Engagement {
		private final Map<UUID, UnitSample> units = new HashMap<>();
		private final Set<UUID> lost = new HashSet<>();
		private long lastTick = -1L;
		private double healthLoss;
		private int flightDrain;
		private int weaponDrain;
		private int destroyed;

		private Snapshot snapshot() {
			int pressure = Mth.clamp((int)Math.ceil(destroyed * 22.0 + healthLoss * 0.55
				+ flightDrain * 0.25 + weaponDrain * 0.12), 0, 100);
			return new Snapshot(pressure, destroyed, (int)Math.ceil(healthLoss), flightDrain, weaponDrain);
		}
	}

	record Snapshot(int pressure, int destroyed, int healthLoss, int flightDrain, int weaponDrain) {
		static Snapshot empty() { return new Snapshot(0, 0, 0, 0, 0); }
	}
	private record UnitSample(double healthRatio, int flightBattery, int weaponPower) {}
	private record Key(String dimension, UUID owner, int targetId) {}
}
