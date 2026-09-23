package jp.morrowgear.drone;

/** Storage upgrades change endurance only, not fire rate, damage or flight performance. */
public final class PayloadCapacity {
	public static final int MAX_TIER = 2;
	private PayloadCapacity() {}
	public static int tier(int value) { return Math.clamp(value, 0, MAX_TIER); }
	public static int gun(int tier) { return 240 + tier(tier) * 120; }
	public static int missiles(int tier) { return 45 + tier(tier) * 15; }
	public static int energy(int tier) { return 1000 + tier(tier) * 500; }
	public static int power(int value, int tier) { return Math.clamp(value, 0, energy(tier)); }
}
