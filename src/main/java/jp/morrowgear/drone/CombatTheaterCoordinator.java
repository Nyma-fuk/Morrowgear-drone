package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

final class CombatTheaterCoordinator {
	private static final long PLAN_TTL_TICKS = 10L;
	private static final Map<Key, CachedPlan> PLANS = new HashMap<>();

	private CombatTheaterCoordinator() {}

	static Snapshot coordinate(ServerLevel level, ServerPlayer owner, List<DroneEntity> fleet) {
		Key key = new Key(level.dimension().toString(), owner.getUUID());
		long now = level.getGameTime();
		CachedPlan cached = PLANS.get(key);
		if (cached != null && now - cached.tick() < PLAN_TTL_TICKS) return cached.snapshot();

		LivingEntity recentAttacker = owner.tickCount - owner.getLastHurtByMobTimestamp() <= 200
			? owner.getLastHurtByMob() : null;
		List<CombatTheaterPolicy.Contact> contacts = FleetThreatNetwork.readAll(key.dimension(), key.owner(), now)
			.stream().filter(report -> liveHostile(level, owner, recentAttacker, report.entityId()))
			.map(report -> {
				LivingEntity target = (LivingEntity)level.getEntity(report.entityId());
				CombatTelemetry.Snapshot telemetry = CombatTelemetry.observe(level, owner.getUUID(),
					report.entityId(), fleet);
				return new CombatTheaterPolicy.Contact(report.entityId(), report.score(),
					report.enemyThreat(), report.playerDanger(), target.getHealth(), telemetry.pressure(),
					report.position(), target.getDeltaMovement(), target.distanceTo(owner),
					report.sourceWing(), report.sourceMission());
			}).toList();
		List<CombatTheaterPolicy.Unit> units = fleet.stream()
			.filter(drone -> drone.role() == DroneRole.SECURITY
				&& CombatPolicy.hasUsableWeapon(drone.securityLoadout(),
					drone.gunAmmo(), drone.missiles(), drone.laserHeat()))
			.map(drone -> new CombatTheaterPolicy.Unit(drone.unitId(), drone.groupId(),
				drone.mode() == DroneMode.STANDBY && !drone.hasSecurityPatrol() && !drone.hasActiveFieldOperation(),
				drone.hasSecurityPatrol() || drone.hasActiveFieldOperation() || drone.hasPatrolRoute()
					|| drone.mode() == DroneMode.WAYPOINT || drone.mode() == DroneMode.FOLLOW,
				drone.isDocked(), drone.serviceReturnActive(), drone.combatActive() || drone.emergencyInterceptActive(),
					drone.combatActive() ? drone.combatTargetId() : drone.emergencyTargetForCoordination(),
				drone.combatSlot(), drone.batteryPercent(), drone.weaponPowerPercent(),
				drone.recoveryLevel(), CombatPolicy.sortieReady(drone.securityLoadout(),
					drone.getHealth() / drone.getMaxHealth(), drone.batteryPercent(),
					drone.weaponPowerPercent(), drone.gunAmmo(), drone.missiles(), drone.laserHeat(), true),
				drone.position())).toList();
		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(contacts, units);
		String summary = summary(plan, units);
		String fronts = fronts(plan);
		Snapshot snapshot = new Snapshot(plan.assignments(), summary, fronts, plan.hostileCount(),
			plan.clusters().size(), plan.reserveCount());
		PLANS.put(key, new CachedPlan(now, snapshot));
		prune(now);
		return snapshot;
	}

	private static boolean liveHostile(ServerLevel level, ServerPlayer owner,
		LivingEntity recentAttacker, int entityId) {
		Entity entity = level.getEntity(entityId);
		return entity instanceof LivingEntity living && living.isAlive()
			&& ThreatAssessment.targetDisposition(living, owner, recentAttacker).engageable();
	}

	private static String summary(CombatTheaterPolicy.Plan plan, List<CombatTheaterPolicy.Unit> units) {
		if (plan.hostileCount() <= 0) return "";
		Map<String, CombatTheaterPolicy.Unit> byId = units.stream().collect(
			java.util.stream.Collectors.toMap(CombatTheaterPolicy.Unit::unitId, unit -> unit));
		long engagedWings = plan.assignments().keySet().stream().map(byId::get)
			.filter(java.util.Objects::nonNull).map(CombatTheaterPolicy.Unit::groupId).distinct().count();
		String band = plan.clusters().stream().anyMatch(cluster -> cluster.contacts().stream()
			.anyMatch(contact -> contact.playerDanger() >= 40)) ? "CRITICAL" : "ACTIVE";
		return "OPERATION AEGIS / " + band + " / " + plan.clusters().size() + " FRONTS / "
			+ plan.hostileCount() + " HOSTILES / " + engagedWings + "W "
			+ plan.assignments().size() + "U ENGAGED / " + plan.reserveCount() + " RESERVE / "
			+ units.size() + " SEC";
	}

	private static String fronts(CombatTheaterPolicy.Plan plan) {
		String visible = plan.clusters().stream().limit(4).map(cluster -> {
			long assigned = plan.assignments().values().stream()
				.filter(assignment -> assignment.clusterId().equals(cluster.id())).count();
			int danger = cluster.contacts().stream().mapToInt(CombatTheaterPolicy.Contact::playerDanger).max().orElse(0);
			return cluster.id() + " " + cluster.contacts().size() + "H/" + assigned + "U"
				+ (danger >= 40 ? "!" : "");
		}).reduce((left, right) -> left + "  |  " + right).orElse("");
		int hidden = Math.max(0, plan.clusters().size() - 4);
		return hidden > 0 ? visible + "  |  +" + hidden + " FRONTS" : visible;
	}

	private static void prune(long now) {
		PLANS.entrySet().removeIf(entry -> now - entry.getValue().tick() > 200L);
	}

	static void clear() { PLANS.clear(); }

	record Snapshot(Map<String, CombatTheaterPolicy.Assignment> assignments, String summary,
		String fronts, int hostileCount, int clusterCount, int reserveCount) {
		CombatTheaterPolicy.Assignment assignment(String unitId) { return assignments.get(unitId); }
	}
	private record Key(String dimension, UUID owner) {}
	private record CachedPlan(long tick, Snapshot snapshot) {}
}
