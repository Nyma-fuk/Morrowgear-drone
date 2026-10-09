package jp.morrowgear.drone;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure service assessment for a Dock or a safe attack-wave boundary (spec sections 4 and 11).
 * This is not a command to interrupt a committed wave. All quantities and mission effectiveness
 * are supplied by the caller; preferred doctrine never fabricates an ammunition requirement.
 */
public record WeaponServicePlan(Set<Need> needs, Set<CombatWeapon> effectiveWeapons,
    Sortie sortie, Set<Blocker> blockers, ReturnReason returnReason, SecurityLoadout doctrine) {
    public enum Need { FLIGHT_POWER, AUXILIARY_POWER, LASER_ENERGY, GUN_AMMO, MISSILE_AMMO, REPAIR, SUBSYSTEM_MAINTENANCE }
    public enum Sortie { BLOCKED, READY, DEGRADED }
    public enum Blocker { AIRFRAME, PROPULSION, FLIGHT_RESERVE, AUXILIARY_POWER, NO_EFFECTIVE_WEAPON, MISSION_INVALID, MAINTENANCE_DUE }
    public enum ReturnReason { NONE, CRITICAL_DAMAGE, FLIGHT_UNSAFE, FLIGHT_RESERVE, WEAPONS_UNAVAILABLE, PREVENTIVE_MAINTENANCE }

    public WeaponServicePlan {
        needs = Set.copyOf(needs);
        effectiveWeapons = weapons(effectiveWeapons);
        blockers = Set.copyOf(blockers);
        Objects.requireNonNull(sortie);
        Objects.requireNonNull(returnReason);
        Objects.requireNonNull(doctrine);
    }
    public boolean sortieReady() { return sortie != Sortie.BLOCKED; }
    public boolean returnRequired() { return returnReason != ReturnReason.NONE; }

    /** Minimum is enough for the planned sortie/wave; target is the optional service goal. */
    public record Resource(int available, int minimum, int target) {
        public Resource {
            if (available < 0 || minimum < 0 || target < minimum)
                throw new IllegalArgumentException("Resource quantities must be nonnegative and target >= minimum");
        }
        public boolean sufficient() { return available >= minimum; }
        public boolean needsService() { return available < target; }
    }
    public record Resources(Resource flight, Resource auxiliary, Resource laser, Resource gun, Resource missile) {
        public Resources {
            Objects.requireNonNull(flight); Objects.requireNonNull(auxiliary); Objects.requireNonNull(laser);
            Objects.requireNonNull(gun); Objects.requireNonNull(missile);
        }
    }
    public record Condition(boolean airframeFlyable, boolean propulsionFlyable, boolean criticalDamage,
        boolean repairNeeded, boolean maintenanceNeeded, boolean maintenanceDue, int returnReserve) {
        public Condition {
            if (returnReserve < 0) throw new IllegalArgumentException("Negative return reserve");
        }
    }
    /** Temporary blockers include heat, charging and line-of-sight loss, not persistent depletion. */
    public record Armament(Set<CombatWeapon> installed, Set<CombatWeapon> effectiveForMission,
        Set<CombatWeapon> temporarilyBlocked, SecurityLoadout doctrine, Optional<CombatWeapon> only) {
        public Armament {
            installed = weapons(installed);
            effectiveForMission = weapons(effectiveForMission);
            temporarilyBlocked = weapons(temporarilyBlocked);
            Objects.requireNonNull(doctrine);
            Objects.requireNonNull(only);
            only.ifPresent(weapon -> weapons(Set.of(weapon)));
        }
        public Armament withDoctrine(SecurityLoadout next) {
            return new Armament(installed, effectiveForMission, temporarilyBlocked, next, only);
        }
    }
    /** Persistent loss is confirmed externally over time; a one-tick empty candidate list is not RTB. */
    public record Input(Resources resources, Condition condition, Armament armament,
        boolean missionValid, boolean auxiliaryAirRecoveryPossible, boolean persistentWeaponLoss) {
        public Input {
            Objects.requireNonNull(resources); Objects.requireNonNull(condition); Objects.requireNonNull(armament);
        }
    }

    public static WeaponServicePlan recompute(Input latest, SecurityLoadout newDoctrine) {
        Objects.requireNonNull(latest);
        return evaluate(new Input(latest.resources, latest.condition, latest.armament.withDoctrine(newDoctrine),
            latest.missionValid, latest.auxiliaryAirRecoveryPossible, latest.persistentWeaponLoss));
    }

    public static WeaponServicePlan evaluate(Input input) {
        Objects.requireNonNull(input);
        Resources r = input.resources;
        Condition c = input.condition;
        Armament a = input.armament;
        EnumSet<Need> needs = EnumSet.noneOf(Need.class);
        if (r.flight.needsService()) needs.add(Need.FLIGHT_POWER);
        if (r.auxiliary.needsService()) needs.add(Need.AUXILIARY_POWER);
        if (a.installed.contains(CombatWeapon.LASER) && r.laser.needsService()) needs.add(Need.LASER_ENERGY);
        if (a.installed.contains(CombatWeapon.AUTOCANNON) && r.gun.needsService()) needs.add(Need.GUN_AMMO);
        if (a.installed.contains(CombatWeapon.MISSILE) && r.missile.needsService()) needs.add(Need.MISSILE_AMMO);
        if (c.repairNeeded || c.criticalDamage || !c.airframeFlyable) needs.add(Need.REPAIR);
        if (c.maintenanceNeeded || c.maintenanceDue || !c.propulsionFlyable) needs.add(Need.SUBSYSTEM_MAINTENANCE);

        EnumSet<CombatWeapon> allowed = EnumSet.noneOf(CombatWeapon.class);
        allowed.addAll(a.installed);
        allowed.retainAll(a.effectiveForMission);
        if (a.doctrine == SecurityLoadout.UNARMED) allowed.clear();
        a.only.ifPresent(weapon -> allowed.retainAll(Set.of(weapon)));

        EnumSet<CombatWeapon> supplied = EnumSet.noneOf(CombatWeapon.class);
        for (CombatWeapon weapon : allowed) {
            Resource resource = switch (weapon) {
                case LASER -> r.laser;
                case AUTOCANNON -> r.gun;
                case MISSILE -> r.missile;
                default -> throw new IllegalStateException("Unsupported service weapon");
            };
            // A zero minimum is legitimate for an unused store, never an effective empty weapon.
            if (resource.available > 0 && resource.sufficient()) supplied.add(weapon);
        }
        EnumSet<CombatWeapon> effective = supplied.clone();
        effective.removeAll(a.temporarilyBlocked);
        if (!r.auxiliary.sufficient()) effective.clear();

        EnumSet<Blocker> blockers = EnumSet.noneOf(Blocker.class);
        if (!c.airframeFlyable || c.criticalDamage) blockers.add(Blocker.AIRFRAME);
        if (!c.propulsionFlyable) blockers.add(Blocker.PROPULSION);
        if (!r.flight.sufficient() || r.flight.available <= c.returnReserve) blockers.add(Blocker.FLIGHT_RESERVE);
        if (!r.auxiliary.sufficient()) blockers.add(Blocker.AUXILIARY_POWER);
        if (effective.isEmpty()) blockers.add(Blocker.NO_EFFECTIVE_WEAPON);
        if (!input.missionValid) blockers.add(Blocker.MISSION_INVALID);
        if (c.maintenanceDue) blockers.add(Blocker.MAINTENANCE_DUE);

        // Cooling/occlusion are deliberately absent: supplied weapons can recover in the air.
        boolean recoverableWeapons = !supplied.isEmpty()
            && (r.auxiliary.sufficient() || input.auxiliaryAirRecoveryPossible);
        ReturnReason reason = c.criticalDamage ? ReturnReason.CRITICAL_DAMAGE
            : !c.airframeFlyable || !c.propulsionFlyable ? ReturnReason.FLIGHT_UNSAFE
            : r.flight.available <= c.returnReserve ? ReturnReason.FLIGHT_RESERVE
            : a.doctrine != SecurityLoadout.UNARMED && input.persistentWeaponLoss && !recoverableWeapons
                ? ReturnReason.WEAPONS_UNAVAILABLE
            : c.maintenanceDue ? ReturnReason.PREVENTIVE_MAINTENANCE : ReturnReason.NONE;
        boolean degraded = !needs.isEmpty() || effective.size() < allowed.size();
        Sortie sortie = !blockers.isEmpty() ? Sortie.BLOCKED : degraded ? Sortie.DEGRADED : Sortie.READY;
        return new WeaponServicePlan(needs, effective, sortie, blockers, reason, a.doctrine);
    }

    private static Set<CombatWeapon> weapons(Set<CombatWeapon> source) {
        Set<CombatWeapon> copy = Set.copyOf(source);
        if (copy.stream().anyMatch(weapon -> weapon != CombatWeapon.LASER
            && weapon != CombatWeapon.AUTOCANNON && weapon != CombatWeapon.MISSILE))
            throw new IllegalArgumentException("Only laser, gun and missile belong to a service plan");
        return copy;
    }
}
