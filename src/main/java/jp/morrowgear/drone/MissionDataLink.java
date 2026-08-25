package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

final class MissionDataLink {
	static final long REPORT_TTL_TICKS = 200L;
	private static final Map<Key, Map<String, ScoutReport>> REPORTS = new HashMap<>();

	private MissionDataLink() {
	}

	static void publish(String dimension, UUID owner, String missionId, String sourceId, ScoutReport report) {
		if (missionId == null || missionId.isBlank() || sourceId == null || sourceId.isBlank()) return;
		Key key = new Key(dimension, owner, missionId);
		REPORTS.computeIfAbsent(key, ignored -> new HashMap<>()).put(sourceId, report.normalized());
		prune(report.observedTick());
	}

	static Snapshot read(String dimension, UUID owner, String missionId, long now) {
		if (missionId == null || missionId.isBlank()) return Snapshot.empty();
		Map<String, ScoutReport> missionReports = REPORTS.get(new Key(dimension, owner, missionId));
		if (missionReports == null) return Snapshot.empty();
		List<ScoutReport> active = missionReports.values().stream()
			.filter(report -> now - report.observedTick() <= REPORT_TTL_TICKS).toList();
		if (active.isEmpty()) return Snapshot.empty();

		RouteStatus routeStatus = active.stream().map(ScoutReport::routeStatus)
			.max((left, right) -> Integer.compare(left.priority(), right.priority())).orElse(RouteStatus.UNKNOWN);
		ScoutReport strongestThreat = active.stream().max((left, right) ->
			Integer.compare(left.threatScore(), right.threatScore())).orElse(active.getFirst());
		double confidence = active.stream().mapToDouble(ScoutReport::confidence).average().orElse(0.0);
		boolean destinationReady = active.stream().anyMatch(ScoutReport::destinationReady);
		long newestObservation = active.stream().mapToLong(ScoutReport::observedTick).max().orElse(now);
		return new Snapshot(routeStatus, strongestThreat.threatScore(), strongestThreat.threatPosition(),
			destinationReady, confidence, newestObservation, active.size());
	}

	static void clear() {
		REPORTS.clear();
	}

	private static void prune(long now) {
		List<Key> emptyKeys = new ArrayList<>();
		for (Map.Entry<Key, Map<String, ScoutReport>> entry : REPORTS.entrySet()) {
			entry.getValue().values().removeIf(report -> now - report.observedTick() > REPORT_TTL_TICKS);
			if (entry.getValue().isEmpty()) emptyKeys.add(entry.getKey());
		}
		emptyKeys.forEach(REPORTS::remove);
	}

	enum RouteStatus {
		UNKNOWN(0), PROBING(1), CLEAR(2), HAZARDOUS(3), BLOCKED(4);

		private final int priority;

		RouteStatus(int priority) {
			this.priority = priority;
		}

		int priority() {
			return priority;
		}
	}

	record ScoutReport(RouteStatus routeStatus, Vec3 routeAnchor, int threatScore,
		Vec3 threatPosition, boolean destinationReady, double confidence, long observedTick,
		String sourceWing) {
		ScoutReport normalized() {
			return new ScoutReport(routeStatus == null ? RouteStatus.UNKNOWN : routeStatus,
				routeAnchor == null ? Vec3.ZERO : routeAnchor, Math.max(0, threatScore),
				threatPosition == null ? Vec3.ZERO : threatPosition, destinationReady,
				Mth.clamp(confidence, 0.0, 1.0), observedTick,
				sourceWing == null ? "" : sourceWing);
		}
	}

	record Snapshot(RouteStatus routeStatus, int threatScore, Vec3 threatPosition,
		boolean destinationReady, double confidence, long newestObservation, int sourceCount) {
		static Snapshot empty() {
			return new Snapshot(RouteStatus.UNKNOWN, 0, Vec3.ZERO, false, 0.0, -1L, 0);
		}

		boolean available() {
			return sourceCount > 0;
		}
	}

	private record Key(String dimension, UUID owner, String missionId) {
	}
}
