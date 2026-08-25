package jp.morrowgear.drone;

public enum FieldOperationType {
	NONE("none", "NONE"),
	ORE("ore", "ORE OPS"),
	EXCAVATE("excavate", "EXCAVATE"),
	FORESTRY("forestry", "FORESTRY");

	private final String id;
	private final String label;

	FieldOperationType(String id, String label) {
		this.id = id;
		this.label = label;
	}

	public String id() { return id; }
	public String label() { return label; }

	public static FieldOperationType byId(String id) {
		for (FieldOperationType type : values()) if (type.id.equalsIgnoreCase(id)) return type;
		return NONE;
	}
}
