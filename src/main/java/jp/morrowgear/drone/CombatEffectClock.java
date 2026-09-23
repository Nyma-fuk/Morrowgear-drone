package jp.morrowgear.drone;

/** Shot events use the shared world clock, never the client's entity tracking age. */
public final class CombatEffectClock {
	private CombatEffectClock() {}

	public static int shotAge(long worldTick, long shotTick) {
		if (shotTick < 0) return Integer.MAX_VALUE;
		if (worldTick <= shotTick) return 0;
		return (int)Math.min(Integer.MAX_VALUE, worldTick - shotTick);
	}
}
