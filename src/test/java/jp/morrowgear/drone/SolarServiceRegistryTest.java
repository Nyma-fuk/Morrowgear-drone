package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class SolarServiceRegistryTest {
	private final UUID owner = UUID.randomUUID();
	private final UUID station = UUID.randomUUID();

	@AfterEach
	void clear() {
		SolarServiceRegistry.clearForTests();
	}

	@Test
	void stationOffersExactlyThreeStableReservationSlots() {
		SolarServiceRegistry.heartbeat(station, owner, "overworld", 40, 80, 0, 100, 10);
		UUID first = UUID.randomUUID();
		assertEquals(0, SolarServiceRegistry.reserve(station, first, 10));
		assertEquals(0, SolarServiceRegistry.reserve(station, first, 20));
		assertEquals(0, SolarServiceRegistry.reservedSlot(station, first, 20));
		assertEquals(1, SolarServiceRegistry.reserve(station, UUID.randomUUID(), 20));
		assertEquals(2, SolarServiceRegistry.reserve(station, UUID.randomUUID(), 20));
		assertEquals(-1, SolarServiceRegistry.reserve(station, UUID.randomUUID(), 20));
	}

	@Test
	void expiredAircraftAndDestroyedStationReleaseCapacity() {
		SolarServiceRegistry.heartbeat(station, owner, "overworld", 40, 80, 0, 100, 0);
		UUID aircraft = UUID.randomUUID();
		SolarServiceRegistry.reserve(station, aircraft, 0);
		SolarServiceRegistry.heartbeat(station, owner, "overworld", 40, 80, 0, 100,
			SolarServiceRegistry.RESERVATION_EXPIRY_TICKS + 1);
		assertEquals(0, SolarServiceRegistry.occupiedSlots(station));
		SolarServiceRegistry.reserve(station, aircraft, SolarServiceRegistry.RESERVATION_EXPIRY_TICKS + 1);
		SolarServiceRegistry.removeStation(station);
		assertEquals(0, SolarServiceRegistry.occupiedSlots(station));
	}

	@Test
	void candidatesRespectOwnershipDimensionEnergyAndLiveOccupancy() {
		SolarServiceRegistry.heartbeat(station, owner, "overworld", 40, 80, 0, 72, 10);
		SolarServiceRegistry.reserve(station, UUID.randomUUID(), 10);
		List<SolarServicePolicy.Candidate> candidates = SolarServiceRegistry.candidates(owner,
			"overworld", 0, 80, 0, 100, 80, 0, 10);
		assertEquals(1, candidates.size());
		SolarServicePolicy.Candidate candidate = candidates.getFirst();
		assertEquals(1, candidate.occupiedSlots());
		assertEquals(40.0, candidate.distance(), 0.001);
		assertTrue(candidate.sameDimension());
	}

	@Test
	void fullStationQueuesByPowerEmergencyAndPromotesWhenABayReleases() {
		SolarServiceRegistry.heartbeat(station, owner, "overworld", 40, 80, 0, 100, 10);
		UUID first = UUID.randomUUID();
		UUID second = UUID.randomUUID();
		UUID third = UUID.randomUUID();
		UUID normal = UUID.randomUUID();
		UUID emergency = UUID.randomUUID();
		assertEquals(0, SolarServiceRegistry.request(station, first, 60, 10));
		assertEquals(1, SolarServiceRegistry.request(station, second, 60, 10));
		assertEquals(2, SolarServiceRegistry.request(station, third, 60, 10));
		assertEquals(-1, SolarServiceRegistry.request(station, normal, 45, 10));
		assertEquals(-1, SolarServiceRegistry.request(station, emergency, 8, 11));
		assertEquals(1, SolarServiceRegistry.waitingPosition(station, emergency, 11));
		assertEquals(2, SolarServiceRegistry.waitingCount(station, 11));
		SolarServiceRegistry.release(first);
		assertEquals(0, SolarServiceRegistry.request(station, emergency, 8, 12));
		assertEquals(1, SolarServiceRegistry.waitingCount(station, 12));
	}
}
