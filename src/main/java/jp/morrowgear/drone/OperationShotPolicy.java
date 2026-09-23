package jp.morrowgear.drone;

import java.util.Optional;

/** A firing state is an intention; the server's shot tick is the execution evidence. */
public final class OperationShotPolicy {
    private OperationShotPolicy() {}

    public static Optional<OperationEvent.Kind> confirmed(CombatWeapon weapon, CombatState state,
            long now, long shotTick, long stateSince) {
        if (shotTick < 0 || shotTick < stateSince || shotTick > now
                || now - shotTick >= OperationEvent.TTL_TICKS) return Optional.empty();
        return switch (weapon) {
            case AUTOCANNON -> state == CombatState.GUN_RUN
                    ? Optional.of(OperationEvent.Kind.GUN_STARTED) : Optional.empty();
            case MISSILE -> state == CombatState.MISSILE_APPROACH || state == CombatState.MISSILE_EGRESS
                    ? Optional.of(OperationEvent.Kind.MISSILE_STARTED) : Optional.empty();
            case LASER -> state == CombatState.LASER_FIRE
                    ? Optional.of(OperationEvent.Kind.LASER_STARTED) : Optional.empty();
            default -> Optional.empty();
        };
    }
}
