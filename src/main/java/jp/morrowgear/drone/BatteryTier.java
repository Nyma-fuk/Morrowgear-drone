package jp.morrowgear.drone;

import java.util.Arrays;

public enum BatteryTier {
	STANDARD("standard", "STANDARD", 1000),
	REINFORCED("reinforced", "REINFORCED", 1800),
	HIGH_DENSITY("high_density", "HIGH DENSITY", 3000);

	private final String id;
	private final String displayName;
	private final int capacity;

	BatteryTier(String id, String displayName, int capacity) {
		this.id = id;
		this.displayName = displayName;
		this.capacity = capacity;
	}

	public String id() { return id; }
	public String displayName() { return displayName; }
	public int capacity() { return capacity; }

	public int clamp(int stored) {
		return Math.max(0, Math.min(capacity, stored));
	}

	public int storedForPercent(int percent) {
		return Math.max(0, Math.min(100, percent)) * capacity / 100;
	}

	public static BatteryTier byId(String id) {
		return Arrays.stream(values()).filter(tier -> tier.id.equalsIgnoreCase(id))
			.findFirst().orElse(STANDARD);
	}
}
