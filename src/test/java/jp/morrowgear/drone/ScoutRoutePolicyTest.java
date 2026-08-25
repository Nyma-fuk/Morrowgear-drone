package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import net.minecraft.world.phys.Vec3;

class ScoutRoutePolicyTest {
	@Test
	void rejectsMissingLowConfidenceAndStaleIntel() {
		assertFalse(ScoutRoutePolicy.decide(MissionDataLink.Snapshot.empty(), 100).usable());
		assertFalse(ScoutRoutePolicy.decide(snapshot(MissionDataLink.RouteStatus.CLEAR, 0.44, 100), 100).usable());
		assertFalse(ScoutRoutePolicy.decide(snapshot(MissionDataLink.RouteStatus.CLEAR, 0.8, 100), 201).usable());
	}

	@Test
	void hazardRaisesClearanceAndCapsOnlyTheLeaderCruiseSpeed() {
		ScoutRoutePolicy.Decision decision = ScoutRoutePolicy.decide(
			snapshot(MissionDataLink.RouteStatus.HAZARDOUS, 0.8, 100), 120);
		assertTrue(decision.usable());
		assertEquals(5.0, decision.clearanceBoost());
		assertEquals(0.66, decision.leaderSpeedCap());
	}

	@Test
	void clearRouteDoesNotConstrainNormalFlight() {
		ScoutRoutePolicy.Decision decision = ScoutRoutePolicy.decide(
			snapshot(MissionDataLink.RouteStatus.CLEAR, 0.8, 100), 120);
		assertEquals(0.0, decision.clearanceBoost());
		assertEquals(Double.POSITIVE_INFINITY, decision.leaderSpeedCap());
	}

	private static MissionDataLink.Snapshot snapshot(MissionDataLink.RouteStatus status,
		double confidence, long observedTick) {
		return new MissionDataLink.Snapshot(status, 0, Vec3.ZERO, false,
			confidence, observedTick, 1);
	}
}
