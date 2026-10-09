package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AttackWavePlanTest {
    private static final UUID PLAN = new UUID(1, 1);
    private static final UUID TARGET = new UUID(2, 2);
    private static final UUID WAVE = new UUID(3, 3);
    private static final UUID A = new UUID(4, 1);
    private static final UUID B = new UUID(4, 2);
    private static final UUID C = new UUID(4, 3);

    @Test void snapshotOwnsImmutableIdentityMembershipAndSlotData() {
        var members = new ArrayList<>(List.of(A, B));
        var slots = new HashMap<>(Map.of(A, 0, B, 4));
        var plan = laser(members, slots, 8, 7, AttackWavePhase.WAVE_PLAN);
        members.clear();
        slots.clear();

        assertEquals(PLAN, plan.planId());
        assertEquals(7, plan.revision());
        assertEquals(TARGET, plan.targetId());
        assertEquals(WAVE, plan.waveId());
        assertEquals(19, plan.formationGeneration());
        assertEquals(List.of(A, B), plan.memberIds());
        assertEquals(0, plan.slotFor(A).orElseThrow());
        assertEquals(4, plan.slotFor(B).orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> plan.memberIds().add(C));
        assertThrows(UnsupportedOperationException.class, () -> plan.reservedLaserSlots().put(C, 2));
    }

    @Test void eightReservedSlotsRemainStableWhenOnlySomeAircraftArePresent() {
        var plan = laser(List.of(A, B, C), Map.of(A, 0, B, 3, C, 7), 8, 0, AttackWavePhase.WAVE_EXECUTE);
        assertEquals(Set.of(1, 2, 4, 5, 6, 7), plan.vacantLaserSlots(Set.of(A, B)));
        assertEquals(7, plan.slotFor(C).orElseThrow());
        assertEquals(Map.of(A, 0, B, 3, C, 7), plan.reservedLaserSlots());
    }

    @Test void boundsDuplicatesAndMalformedReservationsAreRejected() {
        var nine = new ArrayList<UUID>();
        for (int i = 0; i < 9; i++) nine.add(new UUID(9, i));
        assertThrows(IllegalArgumentException.class, () -> gun(nine));
        var oversizedSlots = new HashMap<UUID, Integer>();
        for (int i = 0; i < 9; i++) oversizedSlots.put(new UUID(10, i), i);
        assertThrows(IllegalArgumentException.class,
            () -> laser(List.of(A), oversizedSlots, 8, 0, AttackWavePhase.WAVE_PLAN));
        assertThrows(IllegalArgumentException.class, () -> gun(List.of(A, A)));
        assertThrows(IllegalArgumentException.class,
            () -> laser(List.of(A, B), Map.of(A, 0), 2, 0, AttackWavePhase.WAVE_PLAN));
        assertThrows(IllegalArgumentException.class,
            () -> laser(List.of(A, B), Map.of(A, 0, B, 0), 2, 0, AttackWavePhase.WAVE_PLAN));
        assertThrows(IllegalArgumentException.class,
            () -> laser(List.of(A, B), Map.of(A, 0, B, 2), 2, 0, AttackWavePhase.WAVE_PLAN));
        assertThrows(IllegalArgumentException.class,
            () -> new AttackWavePlan(PLAN, 0, TARGET, WAVE, 1, List.of(A), Map.of(A, 0), 1,
                CombatWeapon.AUTOCANNON, AttackWavePhase.WAVE_PLAN, 0, 10));
    }

    @Test void coolingPhaseCannotBeAttachedToAnotherWeapon() {
        assertThrows(IllegalArgumentException.class, () -> new AttackWavePlan(PLAN, 0, TARGET, WAVE, 1,
            List.of(A), Map.of(), 0, CombatWeapon.MISSILE, AttackWavePhase.LASER_COOL_ORBIT, 0, 10));
    }

    private static AttackWavePlan laser(List<UUID> members, Map<UUID, Integer> slots, int slotCount,
                                        long revision, AttackWavePhase phase) {
        return new AttackWavePlan(PLAN, revision, TARGET, WAVE, 19, members, slots, slotCount,
            CombatWeapon.LASER, phase, 100, 200);
    }

    private static AttackWavePlan gun(List<UUID> members) {
        return new AttackWavePlan(PLAN, 0, TARGET, WAVE, 1, members, Map.of(), 0,
            CombatWeapon.AUTOCANNON, AttackWavePhase.WAVE_PLAN, 0, 10);
    }
}
