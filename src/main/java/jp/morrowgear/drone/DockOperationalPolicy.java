package jp.morrowgear.drone;

public final class DockOperationalPolicy {
	private DockOperationalPolicy() {
	}

	public static Status status(boolean occupied, int aircraftBattery, String aircraftStatus,
		int storedPower, int powerCapacity, boolean powerAvailable,
		boolean repairAvailable) {
		int powerPercent = powerCapacity <= 0 ? 0
			: Math.max(0, Math.min(100, storedPower * 100 / powerCapacity));
		String normalized = aircraftStatus == null ? "" : aircraftStatus.toUpperCase();
		if (occupied) {
			if (normalized.contains("REPAIR MATERIAL") || normalized.contains("MAINTENANCE MATERIAL"))
				return new Status("WAIT REPAIR", Severity.BLOCKED, powerPercent);
			if (normalized.contains("AMMUNITION"))
				return new Status("WAIT AMMO", Severity.BLOCKED, powerPercent);
			if (normalized.contains("POWER SUPPLY"))
				return new Status("WAIT POWER", Severity.BLOCKED, powerPercent);
			if (aircraftBattery < DroneServicePolicy.NORMAL_SORTIE_POWER)
				return new Status("CHARGE " + Math.max(0, aircraftBattery) + "%", Severity.SERVICING, powerPercent);
			if (!repairAvailable && normalized.contains("SERVICE"))
				return new Status("LIMITED", Severity.CAUTION, powerPercent);
			return new Status("BAY ONLINE", Severity.READY, powerPercent);
		}
		if (!powerAvailable) return new Status("WAIT POWER", Severity.BLOCKED, powerPercent);
		return new Status("READY", Severity.READY, powerPercent);
	}

	public enum Severity { READY, SERVICING, CAUTION, BLOCKED }

	public record Status(String label, Severity severity, int powerPercent) {
		public Status {
			label = label == null ? "" : label;
			severity = severity == null ? Severity.BLOCKED : severity;
			powerPercent = Math.max(0, Math.min(100, powerPercent));
		}
	}
}
