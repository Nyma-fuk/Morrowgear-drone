package jp.morrowgear.drone;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

final class SolarServicePolicy {
	static final int MAX_SIMULTANEOUS_CHARGERS = 3;
	static final int TARGET_FLIGHT_POWER = 85;
	static final int EMERGENCY_FLIGHT_POWER = 10;
	static final int MINIMUM_STATION_ENERGY = 8;
	static final double BASE_DISTANCE_ADVANTAGE = 16.0;

	private SolarServicePolicy() {
	}

	static ServicePlan plan(DroneServicePolicy.Need need, int flightPower, boolean baseAvailable,
		double baseDistance, double remainingMissionDistance, List<Candidate> candidates) {
		return plan(need, flightPower, baseAvailable, baseDistance, remainingMissionDistance,
			DroneServicePolicy.flightReserveForDock(remainingMissionDistance), candidates);
	}

	static ServicePlan plan(DroneServicePolicy.Need need, int flightPower, boolean baseAvailable,
		double baseDistance, double remainingMissionDistance, int missionDiversionThreshold,
		List<Candidate> candidates) {
		DroneServicePolicy.Need reason = need == null ? DroneServicePolicy.Need.NONE : need;
		if (reason != DroneServicePolicy.Need.FLIGHT_POWER) {
			return new ServicePlan(baseAvailable && reason != DroneServicePolicy.Need.NONE
				? Destination.BASE_DOCK : Destination.NONE, null);
		}
		Optional<Candidate> solar = selectReachable(flightPower, remainingMissionDistance, candidates);
		if (solar.isEmpty()) return new ServicePlan(baseAvailable ? Destination.BASE_DOCK : Destination.NONE, null);
		Candidate station = solar.get();
		boolean baseUnreachable = !baseAvailable || flightPower <= minimumArrivalPower(baseDistance);
		boolean solarProtectsMission = remainingMissionDistance > 0
			&& flightPower <= Math.max(DroneServicePolicy.flightReserveForDock(remainingMissionDistance),
				missionDiversionThreshold);
		boolean materiallyCloser = !baseAvailable || station.distance() + BASE_DISTANCE_ADVANTAGE < baseDistance;
		if (flightPower <= EMERGENCY_FLIGHT_POWER || baseUnreachable || solarProtectsMission || materiallyCloser) {
			return new ServicePlan(Destination.SOLAR_DOCK, station.id());
		}
		return new ServicePlan(Destination.BASE_DOCK, null);
	}

	static Optional<Candidate> selectReachable(int flightPower, double remainingMissionDistance,
		List<Candidate> candidates) {
		if (candidates == null) return Optional.empty();
		return candidates.stream().filter(Candidate::available)
			.filter(candidate -> flightPower > minimumArrivalPower(candidate.distance()))
			.min(Comparator.comparingDouble(candidate -> candidate.score(remainingMissionDistance)));
	}

	static Optional<Candidate> selectQueueCandidate(int flightPower, double baseDistance,
		double remainingMissionDistance, List<Candidate> candidates) {
		if (candidates == null || flightPower > minimumArrivalPower(baseDistance)) return Optional.empty();
		return candidates.stream().filter(candidate -> candidate.id() != null && candidate.sameDimension()
			&& candidate.energyPercent() >= MINIMUM_STATION_ENERGY
			&& flightPower > minimumArrivalPower(candidate.distance()))
			.min(Comparator.comparingDouble(candidate -> candidate.score(remainingMissionDistance)));
	}

	static int minimumArrivalPower(double distance) {
		return Math.min(45, 5 + (int)Math.ceil(Math.max(0.0, distance) / 32.0));
	}

	static boolean chargeComplete(int flightPower) {
		return chargeComplete(flightPower, TARGET_FLIGHT_POWER);
	}

	static boolean chargeComplete(int flightPower, int targetFlightPower) {
		return flightPower >= targetFlightPower;
	}

	enum Destination { NONE, BASE_DOCK, SOLAR_DOCK }

	record ServicePlan(Destination destination, UUID solarDockId) {}

	record Candidate(UUID id, double distance, double routeDetour, int energyPercent,
		int occupiedSlots, boolean sameDimension) {
		boolean available() {
			return id != null && sameDimension && energyPercent >= MINIMUM_STATION_ENERGY
				&& occupiedSlots < MAX_SIMULTANEOUS_CHARGERS;
		}

		double score(double remainingMissionDistance) {
			double missionPenalty = remainingMissionDistance <= 0 ? 0 : Math.max(0, routeDetour);
			return Math.max(0, distance) + missionPenalty + occupiedSlots * 12.0 - energyPercent * 0.08;
		}
	}
}
