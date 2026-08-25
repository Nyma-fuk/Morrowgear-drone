package jp.morrowgear.drone;

public final class FormationLightTrailProfile {
	public static final int LIFETIME_TICKS = 100;
	public static final int MAX_CONTROL_POINTS = 72;
	public static final double MIN_SAMPLE_DISTANCE = 0.12;
	public static final int MAX_SAMPLE_INTERVAL = 2;
	private static final float FADE_START = 0.72f;

	private FormationLightTrailProfile() {}

	public static float alpha(int ageTicks) {
		if (ageTicks < 0) return 1.0f;
		if (ageTicks >= LIFETIME_TICKS) return 0.0f;
		float progress = (float) ageTicks / LIFETIME_TICKS;
		if (progress <= FADE_START) return 1.0f;
		float fade = 1.0f - (progress - FADE_START) / (1.0f - FADE_START);
		return fade * fade;
	}

	public static boolean shouldSample(double distance, long elapsedTicks) {
		return distance >= MIN_SAMPLE_DISTANCE || elapsedTicks >= MAX_SAMPLE_INTERVAL;
	}

	public static boolean retainState(boolean visible, boolean active, boolean hasPoints) {
		return visible && (active || hasPoints);
	}
}
