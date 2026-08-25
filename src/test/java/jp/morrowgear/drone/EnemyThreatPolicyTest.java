package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class EnemyThreatPolicyTest {
	@Test void highHealthHighDamageModdedEnemyOutranksVanillaSizedEnemy() {
		int ordinary = EnemyThreatPolicy.assess(new EnemyThreatPolicy.Factors(20, 4, 2, 0.23, 0)).score();
		int powerful = EnemyThreatPolicy.assess(new EnemyThreatPolicy.Factors(420, 28, 18, 0.34, 0)).score();
		assertTrue(ordinary < 20);
		assertTrue(powerful >= 80);
	}

	@Test void playerDangerIsSeparateFromIntrinsicEnemyPower() {
		int distant = EnemyThreatPolicy.playerDanger(80, 23, false, false, false);
		int attacking = EnemyThreatPolicy.playerDanger(80, 4, true, true, false);
		assertTrue(attacking > distant + 35);
	}
}
