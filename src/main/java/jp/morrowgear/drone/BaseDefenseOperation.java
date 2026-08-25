package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;

final class BaseDefenseOperation {
	private static final long ALERT_TICKS = 80L;
	private static final long RECOVERY_TICKS = 140L;
	private static final Map<UUID, Session> SESSIONS = new HashMap<>();

	private BaseDefenseOperation() {}

	static synchronized void start(ServerPlayer player, BlockPos center) {
		SESSIONS.put(player.getUUID(), new Session(player.getUUID(), player.level().dimension().toString(),
			center.immutable(), Phase.ALERT, 0, player.level().getGameTime(), 0, "N0 E0 S0 W0"));
	}

	static synchronized void tick(MinecraftServer server) {
		SESSIONS.replaceAll((ownerId, session) -> update(server, session));
		SESSIONS.entrySet().removeIf(entry -> entry.getValue().phase() == Phase.COMPLETE
			&& entry.getValue().phaseTicks() > 240L);
	}

	static synchronized Snapshot snapshot(UUID ownerId, String dimension) {
		Session session = SESSIONS.get(ownerId);
		if (session == null || !session.dimension().equals(dimension)) return Snapshot.inactive();
		return new Snapshot(true, summary(session), session.fronts());
	}

	private static Session update(MinecraftServer server, Session current) {
		ServerPlayer player = server.getPlayerList().getPlayer(current.ownerId());
		if (player == null || !player.level().dimension().toString().equals(current.dimension())) return current;
		ServerLevel level = player.level();
		long phaseTicks = level.getGameTime() - current.phaseStarted();
		List<LivingEntity> hostiles = hostiles(level, current.center());
		publishContacts(level, player, current, hostiles);
		String fronts = frontSummary(current.center(), hostiles);
		if (current.phase() == Phase.ALERT && phaseTicks >= ALERT_TICKS) {
			MorrowgearBaseGenerator.launchOperationWave(level, player, current.center(), 0);
			return current.transition(Phase.CONTACT, level.getGameTime(), hostiles.size(), fronts);
		}
		if (shouldEnterRecovery(current.phase(), hostiles.size(), phaseTicks)) {
			player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"[MORROWGEAR BASE] SERVICE WINDOW / FLEET RECOVERY"));
			return current.transition(Phase.RECOVERY, level.getGameTime(), 0, fronts);
		}
		if (current.phase() == Phase.RECOVERY && phaseTicks >= RECOVERY_TICKS) {
			Phase next = nextAfterRecovery(current.waveIndex());
			if (next.wave()) MorrowgearBaseGenerator.launchOperationWave(level, player, current.center(),
				current.waveIndex() + 1);
			else player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
				"[MORROWGEAR BASE] SECTOR SECURE / DEFENSE OPERATION COMPLETE"));
			return current.transition(next, level.getGameTime(), hostiles.size(), fronts);
		}
		return current.withStatus(phaseTicks, hostiles.size(), fronts);
	}

	static boolean shouldEnterRecovery(Phase phase, int hostiles, long phaseTicks) {
		return phase != null && phase.wave() && hostiles == 0 && phaseTicks >= 100L;
	}

	static Phase nextAfterRecovery(int waveIndex) {
		return switch (waveIndex) {
			case 0 -> Phase.ASSAULT;
			case 1 -> Phase.SIEGE;
			default -> Phase.COMPLETE;
		};
	}

	private static List<LivingEntity> hostiles(ServerLevel level, BlockPos center) {
		return level.getEntitiesOfClass(LivingEntity.class, new AABB(center).inflate(72, 28, 72),
			entity -> entity instanceof Enemy && entity.isAlive());
	}

	private static void publishContacts(ServerLevel level, ServerPlayer player, Session session,
		List<LivingEntity> hostiles) {
		for (LivingEntity hostile : hostiles) {
			int enemyThreat = ThreatAssessment.entityThreat(hostile);
			boolean targetingPlayer = hostile instanceof Mob mob && mob.getTarget() == player;
			int playerDanger = EnemyThreatPolicy.playerDanger(enemyThreat, hostile.distanceTo(player),
				targetingPlayer, false, false);
			int score = Mth.clamp((int)Math.ceil(enemyThreat * .72 + playerDanger * .58
				+ Math.min(24, Math.max(0, hostiles.size() - 1))), 1, 100);
			FleetThreatNetwork.publish(session.dimension(), session.ownerId(), "BASE-" + hostile.getId(),
				new FleetThreatNetwork.Report(score, enemyThreat, playerDanger, hostile.position(),
					hostile.getId(), "BASE-SENSOR", "BASE-DEFENSE", level.getGameTime()));
		}
	}

	private static String frontSummary(BlockPos center, List<LivingEntity> hostiles) {
		int north = 0, east = 0, south = 0, west = 0;
		for (LivingEntity hostile : hostiles) {
			double dx = hostile.getX() - center.getX();
			double dz = hostile.getZ() - center.getZ();
			if (Math.abs(dx) > Math.abs(dz)) {
				if (dx >= 0) east++; else west++;
			} else if (dz >= 0) south++; else north++;
		}
		return "FRONTS N" + north + " E" + east + " S" + south + " W" + west;
	}

	private static String summary(Session session) {
		return "BASE DEFENSE / " + session.phase().label + " / HOSTILES " + session.hostiles()
			+ " / WAVE " + Math.min(3, session.waveIndex() + 1) + "/3";
	}

	enum Phase {
		ALERT("ALERT"), CONTACT("CONTACT"), ASSAULT("ASSAULT"), SIEGE("SIEGE"),
		RECOVERY("SERVICE WINDOW"), COMPLETE("SECTOR SECURE");
		final String label;
		Phase(String label) { this.label = label; }
		boolean wave() { return this == CONTACT || this == ASSAULT || this == SIEGE; }
	}

	record Snapshot(boolean active, String summary, String fronts) {
		static Snapshot inactive() { return new Snapshot(false, "", ""); }
	}

	private record Session(UUID ownerId, String dimension, BlockPos center, Phase phase, int waveIndex,
		long phaseStarted, int hostiles, String fronts, long phaseTicks) {
		Session(UUID ownerId, String dimension, BlockPos center, Phase phase, int waveIndex,
			long phaseStarted, int hostiles, String fronts) {
			this(ownerId, dimension, center, phase, waveIndex, phaseStarted, hostiles, fronts, 0L);
		}
		Session transition(Phase next, long tick, int hostiles, String fronts) {
			int nextWave = next == Phase.ASSAULT ? 1 : next == Phase.SIEGE ? 2 : waveIndex;
			return new Session(ownerId, dimension, center, next, nextWave, tick, hostiles, fronts, 0L);
		}
		Session withStatus(long phaseTicks, int hostiles, String fronts) {
			return new Session(ownerId, dimension, center, phase, waveIndex, phaseStarted,
				hostiles, fronts, phaseTicks);
		}
	}
}
