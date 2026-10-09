package jp.morrowgear.drone;

/** Authoritative discovery volumes. Data-link forwarding is owner/dimension scoped, not distance scoped. */
public final class ThreatSensorPolicy {
	public static final double OWNER_GUARD_RADIUS = 24.0;
	public static final double OWNER_GUARD_VERTICAL = 12.0;
	public static final double LOCAL_SECURITY_RADIUS = 24.0;
	public static final double LOCAL_SECURITY_VERTICAL = 12.0;
	public static final double SCOUT_RADIUS = 64.0;
	public static final double SCOUT_VERTICAL = 32.0;
	public static final int MAX_PATROL_RADIUS = 64;
	public static final long REPORT_TTL_TICKS = 120L;
	public static final long NETWORK_MAINTENANCE_INTERVAL_TICKS = 10L;

	private ThreatSensorPolicy() {}
}
