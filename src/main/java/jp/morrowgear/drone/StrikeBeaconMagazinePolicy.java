package jp.morrowgear.drone;

/** Pure three-round magazine arithmetic shared by item state and regression tests. */
public final class StrikeBeaconMagazinePolicy {
	public static final int CAPACITY = 3;
	private StrikeBeaconMagazinePolicy() {}

	public static int normalize(int rounds) { return Math.max(0, Math.min(CAPACITY, rounds)); }
	public static int afterShot(int rounds) { return Math.max(0, normalize(rounds) - 1); }
	public static int reloadDemand(int rounds) { return CAPACITY - normalize(rounds); }
	public static int afterReload(int rounds, int available) {
		return normalize(normalize(rounds) + Math.max(0, available));
	}
}
