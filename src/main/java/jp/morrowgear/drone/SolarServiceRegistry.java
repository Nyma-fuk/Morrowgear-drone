package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class SolarServiceRegistry {
	static final long STATION_EXPIRY_TICKS = 120L;
	static final long RESERVATION_EXPIRY_TICKS = 80L;
	private static final Map<UUID, Station> STATIONS = new HashMap<>();
	private static final Map<UUID, Reservation> RESERVATIONS = new HashMap<>();
	private static final Map<UUID, WaitRequest> WAITING = new HashMap<>();

	private SolarServiceRegistry() {
	}

	static synchronized void heartbeat(UUID stationId, UUID ownerId, String dimension,
		double x, double y, double z, int energyPercent, long tick) {
		if (stationId == null || ownerId == null || dimension == null) return;
		STATIONS.put(stationId, new Station(stationId, ownerId, dimension, x, y, z,
			Math.max(0, Math.min(100, energyPercent)), tick));
		prune(tick);
	}

	static synchronized List<SolarServicePolicy.Candidate> candidates(UUID ownerId, String dimension,
		double x, double y, double z, double missionX, double missionY, double missionZ, long tick) {
		prune(tick);
		List<SolarServicePolicy.Candidate> result = new ArrayList<>();
		for (Station station : STATIONS.values()) {
			if (!station.ownerId().equals(ownerId)) continue;
			boolean sameDimension = station.dimension().equals(dimension);
			double distance = distance(x, y, z, station.x(), station.y(), station.z());
			double directMission = distance(x, y, z, missionX, missionY, missionZ);
			double viaStation = distance
				+ distance(station.x(), station.y(), station.z(), missionX, missionY, missionZ);
			result.add(new SolarServicePolicy.Candidate(station.id(), distance,
				Math.max(0.0, viaStation - directMission), station.energyPercent(),
				occupiedSlots(station.id()), sameDimension));
		}
		result.sort(Comparator.comparingDouble(SolarServicePolicy.Candidate::distance));
		return List.copyOf(result);
	}

	static synchronized int reserve(UUID stationId, UUID aircraftId, long tick) {
		return request(stationId, aircraftId, 50, tick);
	}

	static synchronized int request(UUID stationId, UUID aircraftId, int priority, long tick) {
		prune(tick);
		Station station = STATIONS.get(stationId);
		if (station == null || station.energyPercent() < SolarServicePolicy.MINIMUM_STATION_ENERGY) return -1;
		Reservation existing = RESERVATIONS.get(aircraftId);
		if (existing != null && existing.stationId().equals(stationId)) {
			RESERVATIONS.put(aircraftId, new Reservation(stationId, existing.slot(), tick));
			return existing.slot();
		}
		WAITING.put(aircraftId, new WaitRequest(stationId, Math.max(0, priority), tick));
		promoteWaiting(stationId, tick);
		Reservation promoted = RESERVATIONS.get(aircraftId);
		if (promoted != null && promoted.stationId().equals(stationId)) return promoted.slot();
		return -1;
	}

	private static void promoteWaiting(UUID stationId, long tick) {
		boolean[] occupied = new boolean[SolarServicePolicy.MAX_SIMULTANEOUS_CHARGERS];
		for (Reservation reservation : RESERVATIONS.values()) {
			if (reservation.stationId().equals(stationId) && reservation.slot() >= 0
				&& reservation.slot() < occupied.length) occupied[reservation.slot()] = true;
		}
		List<Map.Entry<UUID, WaitRequest>> queue = WAITING.entrySet().stream()
			.filter(entry -> entry.getValue().stationId().equals(stationId))
			.sorted(Comparator.comparingInt((Map.Entry<UUID, WaitRequest> entry) -> entry.getValue().priority())
				.thenComparingLong(entry -> entry.getValue().renewedTick())
				.thenComparing(entry -> entry.getKey().toString())).toList();
		int cursor = 0;
		for (int slot = 0; slot < occupied.length && cursor < queue.size(); slot++) {
			if (occupied[slot]) continue;
			Map.Entry<UUID, WaitRequest> next = queue.get(cursor++);
			RESERVATIONS.put(next.getKey(), new Reservation(stationId, slot, tick));
			WAITING.remove(next.getKey());
		}
	}

	static synchronized void release(UUID aircraftId) {
		RESERVATIONS.remove(aircraftId);
		WAITING.remove(aircraftId);
	}

	static synchronized int reservedSlot(UUID stationId, UUID aircraftId, long tick) {
		prune(tick);
		Reservation reservation = RESERVATIONS.get(aircraftId);
		return reservation != null && reservation.stationId().equals(stationId)
			? reservation.slot() : -1;
	}

	static synchronized void removeStation(UUID stationId) {
		STATIONS.remove(stationId);
		RESERVATIONS.entrySet().removeIf(entry -> entry.getValue().stationId().equals(stationId));
		WAITING.entrySet().removeIf(entry -> entry.getValue().stationId().equals(stationId));
	}

	static synchronized int occupiedSlots(UUID stationId) {
		return (int)RESERVATIONS.values().stream()
			.filter(reservation -> reservation.stationId().equals(stationId)).count();
	}

	static synchronized int waitingCount(UUID stationId, long tick) {
		prune(tick);
		return (int)WAITING.values().stream().filter(wait -> wait.stationId().equals(stationId)).count();
	}

	static synchronized int waitingPosition(UUID stationId, UUID aircraftId, long tick) {
		prune(tick);
		List<UUID> queue = WAITING.entrySet().stream()
			.filter(entry -> entry.getValue().stationId().equals(stationId))
			.sorted(Comparator.comparingInt((Map.Entry<UUID, WaitRequest> entry) -> entry.getValue().priority())
				.thenComparingLong(entry -> entry.getValue().renewedTick())
				.thenComparing(entry -> entry.getKey().toString()))
			.map(Map.Entry::getKey).toList();
		int index = queue.indexOf(aircraftId);
		return index < 0 ? -1 : index + 1;
	}

	static synchronized void clearForTests() {
		STATIONS.clear();
		RESERVATIONS.clear();
		WAITING.clear();
	}

	private static void prune(long tick) {
		STATIONS.entrySet().removeIf(entry -> expired(entry.getValue().heartbeatTick(), tick,
			STATION_EXPIRY_TICKS));
		RESERVATIONS.entrySet().removeIf(entry -> expired(entry.getValue().renewedTick(), tick,
			RESERVATION_EXPIRY_TICKS) || !STATIONS.containsKey(entry.getValue().stationId()));
		WAITING.entrySet().removeIf(entry -> expired(entry.getValue().renewedTick(), tick,
			RESERVATION_EXPIRY_TICKS) || !STATIONS.containsKey(entry.getValue().stationId()));
	}

	private static boolean expired(long heartbeat, long tick, long lifetime) {
		return tick >= heartbeat && tick - heartbeat > lifetime;
	}

	private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
		double dx = ax - bx;
		double dy = ay - by;
		double dz = az - bz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private record Station(UUID id, UUID ownerId, String dimension, double x, double y, double z,
		int energyPercent, long heartbeatTick) {}

	private record Reservation(UUID stationId, int slot, long renewedTick) {}
	private record WaitRequest(UUID stationId, int priority, long renewedTick) {}
}
