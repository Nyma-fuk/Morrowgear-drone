package jp.morrowgear.drone;

/** Bounded visual compensation; never feeds flight or combat decisions. */
public final class EffectReadabilityPolicy {
	private EffectReadabilityPolicy() {}

	public static double detail(double pixelsPerBlock) {
		if (!Double.isFinite(pixelsPerBlock)) return 0;
		double t = Math.clamp((pixelsPerBlock - 18) / 32, 0, 1);
		return t * t * (3 - 2 * t);
	}

	public static float width(float base, double pixelsPerBlock, float targetPixels, float maxScale) {
		if (!Double.isFinite(pixelsPerBlock) || pixelsPerBlock <= 0) return base;
		return (float)Math.clamp(targetPixels / pixelsPerBlock, base, base * maxScale);
	}
}
