package jp.morrowgear.drone;

public final class FormationLightTrailProfile {
	public static final int LIFETIME_TICKS = 100;
	public static final int MAX_CONTROL_POINTS = 72;
	public static final double MIN_SAMPLE_DISTANCE = 0.12;
	public static final int MAX_SAMPLE_INTERVAL = 2;
	public static final int MAX_TRACKED_DRONES = 64;
	public static final int STYLE_BLEND_TICKS = 8;
	public static final int STOP_FADE_TICKS = 10;
	public static final double TELEPORT_DISTANCE = 8.0;
	public static final double MAX_RENDER_DISTANCE = 96.0;
	public static final float HALO_WIDTH = 0.072f;
	public static final float CORE_WIDTH = 0.022f;
	public static final int CYAN = 0x20D7DF;
	public static final int COMBAT_AMBER_RED = 0xFF471D;

	private FormationLightTrailProfile() {}

	public static float alpha(int ageTicks) {
		return alpha((float) ageTicks);
	}

	public static float alpha(float ageTicks) {
		if (!Float.isFinite(ageTicks)) return 0;
		if (ageTicks < 0) return 1.0f;
		if (ageTicks >= LIFETIME_TICKS) return 0.0f;
		return 1 - smoothstep(ageTicks / LIFETIME_TICKS);
	}

	public static float stopAlpha(float elapsedTicks) {
		return 1 - smoothstep(elapsedTicks / STOP_FADE_TICKS);
	}

	public static float distanceAlpha(double distance) {
		if (!Double.isFinite(distance)) return 0;
		return 1 - smoothstep((float) ((distance - 72) / (MAX_RENDER_DISTANCE - 72)));
	}

	public static int sampleStride(double distance) {
		return distance < 32 ? 1 : distance < 64 ? 2 : 4;
	}

	public static boolean discontinuity(double distance, long elapsedTicks) {
		return !Double.isFinite(distance) || distance > TELEPORT_DISTANCE || elapsedTicks < 0;
	}

	public static int color(FormationTrailPolicy.TrailStyle style) {
		return style == FormationTrailPolicy.TrailStyle.COMBAT_ENTRY ? COMBAT_AMBER_RED : CYAN;
	}

	public static int blendColor(int from, int to, float progress) {
		float t = Math.clamp(progress, 0, 1);
		int result = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			int a = (from >> shift) & 255, b = (to >> shift) & 255;
			result |= Math.round(a + (b - a) * t) << shift;
		}
		return result;
	}

	private static float smoothstep(float value) {
		float t = Math.clamp(value, 0, 1);
		return t * t * (3 - 2 * t);
	}

	public static boolean shouldSample(double distance, long elapsedTicks) {
		return distance >= MIN_SAMPLE_DISTANCE || elapsedTicks >= MAX_SAMPLE_INTERVAL;
	}

	public static boolean retainState(boolean visible, boolean active, boolean hasPoints) {
		return visible && (active || hasPoints);
	}
}
