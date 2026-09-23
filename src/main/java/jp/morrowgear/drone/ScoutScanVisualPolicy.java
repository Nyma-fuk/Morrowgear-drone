package jp.morrowgear.drone;

/** Bounded, client-only presentation policy. This does not report or discover threats. */
public final class ScoutScanVisualPolicy {
	public static final int MAX_CACHED_SCOUTS = 24;
	public static final int MAX_RAYS = 9;
	public static final int MAX_RAYCASTS_PER_TICK = MAX_CACHED_SCOUTS * MAX_RAYS;
	public static final int MAX_MOB_BOUNDS = 16;
	public static final int CACHE_RETENTION_TICKS = 6;
	public static final int REVOLUTION_TICKS = 100;
	public static final double FAN_RADIANS = Math.toRadians(78);
	public static final double RAY_LENGTH = 48;
	public static final double MAX_DISTANCE = 112;
	public static final double MOTION_REFRESH_DISTANCE = 0.75;
	public static final int CURTAIN_ALPHA = 26;
	public static final int EDGE_ALPHA = 185;

	private ScoutScanVisualPolicy() {}

	public static boolean eligible(DroneRole role, boolean alive, boolean docked, boolean powerLost, boolean airborne) {
		return role == DroneRole.SCOUT && alive && !docked && !powerLost && airborne;
	}

	public static int rayCount(double distance) {
		if (!Double.isFinite(distance) || distance < 0 || distance >= MAX_DISTANCE) return 0;
		return distance < 24 ? MAX_RAYS : distance < 44 ? 6 : 4;
	}

	public static int refreshInterval(double distance) {
		return distance < 24 ? 1 : distance < 44 ? 2 : 4;
	}

	public static boolean shouldRefresh(long now, long sampledAt, double distance) {
		return sampledAt == Long.MIN_VALUE || now < sampledAt || now - sampledAt >= refreshInterval(distance);
	}

	public static boolean shouldRefresh(long now, long sampledAt, double distance, double movedDistance) {
		return shouldRefresh(now, sampledAt, distance) || (now != sampledAt && movedDistance >= MOTION_REFRESH_DISTANCE);
	}

	public static double sweepAngle(double ageInTicks) {
		if (!Double.isFinite(ageInTicks)) return 0;
		double cycles = ageInTicks / REVOLUTION_TICKS;
		return (cycles - Math.floor(cycles)) * Math.PI * 2;
	}

	public static double rayAngle(double ageInTicks, int ray, int count) {
		return sweepAngle(ageInTicks) - FAN_RADIANS + FAN_RADIANS * ray / Math.max(1, count - 1);
	}

	public static float distanceAlpha(double distance) {
		if (!Double.isFinite(distance)) return 0;
		float t = (float) Math.clamp((distance - 44) / (MAX_DISTANCE - 44), 0, 1);
		return 1 - t * t * (3 - 2 * t);
	}

	public static boolean joinCurtain(double firstLength, double secondLength, boolean firstMob, boolean secondMob) {
		return !firstMob && !secondMob && firstLength >= 0.5 && secondLength >= 0.5
			&& Math.abs(firstLength - secondLength) < 1.5;
	}

	public static double clippedLength(double terrainLength, double mobLength) {
		return Math.clamp(Math.min(terrainLength, mobLength), 0, RAY_LENGTH);
	}

	public static final class TickBudget {
		private long tick = Long.MIN_VALUE;
		private int spent;

		public boolean reserve(long now, int rays) {
			if (now != tick) { tick = now; spent = 0; }
			if (rays <= 0 || rays > MAX_RAYS || spent + rays > MAX_RAYCASTS_PER_TICK) return false;
			spent += rays;
			return true;
		}

		public int spent() { return spent; }
		public void reset() { tick = Long.MIN_VALUE; spent = 0; }
	}
}
