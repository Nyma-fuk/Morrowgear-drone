package jp.morrowgear.drone;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Server facts, not control commands. Runtime evidence is deliberately not saved here. */
public record OperationEvent(UUID id, UUID owner, UUID context, UUID sourceUnit, long serverTick, long occurredAtMillis,
        Kind kind, String wing, String dimension, String sourceName, String voiceNumber,
        int count, int x, int y, int z) {
    public static final int HISTORY_LIMIT = 128;
    public static final int FLEET_LIMIT = 512;
    public static final int TEXT_LIMIT = 96;
    public static final int DIMENSION_LIMIT = 128;
    public static final long TTL_TICKS = 160;

    public OperationEvent {
        Objects.requireNonNull(id);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(context);
        Objects.requireNonNull(sourceUnit);
        Objects.requireNonNull(kind);
        requireText(wing, TEXT_LIMIT);
        requireText(dimension, DIMENSION_LIMIT);
        requireText(sourceName, TEXT_LIMIT);
        requireText(voiceNumber, TEXT_LIMIT);
        if (serverTick < 0 || occurredAtMillis < 0 || count < 1 || count > FLEET_LIMIT) {
            throw new IllegalArgumentException("Invalid operation event bounds");
        }
    }

    public String messageKey() { return kind.messageKey(); }
    public String source() { return wing.isBlank() ? sourceName : wing; }
    public List<Object> messageArgs() { return List.of(source(), count); }
    public boolean expired(long tick) { return tick < serverTick || tick - serverTick >= TTL_TICKS; }
    public String semanticKey() {
        String scope = wing.isBlank() ? sourceUnit.toString() : wing;
        String operation = kind == Kind.POWER_LOST || kind == Kind.DANGER ? "warning" : context.toString();
        return kind.name() + "/" + dimension + "/" + scope + "/" + operation;
    }

    public OperationEvent withCount(int remaining) {
        return new OperationEvent(id, owner, context, sourceUnit, serverTick, occurredAtMillis, kind, wing, dimension,
                sourceName, voiceNumber, remaining, x, y, z);
    }

    public static String bounded(String value, int limit) {
        if (value == null) return "";
        String clean = value.replaceAll("[\\p{Cntrl}]", " ");
        int end = Math.min(clean.length(), limit);
        if (end > 0 && Character.isHighSurrogate(clean.charAt(end - 1))) end--;
        return clean.substring(0, end);
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.length() > limit) throw new IllegalArgumentException("Operation text too long");
    }

    public enum Kind {
        POWER_LOST(4, false, false), DANGER(4, false, false), EMERGENCY_ATTACK(4, true, true),
        INTERCEPTING(3, true, true), OPERATION_BLOCKED(3, false, false),
        MATERIAL_LOW(3, false, false), CARGO_STALLED(3, false, false),
        TARGET_LOST(3, false, false),
        FOLLOW_STARTED(1, true, false), ROUTE_STARTED(1, true, false),
        PATROL_STARTED(1, true, false), SURVEY_STARTED(1, true, false),
        WORK_STARTED(1, true, false), CARGO_STARTED(1, true, false),
        REPAIR_STARTED(1, true, false), SALVAGE_STARTED(1, true, true),
        SALVAGE_ATTACHED(1, true, true), LASER_STARTED(1, true, true),
        GUN_STARTED(1, true, true), MISSILE_STARTED(1, true, true),
        MISSION_RESUMED(1, false, false), SERVICE_RETURN(1, false, false),
        RETURN_STARTED(1, false, false), DOCKED(0, false, false),
        SERVICE_COMPLETE(0, false, false), OPERATION_COMPLETE(0, true, false);

        private final int priority;
        private final boolean oncePerMission;
        private final boolean requiresTarget;

        Kind(int priority, boolean oncePerMission, boolean requiresTarget) {
            this.priority = priority;
            this.oncePerMission = oncePerMission;
            this.requiresTarget = requiresTarget;
        }

        public int priority(boolean selected) {
            return priority >= 3 ? priority : selected && priority == 1 ? 2 : priority;
        }
        public boolean important() { return priority >= 3; }
        public boolean interrupts() { return priority == 4; }
        public boolean oncePerMission() { return oncePerMission; }
        public boolean requiresTarget() { return requiresTarget; }
        public String cueId() { return name().toLowerCase(Locale.ROOT); }
        public String messageKey() { return "communication.morrowgear_drone." + cueId(); }
    }
}
