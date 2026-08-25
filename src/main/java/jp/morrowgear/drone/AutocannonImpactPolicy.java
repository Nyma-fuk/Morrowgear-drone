package jp.morrowgear.drone;

final class AutocannonImpactPolicy {
	static final double BLAST_RADIUS = 1.55;
	static final float DIRECT_DAMAGE = 2.4f;

	private AutocannonImpactPolicy() {
	}

	static float blastDamage(double distance, boolean exposed) {
		if (!exposed || distance > BLAST_RADIUS) return 0.0f;
		double normalized = Math.max(0.0, 1.0 - distance / BLAST_RADIUS);
		return (float)(0.25 + normalized * 1.0);
	}
}
