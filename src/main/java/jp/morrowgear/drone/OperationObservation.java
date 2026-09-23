package jp.morrowgear.drone;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, read-only server sample. missionKey excludes route progress and weapon passes. */
public record OperationObservation(UUID owner, UUID unit, String missionKey, String wing,
        String dimension, String sourceName, String voiceNumber, int x, int y, int z,
        UUID target, boolean targetAlive, Set<OperationEvent.Kind> states,
        boolean rejoining, boolean servicing, boolean docked, boolean serviceReady,
        boolean missionRunning, int cargoItems) {
    public OperationObservation {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(unit);
        Objects.requireNonNull(missionKey);
        states = Set.copyOf(states);
    }

    public boolean sameMission(OperationObservation other) {
        return owner.equals(other.owner) && missionKey.equals(other.missionKey)
                && wing.equals(other.wing) && dimension.equals(other.dimension);
    }

    public boolean supports(OperationEvent.Kind kind) {
        if (kind == OperationEvent.Kind.MISSION_RESUMED) return missionRunning && !rejoining && !servicing;
        if (kind == OperationEvent.Kind.SERVICE_COMPLETE) return !servicing && !docked && serviceReady;
        return states.contains(kind) && (!kind.requiresTarget() || target != null && targetAlive);
    }
}
