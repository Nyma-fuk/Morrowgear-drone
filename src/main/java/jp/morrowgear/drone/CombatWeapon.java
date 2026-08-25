package jp.morrowgear.drone;

public enum CombatWeapon {
	NONE(0, "NONE"),
	AUTOCANNON(1, "AUTOCANNON"),
	LASER(2, "LASER"),
	MISSILE(3, "MISSILE"),
	RAM(4, "RAM");

	private final int id;
	private final String label;

	CombatWeapon(int id, String label) {
		this.id = id;
		this.label = label;
	}

	public int id() { return id; }
	public String label() { return label; }

	public static CombatWeapon byId(int id) {
		for (CombatWeapon weapon : values()) if (weapon.id == id) return weapon;
		return NONE;
	}
}
