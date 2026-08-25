package jp.morrowgear.drone;

import net.minecraft.util.Mth;

final class EnemyThreatPolicy {
	private EnemyThreatPolicy() {}

	static Profile assess(Factors factors) {
		double health = Math.min(45.0, Math.max(0.0, factors.maxHealth()) * 0.09);
		double offense = Math.min(35.0, Math.max(0.0, factors.attackDamage()) * 1.15);
		double protection = Math.min(12.0, Math.max(0.0, factors.armor()) * 0.6);
		double mobility = Math.min(8.0, Math.max(0.0, factors.movementSpeed()) * 12.0);
		int score = Mth.clamp((int)Math.ceil(health + offense + protection + mobility
			+ Math.max(0, factors.specialRisk())), 1, 100);
		return new Profile(score, (int)Math.ceil(health), (int)Math.ceil(offense),
			(int)Math.ceil(protection), (int)Math.ceil(mobility));
	}

	static int playerDanger(int enemyThreat, double distance, boolean attackingPlayer,
		boolean recentAttacker, boolean incomingProjectile) {
		int proximity = Mth.clamp((int)Math.ceil((24.0 - Math.max(0.0, distance)) / 1.5), 0, 16);
		int danger = proximity + Math.min(24, Math.max(0, enemyThreat) / 4);
		if (attackingPlayer) danger += 24;
		if (recentAttacker) danger += 18;
		if (incomingProjectile) danger += 24;
		return Mth.clamp(danger, 0, 100);
	}

	record Factors(double maxHealth, double attackDamage, double armor,
		double movementSpeed, int specialRisk) {}
	record Profile(int score, int durability, int offense, int protection, int mobility) {}
}
