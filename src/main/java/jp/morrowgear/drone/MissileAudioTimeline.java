package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Debris follows confirmed detonations, never predicted collisions or entity removal. */
public final class MissileAudioTimeline {
	private final Map<UUID, Impact> impacts = new LinkedHashMap<>();
	private long lastTick = Long.MIN_VALUE;

	public boolean detonated(UUID id, long tick, double x, double y, double z, int emitters) {
		if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
		if (impacts.containsKey(id) || impacts.size() >= DroneAudioPolicy.MAX_MISSILE_SEQUENCES) return false;
		impacts.put(id, new Impact(tick, x, y, z, DroneAudioPolicy.fleetGain(emitters)));
		return true;
	}

	public List<Cue> tick(long tick) {
		List<Cue> result = new ArrayList<>();
		if (tick < lastTick) clear();
		lastTick = tick;
		var iterator = impacts.values().iterator();
		while (iterator.hasNext()) {
			Impact impact = iterator.next();
			long age = tick - impact.tick;
			if (age < 0 || age > 100) {
				iterator.remove();
				continue;
			}
			if (!impact.played && age >= DroneAudioPolicy.MISSILE_DEBRIS_DELAY_TICKS) {
				impact.played = true;
				// Never replay a backlog after a clock jump or stalled callback.
				if (age - DroneAudioPolicy.MISSILE_DEBRIS_DELAY_TICKS <= DroneAudioPolicy.FRESH_EVENT_TICKS)
					result.add(new Cue(impact.x, impact.y, impact.z, impact.gain));
			}
			// Retain the event ID through the debris tail to reject duplicate impacts.
			if (age >= 80) iterator.remove();
		}
		return List.copyOf(result);
	}

	public void clear() {
		impacts.clear();
		lastTick = Long.MIN_VALUE;
	}

	public int size() { return impacts.size(); }

	public record Cue(double x, double y, double z, float gain) {}

	private static final class Impact {
		private final long tick;
		private final double x, y, z;
		private final float gain;
		private boolean played;
		private Impact(long tick, double x, double y, double z, float gain) {
			this.tick = tick;
			this.x = x;
			this.y = y;
			this.z = z;
			this.gain = gain;
		}
	}
}
