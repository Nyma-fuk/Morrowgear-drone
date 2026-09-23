package jp.morrowgear.drone.client;

import java.util.LinkedHashMap;
import java.util.UUID;

/** Arrival-relative timing: server entity age is not the age of a late-joining client entity. */
final class CarrierEffectTimeline {
    static final int MAX_TRACKED = 128, CAPTURE_TICKS = 18;
    private final LinkedHashMap<UUID, Sample> samples = new LinkedHashMap<>();
    private record Sample(int tick, int sequence, int items, double received, double captured, CarrierEffectGeometry.Point position) {}
    record Timing(double interpolation, boolean fresh, double captureAge, CarrierEffectGeometry.Point position) {}
    Timing observe(UUID id, int tick, int sequence, int items, double localTick, CarrierEffectGeometry.Point position) {
        Sample old = samples.get(id);
        boolean reset = old == null || tick < old.tick || sequence < old.sequence || items < old.items || localTick < old.received;
        double captured = reset ? Double.NEGATIVE_INFINITY : old.captured;
        CarrierEffectGeometry.Point anchor = reset ? null : old.position;
        // Continuous commits must not restart one sweep every frame or move its confirmed origin.
        if (!reset && sequence > old.sequence && items > old.items && localTick - captured >= CAPTURE_TICKS) {
            captured = localTick; anchor = position;
        }
        double received = reset || old.tick != tick ? localTick : old.received;
        samples.put(id, new Sample(tick, sequence, items, received, captured, anchor));
        if (samples.size() > MAX_TRACKED) samples.remove(samples.keySet().iterator().next());
        double age = localTick - received;
        return new Timing(Math.clamp(age, 0, 10), age <= 20, localTick - captured, anchor);
    }
    int size() { return samples.size(); }
}
