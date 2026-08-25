package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class FleetThreatNetwork {
	static final long REPORT_TTL_TICKS = 60L;
	private static final Map<Key, Map<String, Report>> REPORTS = new HashMap<>();

	private FleetThreatNetwork() {}

	static void publish(String dimension, UUID owner, String sourceId, Report report) {
		if (sourceId == null || sourceId.isBlank()) return;
		Key key = new Key(dimension, owner);
		String contactKey = sourceId + '#' + report.entityId();
		REPORTS.computeIfAbsent(key, ignored -> new HashMap<>()).put(contactKey, report.normalized());
		prune(report.tick());
	}

	static Snapshot read(String dimension, UUID owner, long now) {
		return readAll(dimension, owner, now).stream().findFirst().orElseGet(Snapshot::empty);
	}

	static List<Snapshot> readAll(String dimension, UUID owner, long now) {
		Map<String, Report> reports = REPORTS.get(new Key(dimension, owner));
		if (reports == null) return List.of();
		Map<Integer, Report> strongestByEntity = new HashMap<>();
		reports.values().stream()
			.filter(report -> now - report.tick() <= REPORT_TTL_TICKS
				&& report.score() > 0 && report.entityId() >= 0)
			.forEach(report -> strongestByEntity.merge(report.entityId(), report,
				(left, right) -> compareReports(left, right) >= 0 ? left : right));
		return strongestByEntity.values().stream()
			.sorted((a, b) -> -compareReports(a, b))
			.map(report -> new Snapshot(report.score(), report.enemyThreat(), report.playerDanger(),
				report.position(), report.entityId(), report.sourceWing(), report.sourceMission(), report.tick()))
			.toList();
	}

	private static int compareReports(Report a, Report b) {
				int priority = Integer.compare(triggerPriority(a), triggerPriority(b));
				if (priority != 0) return priority;
				int danger = Integer.compare(a.playerDanger(), b.playerDanger());
				if (danger != 0) return danger;
				int score = Integer.compare(a.score(), b.score());
				if (score != 0) return score;
				int freshness = Long.compare(a.tick(), b.tick());
				return freshness != 0 ? freshness : Integer.compare(b.entityId(), a.entityId());
	}

	static int score(int enemyCount, double nearestDistance) {
		if (enemyCount <= 0) return 0;
		int proximity = (int) Math.ceil(Math.max(0.0, 28.0 - nearestDistance) / 4.0);
		return Math.min(40, enemyCount * 5 + proximity);
	}

	private static int triggerPriority(Report report) {
		return "PLAYER-GUARD".equals(report.sourceWing()) ? 2 : 1;
	}

	static void clear() {
		REPORTS.clear();
		CombatTheaterCoordinator.clear();
	}

	private static void prune(long now) {
		REPORTS.values().forEach(values -> values.values().removeIf(report -> now - report.tick() > REPORT_TTL_TICKS));
		REPORTS.entrySet().removeIf(entry -> entry.getValue().isEmpty());
	}

	record Report(int score, int enemyThreat, int playerDanger, Vec3 position, int entityId,
		String sourceWing, String sourceMission, long tick) {
		Report(int score, Vec3 position, int entityId, String sourceWing, String sourceMission, long tick) {
			this(score, score, 0, position, entityId, sourceWing, sourceMission, tick);
		}

		Report normalized() {
			return new Report(Mth.clamp(score, 0, 100), Mth.clamp(enemyThreat, 0, 100),
				Mth.clamp(playerDanger, 0, 100), position == null ? Vec3.ZERO : position, entityId,
				sourceWing == null ? "" : sourceWing,
				sourceMission == null ? "" : sourceMission, tick);
		}
	}

	record Snapshot(int score, int enemyThreat, int playerDanger, Vec3 position, int entityId,
		String sourceWing, String sourceMission, long tick) {
		static Snapshot empty() { return new Snapshot(0, 0, 0, Vec3.ZERO, -1, "", "", -1L); }
		boolean active() { return score > 0; }
	}

	private record Key(String dimension, UUID owner) {}
}
