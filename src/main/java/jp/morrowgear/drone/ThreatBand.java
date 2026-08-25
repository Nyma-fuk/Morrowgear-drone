package jp.morrowgear.drone;

public enum ThreatBand {
	LOW,
	GUARDED,
	HIGH,
	CRITICAL;

	public static ThreatBand fromScore(int score) {
		if (score >= 28) return CRITICAL;
		if (score >= 14) return HIGH;
		if (score >= 6) return GUARDED;
		return LOW;
	}
}
