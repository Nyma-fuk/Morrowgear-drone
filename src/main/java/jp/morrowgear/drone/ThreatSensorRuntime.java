package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

/** One level entity scan plus owner-scoped hostility indexes per server tick. */
final class ThreatSensorRuntime {
	private static final Map<ServerLevel, LevelSnapshot> LEVELS = new WeakHashMap<>();

	private ThreatSensorRuntime() {}

	static synchronized void clear() { LEVELS.clear(); }
	static synchronized void clearOwner(UUID owner) {
		LEVELS.values().forEach(snapshot -> snapshot.owners.remove(owner));
	}

	static List<LivingEntity> engageable(ServerLevel level, ServerPlayer owner,
		LivingEntity recentAttacker, AABB area) {
		return within(snapshot(level, owner, recentAttacker).engageable, area);
	}

	static List<LivingEntity> vanillaEnemies(ServerLevel level, ServerPlayer owner,
		LivingEntity recentAttacker, AABB area) {
		return within(snapshot(level, owner, recentAttacker).vanillaEnemies, area);
	}

	private static Snapshot snapshot(ServerLevel level, ServerPlayer owner, LivingEntity recentAttacker) {
		long tick = level.getGameTime();
		LevelSnapshot levelSnapshot = LEVELS.get(level);
		if (levelSnapshot == null || levelSnapshot.tick != tick) {
			List<LivingEntity> living = new ArrayList<>();
			List<LivingEntity> vanilla = new ArrayList<>();
			for (var entity : level.getAllEntities()) {
				if (!(entity instanceof LivingEntity candidate) || !candidate.isAlive()) continue;
				living.add(candidate);
				if (candidate instanceof Enemy) vanilla.add(candidate);
			}
			levelSnapshot = new LevelSnapshot(tick, List.copyOf(living), List.copyOf(vanilla), new HashMap<>());
			LEVELS.put(level, levelSnapshot);
		}
		Snapshot cached = levelSnapshot.owners.get(owner.getUUID());
		if (cached != null && cached.tick == tick && cached.recentAttacker == recentAttacker) return cached;
		List<LivingEntity> engageable = new ArrayList<>();
		for (LivingEntity living : levelSnapshot.living) {
			if (ThreatAssessment.isThreat(living, owner, recentAttacker)) engageable.add(living);
		}
		Snapshot snapshot = new Snapshot(tick, recentAttacker, List.copyOf(engageable), levelSnapshot.vanillaEnemies);
		levelSnapshot.owners.put(owner.getUUID(), snapshot);
		return snapshot;
	}

	private static List<LivingEntity> within(List<LivingEntity> source, AABB area) {
		return source.stream().filter(entity -> area.intersects(entity.getBoundingBox())).toList();
	}

	static int worldScansForTest(int owners, int sensorsPerOwner) {
		return owners <= 0 || sensorsPerOwner <= 0 ? 0 : 1;
	}
	static int worldScansForTest(int sensors) { return worldScansForTest(1, sensors); }

	private record LevelSnapshot(long tick, List<LivingEntity> living,
		List<LivingEntity> vanillaEnemies, Map<UUID, Snapshot> owners) {}

	private record Snapshot(long tick, LivingEntity recentAttacker, List<LivingEntity> engageable,
		List<LivingEntity> vanillaEnemies) {}
}
