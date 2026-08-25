package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MorrowgearCombatDamageTest {
	@Test void normalTargetCooldownIsNeverShortenedByIndependentMorrowgearHits() {
		assertEquals(20, MorrowgearCombatDamage.preservedCooldown(20, 0));
		assertEquals(20, MorrowgearCombatDamage.preservedCooldown(6, 20));
		assertEquals(14, MorrowgearCombatDamage.preservedCooldown(14, 10));
		assertEquals(0, MorrowgearCombatDamage.preservedCooldown(-2, -1));
	}
}
