package jp.morrowgear.drone;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable, bounded coordinator snapshot for one attack wave revision. */
public record AttackWavePlan(
    UUID planId,
    long revision,
    UUID targetId,
    UUID waveId,
    long formationGeneration,
    List<UUID> memberIds,
    Map<UUID, Integer> reservedLaserSlots,
    int reservedLaserSlotCount,
    CombatWeapon activeWeapon,
    AttackWavePhase phase,
    long phaseStartedAt,
    long expiresAt
) {
    public static final int MAX_MEMBERS = 8;

    public AttackWavePlan {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(waveId, "waveId");
        Objects.requireNonNull(activeWeapon, "activeWeapon");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(memberIds, "memberIds");
        Objects.requireNonNull(reservedLaserSlots, "reservedLaserSlots");
        if (memberIds.isEmpty() || memberIds.size() > MAX_MEMBERS
            || reservedLaserSlots.size() > MAX_MEMBERS)
            throw new IllegalArgumentException("Attack wave data exceeds its bounded capacity");
        memberIds = List.copyOf(memberIds);
        reservedLaserSlots = Map.copyOf(reservedLaserSlots);
        if (revision < 0 || revision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid revision");
        if (formationGeneration < 0) throw new IllegalArgumentException("Invalid formation generation");
        if (memberIds.stream().anyMatch(Objects::isNull) || new HashSet<>(memberIds).size() != memberIds.size())
            throw new IllegalArgumentException("Attack wave members must be unique");
        if (phaseStartedAt < 0 || expiresAt < phaseStartedAt)
            throw new IllegalArgumentException("Invalid phase time range");
        if (activeWeapon == CombatWeapon.NONE || activeWeapon == CombatWeapon.RAM)
            throw new IllegalArgumentException("Unsupported attack wave weapon");
        validateSlots(memberIds, reservedLaserSlots, reservedLaserSlotCount, activeWeapon);
        if (phase == AttackWavePhase.LASER_COOL_ORBIT && activeWeapon != CombatWeapon.LASER)
            throw new IllegalArgumentException("Only a laser wave can hold a cooling orbit");
    }

    private static void validateSlots(List<UUID> members, Map<UUID, Integer> slots, int slotCount,
                                      CombatWeapon weapon) {
        if (weapon != CombatWeapon.LASER) {
            if (!slots.isEmpty() || slotCount != 0)
                throw new IllegalArgumentException("Only laser waves reserve circular slots");
            return;
        }
        if (slotCount < members.size() || slotCount > MAX_MEMBERS)
            throw new IllegalArgumentException("Invalid laser slot capacity");
        if (!slots.keySet().equals(Set.copyOf(members)))
            throw new IllegalArgumentException("Every planned laser member requires one reserved slot");
        var used = new HashSet<Integer>();
        for (int slot : slots.values()) {
            if (slot < 0 || slot >= slotCount || !used.add(slot))
                throw new IllegalArgumentException("Laser slots must be unique and within the reservation");
        }
    }

    public OptionalInt slotFor(UUID memberId) {
        Integer slot = reservedLaserSlots.get(memberId);
        return slot == null ? OptionalInt.empty() : OptionalInt.of(slot);
    }

    /** Slots remain reserved even when their planned aircraft is absent. */
    public Set<Integer> vacantLaserSlots(Set<UUID> presentMembers) {
        Objects.requireNonNull(presentMembers, "presentMembers");
        if (presentMembers.size() > MAX_MEMBERS) throw new IllegalArgumentException("Too many present members");
        Set<UUID> present = Set.copyOf(presentMembers);
        if (!memberIds.containsAll(present)) throw new IllegalArgumentException("Unknown present member");
        var vacant = new TreeSet<Integer>();
        for (int slot = 0; slot < reservedLaserSlotCount; slot++) vacant.add(slot);
        for (UUID member : present) {
            Integer slot = reservedLaserSlots.get(member);
            if (slot != null) vacant.remove(slot);
        }
        return Set.copyOf(vacant);
    }

    AttackWavePlan atRevision(long nextRevision, AttackWavePhase nextPhase, long startedAt, long nextExpiresAt) {
        return new AttackWavePlan(planId, nextRevision, targetId, waveId, formationGeneration, memberIds,
            reservedLaserSlots, reservedLaserSlotCount, activeWeapon, nextPhase, startedAt, nextExpiresAt);
    }

    boolean sameCommitment(AttackWavePlan other) {
        return planId.equals(other.planId) && targetId.equals(other.targetId) && waveId.equals(other.waveId)
            && formationGeneration == other.formationGeneration && memberIds.equals(other.memberIds)
            && reservedLaserSlots.equals(other.reservedLaserSlots)
            && reservedLaserSlotCount == other.reservedLaserSlotCount && activeWeapon == other.activeWeapon;
    }
}
