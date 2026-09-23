package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class CombatContactContinuityTest {
	@AfterEach void clear() { FleetThreatNetwork.clear(); }

	@ParameterizedTest
	@EnumSource(CombatState.class)
	void onlyAnActiveEngagementRefreshesItsAssignedContact(CombatState state) {
		assertEquals(state.active(), CombatPolicy.refreshCommittedContact(state, 52 * 52 + 64 * 64, true));
		assertFalse(CombatPolicy.refreshCommittedContact(state, 52 * 52 + 64 * 64, false));
	}

	@ParameterizedTest
	@ValueSource(doubles = {0, 144, 2704, 16384})
	void visibleTrackIncludesFullThreeDimensionalRange(double distanceSquared) {
		assertTrue(CombatPolicy.refreshCommittedContact(CombatState.LASER_FIRE, distanceSquared, true));
	}

	@ParameterizedTest
	@ValueSource(doubles = {-1, 16384.01, 65536, Double.NaN, Double.POSITIVE_INFINITY})
	void invalidOrDistantTrackDoesNotCreateOmniscientReports(double distanceSquared) {
		assertFalse(CombatPolicy.refreshCommittedContact(CombatState.LASER_FIRE, distanceSquared, true));
	}

	@Test void nullStateCannotPublish() {
		assertFalse(CombatPolicy.refreshCommittedContact(null, 1, true));
	}

	@ParameterizedTest
	@EnumSource(CombatState.class)
	void onlyInitialEntryMayAwaitAPlanComputedBeforeEngagement(CombatState state) {
		assertEquals(state == CombatState.FLARE_ENTRY,
			CombatTheaterPolicy.awaitingInitialPlan(state, 100, 98, 108));
	}

	@Test void initialGraceIsBoundedAndDoesNotIgnoreANewPlan() {
		assertTrue(CombatTheaterPolicy.awaitingInitialPlan(CombatState.FLARE_ENTRY, 100, 100, 109));
		assertFalse(CombatTheaterPolicy.awaitingInitialPlan(CombatState.FLARE_ENTRY, 100, 98, 110));
		assertFalse(CombatTheaterPolicy.awaitingInitialPlan(CombatState.FLARE_ENTRY, 100, 101, 108));
		assertFalse(CombatTheaterPolicy.awaitingInitialPlan(CombatState.FLARE_ENTRY, 100, 98, 99));
	}

	@Test void elevatedEightAircraftTrackOutlivesTheOriginalScoutReportWithoutLosingSlots() {
		UUID owner = UUID.randomUUID();
		Vec3 target = new Vec3(0, 64, 0);
		FleetThreatNetwork.publish("overworld", owner, "SCOUT",
			new FleetThreatNetwork.Report(40, 40, 0, target, 7, "SCOUT-WING", "ROUTE", 0));
		List<CombatTheaterPolicy.Unit> units = IntStream.range(0, 8).mapToObj(i ->
			new CombatTheaterPolicy.Unit("SEC-" + i, "WING-" + i / 4, false, true,
				false, false, true, 7, i, 90, 90, 0, false, new Vec3(52, 128, 0))).toList();
		for (int tick = 10; tick <= 800; tick++) {
			if (tick % 10 == 0) {
				Vec3 aircraft = target.add(Math.cos(tick * .02) * 52, 64, Math.sin(tick * .02) * 52);
				assertTrue(CombatPolicy.refreshCommittedContact(CombatState.GUN_RUN, aircraft.distanceToSqr(target), true));
				FleetThreatNetwork.publish("overworld", owner, "SEC-0",
					new FleetThreatNetwork.Report(40, 40, 0, target, 7, "WING-0", "ROUTE", tick));
			}
			List<FleetThreatNetwork.Snapshot> reports = FleetThreatNetwork.readAll("overworld", owner, tick);
			assertEquals(1, reports.size());
			List<CombatTheaterPolicy.Contact> contacts = reports.stream().map(r ->
				new CombatTheaterPolicy.Contact(r.entityId(), r.score(), r.enemyThreat(), r.playerDanger(),
					1024, 0, r.position(), Vec3.ZERO, 160, r.sourceWing(), r.sourceMission())).toList();
			var plan = CombatTheaterPolicy.allocate(contacts, units);
			assertEquals(8, plan.assignments().size(), "tick=" + tick);
			for (int i = 0; i < 8; i++) {
				assertEquals(7, plan.assignments().get("SEC-" + i).targetId());
				assertEquals(i, plan.assignments().get("SEC-" + i).slot());
			}
		}
		// No refreshed observations after occlusion, loss, or return: normal TTL still applies.
		assertTrue(FleetThreatNetwork.readAll("overworld", owner, 861).isEmpty());
		assertTrue(CombatTheaterPolicy.allocate(List.of(), units).assignments().isEmpty());
		assertTrue(FleetThreatNetwork.readAll("nether", owner, 800).isEmpty());
		assertTrue(FleetThreatNetwork.readAll("overworld", UUID.randomUUID(), 800).isEmpty());
	}
}
