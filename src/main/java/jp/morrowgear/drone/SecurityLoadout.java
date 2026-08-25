package jp.morrowgear.drone;

import java.util.Arrays;

public enum SecurityLoadout {
	UNARMED("unarmed", "UNARMED", null),
	AUTO("auto", "AUTO", null),
	AUTOCANNON("autocannon", "GUN", CombatWeapon.AUTOCANNON),
	LASER("laser", "LASER", CombatWeapon.LASER),
	MISSILE("missile", "MISSILE", CombatWeapon.MISSILE);

	private final String id;
	private final String displayName;
	private final CombatWeapon weapon;

	SecurityLoadout(String id, String displayName, CombatWeapon weapon) {
		this.id = id;
		this.displayName = displayName;
		this.weapon = weapon;
	}

	public String id() { return id; }
	public String displayName() { return displayName; }
	public CombatWeapon weapon() { return weapon; }

	public static SecurityLoadout byId(String id) {
		return Arrays.stream(values()).filter(loadout -> loadout.id.equalsIgnoreCase(id))
			.findFirst().orElse(AUTO);
	}

	public static boolean isValidId(String id) {
		return id != null && Arrays.stream(values()).anyMatch(loadout -> loadout.id.equalsIgnoreCase(id));
	}
}
