package jp.morrowgear.drone;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class FleetThreatNetwork {
	static final long REPORT_TTL_TICKS = ThreatSensorPolicy.REPORT_TTL_TICKS;
	private static final Map<Key, Map<Integer, Report>> REPORTS = new HashMap<>();
	private static final Map<Key, Long> LAST_PRUNE = new HashMap<>();

	private FleetThreatNetwork() {}

	static void publish(String dimension, UUID owner, String sourceId, Report report) {
		if (sourceId == null || sourceId.isBlank()) return;
		Key key = new Key(dimension, owner);
		Report normalized = report.normalized();
		REPORTS.computeIfAbsent(key, ignored -> new HashMap<>()).merge(normalized.entityId(), normalized,
			(left, right) -> compareReports(left, right) >= 0 ? left : right);
		prune(key, report.tick());
	}

	static Snapshot read(String dimension, UUID owner, long now) {
		return readAll(dimension, owner, now).stream().findFirst().orElseGet(Snapshot::empty);
	}

	static List<Snapshot> readAll(String dimension, UUID owner, long now) {
		Key key = new Key(dimension, owner);
		prune(key, now);
		Map<Integer, Report> reports = REPORTS.get(key);
		if (reports == null) return List.of();
		return reports.values().stream()
			.filter(report -> now - report.tick() <= REPORT_TTL_TICKS
				&& report.score() > 0 && report.entityId() >= 0)
			.sorted((a, b) -> -compareReports(a, b))
			.map(report -> new Snapshot(report.score(), report.enemyThreat(), report.playerDanger(),
				report.position(), report.entityId(), report.sourceWing(), report.sourceMission(), report.tick()))
			.toList();
	}

	static boolean knownContact(String dimension, UUID owner, int entityId, long now) {
		return readAll(dimension, owner, now).stream().anyMatch(report -> report.entityId() == entityId);
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
		LAST_PRUNE.clear();
		CombatTheaterCoordinator.clear();
	}

	private static void prune(Key key, long now) {
		Long previous = LAST_PRUNE.get(key);
		if (previous != null && now >= previous
			&& now - previous < ThreatSensorPolicy.NETWORK_MAINTENANCE_INTERVAL_TICKS) return;
		Map<Integer, Report> reports = REPORTS.get(key);
		if (reports != null) {
			reports.values().removeIf(report -> now - report.tick() > REPORT_TTL_TICKS);
			if (reports.isEmpty()) REPORTS.remove(key);
		}
		LAST_PRUNE.put(key, now);
	}

	static int reportCountForTest(String dimension, UUID owner) {
		Map<Integer, Report> reports = REPORTS.get(new Key(dimension, owner));
		return reports == null ? 0 : reports.size();
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
