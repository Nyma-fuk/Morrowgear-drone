package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class CombatTheaterPolicyTest {
	@Test
	void fiftyHostilesUseMultipleTargetsWithoutBreakingEightUnitLimit() {
		List<CombatTheaterPolicy.Contact> contacts = IntStream.range(0, 50)
			.mapToObj(index -> contact(1000 + index, new Vec3(index % 10, 64, index / 10), 8, 0)).toList();
		List<CombatTheaterPolicy.Unit> units = IntStream.range(0, 24)
			.mapToObj(index -> unit("U" + index, "WING-" + index / 8, -1, false)).toList();

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(contacts, units);

		assertEquals(1, plan.clusters().size());
		assertEquals(50, plan.hostileCount());
		assertEquals(19, plan.assignments().size());
		assertEquals(5, plan.reserveCount());
		Map<Integer, Long> byTarget = plan.assignments().values().stream().collect(
			java.util.stream.Collectors.groupingBy(CombatTheaterPolicy.Assignment::targetId,
				java.util.stream.Collectors.counting()));
		assertTrue(byTarget.size() > 1);
		assertTrue(byTarget.values().stream().allMatch(count -> count <= 8));
	}

	@Test
	void separatedApproachesBecomeIndependentFronts() {
		List<CombatTheaterPolicy.Contact> contacts = List.of(
			contact(1, new Vec3(0, 64, 0), 12, 20),
			contact(2, new Vec3(4, 64, 2), 10, 8),
			contact(3, new Vec3(40, 64, 0), 18, 0),
			contact(4, new Vec3(0, 42, 0), 20, 0));
		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(contacts,
			IntStream.range(0, 12).mapToObj(index -> unit("U" + index, "WING-A", -1, false)).toList());

		assertEquals(3, plan.clusters().size());
		assertTrue(plan.assignments().values().stream().map(CombatTheaterPolicy.Assignment::clusterId)
			.distinct().count() >= 2);
	}

	@Test
	void committedAttackPassKeepsItsTargetDuringReallocation() {
		List<CombatTheaterPolicy.Contact> contacts = List.of(
			contact(1, Vec3.ZERO, 8, 0), contact(2, new Vec3(30, 64, 0), 80, 60));
		List<CombatTheaterPolicy.Unit> units = new ArrayList<>();
		units.add(unit("COMMITTED", "WING-A", 1, true));
		IntStream.range(0, 7).mapToObj(index -> unit("U" + index, "WING-B", -1, false)).forEach(units::add);

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(contacts, units);

		assertEquals(1, plan.assignments().get("COMMITTED").targetId());
		assertTrue(plan.assignments().values().stream().anyMatch(assignment -> assignment.targetId() == 2));
	}

	@Test
	void unavailableAircraftRemainOutsideTheOperation() {
		List<CombatTheaterPolicy.Unit> units = List.of(
			new CombatTheaterPolicy.Unit("EMPTY", "WING-A", true, false, false, false,
				false, -1, 0, 4, 100, 0, false, Vec3.ZERO),
			new CombatTheaterPolicy.Unit("SERVICE", "WING-A", true, false, true, true,
				false, -1, 0, 100, 100, 0, false, Vec3.ZERO),
			unit("READY", "WING-A", -1, false));
		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(
			List.of(contact(1, new Vec3(2, 64, 0), 20, 0)), units);

		assertEquals(List.of("READY"), plan.assignments().keySet().stream().sorted().toList());
	}

	@Test
	void bossDurabilityAndObservedAttritionCanRequestAllEightAircraft() {
		CombatTheaterPolicy.Contact boss = new CombatTheaterPolicy.Contact(99, 100, 82, 45,
			500.0f, 35, new Vec3(8, 64, 0), Vec3.ZERO, 8.0, "PLAYER-GUARD", "");
		List<CombatTheaterPolicy.Unit> units = IntStream.range(0, 20)
			.mapToObj(index -> unit("U" + index, "WING-" + index / 8, -1, false)).toList();

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(List.of(boss), units);

		assertEquals(8, plan.assignments().size());
		assertTrue(plan.assignments().values().stream().allMatch(assignment -> assignment.targetId() == 99));
	}

	@Test
	void scoutReportUsesIdleSecurityBeforeDetachingWaypointSecurity() {
		CombatTheaterPolicy.Contact scoutReport = new CombatTheaterPolicy.Contact(77, 34, 28, 0,
			40.0f, 0, new Vec3(18, 64, 0), Vec3.ZERO, 30.0, "WING-SCOUT", "ROUTE-ALPHA");
		List<CombatTheaterPolicy.Unit> units = List.of(
			new CombatTheaterPolicy.Unit("SEC-IDLE-1", "WING-RESERVE", true, false, false, false,
				false, -1, 0, 100, 100, 0, false, Vec3.ZERO),
			new CombatTheaterPolicy.Unit("SEC-IDLE-2", "WING-RESERVE", true, false, false, false,
				false, -1, 0, 100, 100, 0, false, Vec3.ZERO),
			new CombatTheaterPolicy.Unit("SEC-ROUTE", "WING-SCOUT", false, true, false, false,
				false, -1, 0, 100, 100, 0, false, new Vec3(8, 64, 0)));

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(List.of(scoutReport), units);

		assertTrue(plan.assignments().keySet().contains("SEC-IDLE-1"));
		assertTrue(plan.assignments().keySet().contains("SEC-IDLE-2"));
		assertTrue(!plan.assignments().containsKey("SEC-ROUTE"));
		assertTrue(plan.assignments().values().stream().allMatch(assignment -> assignment.targetId() == 77));
	}

	@Test
	void scoutReportCanTemporarilyUseWaypointSecurityWhenNoReserveExists() {
		CombatTheaterPolicy.Contact scoutReport = new CombatTheaterPolicy.Contact(88, 52, 46, 0,
			80.0f, 0, new Vec3(24, 64, 0), Vec3.ZERO, 36.0, "WING-SCOUT", "ROUTE-BRAVO");
		CombatTheaterPolicy.Unit missionSecurity = new CombatTheaterPolicy.Unit(
			"SEC-ROUTE", "WING-SCOUT", false, true, false, false,
			false, -1, 0, 100, 100, 0, false, new Vec3(10, 64, 0));

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(
			List.of(scoutReport), List.of(missionSecurity));

		assertEquals(88, plan.assignments().get("SEC-ROUTE").targetId());
		assertEquals("ROUTE-BRAVO", plan.assignments().get("SEC-ROUTE").contact().sourceMission());
	}

	@Test
	void emergencyFrontCanRecallAReadyAircraftFromDockService() {
		CombatTheaterPolicy.Contact warden = new CombatTheaterPolicy.Contact(99, 90, 82, 20,
			500.0f, 0, new Vec3(16, 64, 0), Vec3.ZERO, 16.0, "WING-SCOUT", "PATROL");
		List<CombatTheaterPolicy.Unit> units = List.of(
			new CombatTheaterPolicy.Unit("ENGAGED", "WING-A", false, true, false, false,
				true, 99, 0, 80, 80, 0, false, new Vec3(12, 64, 0)),
			new CombatTheaterPolicy.Unit("SERVICE-READY", "WING-B", true, false, true, true,
				false, -1, 0, 75, 89, 0, true, Vec3.ZERO));

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(List.of(warden), units);

		assertTrue(plan.assignments().containsKey("SERVICE-READY"));
		assertEquals(0, plan.reserveCount());
	}

	@Test
	void dockServiceAircraftStaysDockedWhenEmergencyReserveIsNotReady() {
		CombatTheaterPolicy.Contact warden = new CombatTheaterPolicy.Contact(99, 90, 82, 20,
			500.0f, 0, new Vec3(16, 64, 0), Vec3.ZERO, 16.0, "WING-SCOUT", "PATROL");
		CombatTheaterPolicy.Unit service = new CombatTheaterPolicy.Unit(
			"SERVICE-LOW", "WING-B", true, false, true, true,
			false, -1, 0, 40, 30, 0, false, Vec3.ZERO);

		CombatTheaterPolicy.Plan plan = CombatTheaterPolicy.allocate(List.of(warden), List.of(service));

		assertTrue(plan.assignments().isEmpty());
	}

	private static CombatTheaterPolicy.Contact contact(int id, Vec3 position, int threat, int danger) {
		return new CombatTheaterPolicy.Contact(id, threat + danger, threat, danger, 20.0f, 0, position,
			Vec3.ZERO, position.length(),
			danger > 0 ? "PLAYER-GUARD" : "WING-SCOUT", "PATROL");
	}

	private static CombatTheaterPolicy.Unit unit(String id, String wing, int target, boolean committed) {
		return new CombatTheaterPolicy.Unit(id, wing, true, false, false, false, committed,
			target, 0, 100, 100, 0, false, Vec3.ZERO);
	}
}
