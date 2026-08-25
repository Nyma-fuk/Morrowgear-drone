package jp.morrowgear.drone;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/** Applies independent Morrowgear hits without removing the target's normal hurt cooldown. */
final class MorrowgearCombatDamage {
	private MorrowgearCombatDamage() {}

	static boolean apply(ServerLevel level, LivingEntity target, DamageSource source, float amount) {
		if (!target.isAlive() || amount <= 0.0f) return false;
		int externalCooldown = target.invulnerableTime;
		target.invulnerableTime = 0;
		try {
			return target.hurtServer(level, source, amount);
		} finally {
			// Morrowgear hits are independent from each other, while vanilla and other mods
			// still observe the longest cooldown that was active or created by this hit.
			target.invulnerableTime = preservedCooldown(externalCooldown, target.invulnerableTime);
		}
	}

	static int preservedCooldown(int before, int generated) {
		return Math.max(Math.max(0, before), Math.max(0, generated));
	}
}
