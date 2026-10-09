package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class ServerPerformanceComplexityTest {
	@Test
	void invasionScaleAllocationStaysInsideDeskBudget() {
		List<CombatTheaterPolicy.Contact> contacts = IntStream.range(0, 200).mapToObj(index ->
			new CombatTheaterPolicy.Contact(10_000 + index, 30, 20, index % 17 == 0 ? 24 : 0,
				40.0f, 0, new Vec3((index % 20) * 5, 64, (index / 20) * 5), Vec3.ZERO,
				40.0, "SCOUT-" + index % 4, "INVASION")).toList();
		List<CombatTheaterPolicy.Unit> units = IntStream.range(0, 128).mapToObj(index ->
			new CombatTheaterPolicy.Unit("UNIT-" + index, "WING-" + index / 8, true, false,
				false, false, false, -1, 0, 100, 100, 0, false,
				new Vec3(index % 16, 70, index / 16))).toList();

		CombatTheaterPolicy.Plan plan = assertTimeout(Duration.ofSeconds(2),
			() -> CombatTheaterPolicy.allocate(contacts, units));
		assertEquals(200, plan.hostileCount());
		assertEquals(128, plan.assignments().size());
		assertTrue(plan.assignments().values().stream().collect(java.util.stream.Collectors.groupingBy(
			CombatTheaterPolicy.Assignment::targetId, java.util.stream.Collectors.counting()))
			.values().stream().allMatch(count -> count <= CombatTheaterPolicy.MAX_UNITS_PER_TARGET));
	}

	@Test
	void sharedBudgetsReplaceMultiplicativeWorstCases() {
		int drones = 64, enemies = 200, docks = 16, scouts = 16, missiles = 45;
		long oldThreatReports = (long)drones * enemies;
		long mergedThreatContacts = enemies;
		long oldDockCompatibilityChecks = (long)drones * docks * drones;
		long sharedDockCompatibilityChecks = (long)drones * docks;
		long oldFieldSamples = (long)scouts * 384;

		assertEquals(12_800, oldThreatReports);
		assertEquals(200, mergedThreatContacts);
		assertEquals(65_536, oldDockCompatibilityChecks);
		assertEquals(1_024, sharedDockCompatibilityChecks);
		assertEquals(6_144, oldFieldSamples);
		assertEquals(512, FieldOperationRegistry.MAX_SHARED_SCAN_SAMPLES_PER_TICK);
		assertEquals(8, Math.min(missiles, MissileDetonationRuntime.MAX_DETONATIONS_PER_LEVEL_TICK));
		assertTrue(NavigationWorkPolicy.MAX_STRATEGIC_PROBES
			* NavigationWorkPolicy.MAX_CORRIDOR_SAMPLES * 2L <= 21_000);
	}
}
