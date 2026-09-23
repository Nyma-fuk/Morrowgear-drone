package jp.morrowgear.drone;

/** Gain values are source gains, before the player's mixer and spatial attenuation. */
public final class DroneAudioPolicy {
	public static final int MAX_FLIGHT_EMITTERS = 8;
	public static final int MAX_LASER_EMITTERS = 4;
	public static final int MAX_CANNON_EMITTERS = 8;
	public static final int MAX_MOTOR_EMITTERS = 12;
	public static final int MAX_TAILS = 32;
	public static final int RETRY_TICKS = 20;
	public static final int FRESH_EVENT_TICKS = 3;
	public static final int MISSILE_DEBRIS_DELAY_TICKS = 7;
	public static final int MAX_MISSILE_SEQUENCES = 64;
	public static final float FLIGHT_RANGE = 64.0f;
	public static final float LASER_RANGE = 48.0f;
	public static final float WEAPON_RANGE = 64.0f;

	private DroneAudioPolicy() {}

	public static boolean flightActive(boolean alive, boolean docked, boolean powerLost, boolean silent) {
		return alive && !docked && !powerLost && !silent;
	}

	public static float fleetGain(int emitters) {
		return (float)(1.0 / Math.sqrt(Math.max(1, emitters)));
	}

	public static FlightMix flightMix(double speed, int emitters) {
		double throttle = Double.isFinite(speed) ? Math.clamp((speed - 0.12) / 0.70, 0, 1) : 0;
		throttle = throttle * throttle * (3 - 2 * throttle);
		float gain = fleetGain(emitters);
		// Both assets share their fundamentals; linear mixing avoids a mid-throttle boost.
		return new FlightMix((float)(0.20 * (1 - throttle)) * gain,
			(float)(0.24 * throttle) * gain, (float)(0.94 + 0.10 * throttle));
	}

	public static float approach(float current, float target, float step) {
		return current + Math.clamp(target - current, -step, step);
	}

	public static boolean freshEvent(long now, long eventTick) {
		return eventTick >= 0 && now >= eventTick && now - eventTick <= FRESH_EVENT_TICKS;
	}

	public static boolean chargeStarted(CombatState previous, CombatState current, long now, long stateTick) {
		return current == CombatState.LASER_CHARGE && previous != current && freshEvent(now, stateTick);
	}

	public static boolean dischargeStarted(CombatState previous, CombatState current, long now, long stateTick) {
		return current == CombatState.LASER_FIRE && previous != current && freshEvent(now, stateTick);
	}

	public static boolean coolingStarted(CombatState previous, CombatState current) {
		return previous == CombatState.LASER_FIRE && current != CombatState.LASER_FIRE;
	}

	public static boolean missileMotorActive(boolean alive, boolean silent, boolean ignited, boolean impacted) {
		return alive && !silent && ignited && !impacted;
	}

	/** A recent confirmed shot, not GUN_RUN alone, holds the audible burst open. */
	public static final class CannonGate {
		private boolean playing;
		public CannonChange update(CombatState state, long now, long lastShot) {
			boolean next = state == CombatState.GUN_RUN && freshEvent(now, lastShot);
			CannonChange change = new CannonChange(next, next && !playing, playing && !next);
			playing = next;
			return change;
		}
	}

	public record CannonChange(boolean playing, boolean started, boolean stopped) {}

	public record FlightMix(float idle, float cruise, float pitch) {}
}
