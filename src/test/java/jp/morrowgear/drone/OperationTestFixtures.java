package jp.morrowgear.drone;

import java.util.Set;
import java.util.UUID;

final class OperationTestFixtures {
    static final UUID OWNER = new UUID(0, 1);
    static final UUID OTHER = new UUID(0, 2);
    static final UUID UNIT = new UUID(0, 10);
    static final UUID TARGET = new UUID(0, 20);
    static final UUID CONTEXT = new UUID(0, 30);
    static final UUID SESSION = new UUID(0, 40);
    static final OperationRadioSettings STANDARD = new OperationRadioSettings(OperationRadioSettings.VoiceMode.STANDARD, 1, true);

    static OperationEvent event(OperationEvent.Kind kind, long tick) { return event(OWNER, kind, tick, "WING-2"); }
    static OperationEvent event(UUID owner, OperationEvent.Kind kind, long tick, String wing) {
        return new OperationEvent(UUID.randomUUID(), owner, CONTEXT, UNIT, tick, 1_000, kind, wing,
                "minecraft:overworld", "Unit 07", "2", 1, 1, 64, 3);
    }
    static OperationObservation sample(String mission, OperationEvent.Kind... kinds) {
        return sample(OWNER, UNIT, mission, "WING-2", TARGET, true, Set.of(kinds));
    }
    static OperationObservation sample(UUID owner, UUID unit, String mission, String wing,
            UUID target, boolean alive, Set<OperationEvent.Kind> kinds) {
        return new OperationObservation(owner, unit, mission, wing, "minecraft:overworld", "Unit 07", "2",
                1, 64, 3, target, alive, kinds, false, false, false, false, true, 0);
    }
    static OperationObservation flags(OperationObservation s, boolean rejoining, boolean servicing,
            boolean docked, boolean ready, boolean running) {
        return new OperationObservation(s.owner(), s.unit(), s.missionKey(), s.wing(), s.dimension(),
                s.sourceName(), s.voiceNumber(), s.x(), s.y(), s.z(), s.target(), s.targetAlive(),
                s.states(), rejoining, servicing, docked, ready, running, s.cargoItems());
    }
}
