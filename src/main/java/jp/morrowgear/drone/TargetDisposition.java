package jp.morrowgear.drone;

public enum TargetDisposition {
	INVALID("INVALID", false),
	FRIENDLY("FRIENDLY", false),
	NEUTRAL("NEUTRAL", false),
	HOSTILE("HOSTILE", true),
	DIRECT_THREAT("DIRECT THREAT", true);

	private final String label;
	private final boolean engageable;

	TargetDisposition(String label, boolean engageable) {
		this.label = label;
		this.engageable = engageable;
	}

	public String label() { return label; }
	public boolean engageable() { return engageable; }
}
