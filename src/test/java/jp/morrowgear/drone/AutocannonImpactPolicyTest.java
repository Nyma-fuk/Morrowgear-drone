package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AutocannonImpactPolicyTest {
	@Test
	void kineticHitAndBlastHaveIndependentDamageValues() {
		float blast = AutocannonImpactPolicy.blastDamage(0.0, true);
		assertTrue(AutocannonImpactPolicy.DIRECT_DAMAGE > blast);
		assertEquals(1.25f, blast, 0.0001f);
	}

	@Test
	void blastFallsOffAndCannotDamageThroughCoverOrOutsideItsRadius() {
		float near = AutocannonImpactPolicy.blastDamage(0.25, true);
		float far = AutocannonImpactPolicy.blastDamage(1.25, true);
		assertTrue(near > far && far > 0.0f);
		assertEquals(0.0f, AutocannonImpactPolicy.blastDamage(0.25, false));
		assertEquals(0.0f, AutocannonImpactPolicy.blastDamage(2.0, true));
	}
}
