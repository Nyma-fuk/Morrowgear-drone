package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class DroneResourceUiPolicyTest {
	@Test void autoLoadoutNamesEveryResourceWithoutInventingASortieDecision() {
		var view = DroneResourceUiPolicy.assess(true, SecurityLoadout.AUTO,
			100, 100, 0, 240, 0, 45, 950, 100, 100, true);
		assertEquals(java.util.List.of("FLT", "LZR", "GUN", "MSL", "HEAT", "STR", "SYS"),
			view.metrics().stream().map(DroneResourceUiPolicy.Metric::code).toList());
		assertTrue(view.readiness().contains("機関砲弾"));
		assertTrue(view.readiness().contains("ミサイル"));
		assertTrue(view.readiness().contains("レーザー熱"));
		assertEquals(DroneResourceUiPolicy.Tone.CAUTION, view.tone());
		assertTrue(view.readiness().startsWith("補給中"));
		org.junit.jupiter.api.Assertions.assertFalse(view.readiness().contains("BLOCKED"));
	}

	@Test void roleSpecificLoadoutHidesIrrelevantAmmunition() {
		var view = DroneResourceUiPolicy.assess(true, SecurityLoadout.LASER,
			100, 100, 0, 240, 0, 45, 0, 100, 100, false);
		assertEquals(java.util.List.of("FLT", "LZR", "HEAT", "STR", "SYS"),
			view.metrics().stream().map(DroneResourceUiPolicy.Metric::code).toList());
		assertEquals("資源正常", view.readiness());
	}

	@Test void heatDoesNotClaimDockReturnOrBlockOtherWeapons() {
		var view = DroneResourceUiPolicy.assess(true, SecurityLoadout.AUTO,
			100, 100, 240, 240, 45, 45, 1000, 100, 100, false);
		assertEquals("資源注意: レーザー熱", view.readiness());
		assertEquals(DroneResourceUiPolicy.Tone.CAUTION, view.tone());
	}
}
