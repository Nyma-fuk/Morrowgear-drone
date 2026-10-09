package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static jp.morrowgear.drone.WeaponServicePlan.*;

class WeaponServicePlanTest {
    private static final Set<CombatWeapon> ALL = Set.of(CombatWeapon.LASER, CombatWeapon.AUTOCANNON, CombatWeapon.MISSILE);
    private static final Condition SAFE = new Condition(true, true, false, false, false, false, 10);
    private static Resource store(int value) { return new Resource(value, 1, 10); }
    private static Resources resources(int flight, int auxiliary, int laser, int gun, int missile) {
        return new Resources(new Resource(flight, 20, 100), store(auxiliary), store(laser), store(gun), store(missile));
    }
    private static Input input(Resources r, Condition c, SecurityLoadout doctrine, Set<CombatWeapon> temporary,
        Optional<CombatWeapon> only, boolean mission, boolean airRecovery, boolean persistent) {
        return new Input(r, c, new Armament(ALL, ALL, temporary, doctrine, only), mission, airRecovery, persistent);
    }
    private static Input normal(int laser, int gun, int missile) {
        return input(resources(100, 10, laser, gun, missile), SAFE, SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true);
    }

    @Test void allEightWeaponCombinationsAllowAnyEffectiveWeaponRegardlessOfPreference() {
        for (SecurityLoadout doctrine : new SecurityLoadout[]{SecurityLoadout.AUTO, SecurityLoadout.LASER,
            SecurityLoadout.MISSILE, SecurityLoadout.AUTOCANNON}) for (int mask = 0; mask < 8; mask++) {
            var plan = recompute(normal((mask & 1) == 0 ? 0 : 10, (mask & 2) == 0 ? 0 : 10, (mask & 4) == 0 ? 0 : 10), doctrine);
            assertEquals(Integer.bitCount(mask), plan.effectiveWeapons().size());
            assertEquals(mask != 0, plan.sortieReady());
            assertEquals(mask == 0, plan.returnRequired());
            assertEquals(mask == 0 ? Sortie.BLOCKED : mask == 7 ? Sortie.READY : Sortie.DEGRADED, plan.sortie());
        }
    }
    @Test void sevenNeedsRemainIndependentAndHeatIsNotANeed() {
        var condition = new Condition(true, true, false, true, true, false, 10);
        var plan = evaluate(input(resources(25, 1, 1, 1, 1), condition, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, true));
        assertEquals(Set.of(Need.values()), plan.needs());
        assertTrue(plan.sortieReady());
        assertEquals(Sortie.DEGRADED, plan.sortie());
        assertFalse(plan.returnRequired());
    }
    @Test void heatAloneBlocksNewLaserWaveButNeverRequestsRtbEvenIfLossWasReportedPersistent() {
        var plan = evaluate(input(resources(100, 10, 10, 0, 0), SAFE, SecurityLoadout.LASER,
            Set.of(CombatWeapon.LASER), Optional.empty(), true, false, true));
        assertFalse(plan.sortieReady());
        assertEquals(ReturnReason.NONE, plan.returnReason());
        assertFalse(plan.needs().contains(Need.LASER_ENERGY));
    }
    @Test void transientOcclusionOrChargeDoesNotBecomePersistentResourceLoss() {
        var plan = evaluate(input(resources(100, 10, 10, 10, 10), SAFE, SecurityLoadout.AUTO,
            ALL, Optional.empty(), true, false, true));
        assertEquals(Set.of(), plan.needs());
        assertFalse(plan.sortieReady());
        assertFalse(plan.returnRequired());
    }
    @Test void auxiliaryRecoveryIsAttemptedBeforeRtbButDoesNotClaimReady() {
        var r = resources(100, 0, 10, 10, 10);
        var recover = evaluate(input(r, SAFE, SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, true, true));
        var exhausted = evaluate(input(r, SAFE, SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true));
        assertEquals(Set.of(Need.AUXILIARY_POWER), recover.needs());
        assertTrue(recover.blockers().contains(Blocker.AUXILIARY_POWER));
        assertFalse(recover.sortieReady());
        assertFalse(recover.returnRequired());
        assertEquals(ReturnReason.WEAPONS_UNAVAILABLE, exhausted.returnReason());
    }
    @Test void absenceMustBePersistentBeforeWeaponRtb() {
        assertFalse(evaluate(input(resources(100, 10, 0, 0, 0), SAFE, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, false)).returnRequired());
        assertTrue(evaluate(normal(0, 0, 0)).returnRequired());
    }
    @Test void criticalDamageFlightReserveWeaponLossAndMaintenanceHaveExplicitPriority() {
        var all = resources(10, 0, 0, 0, 0);
        var damaged = new Condition(false, false, true, true, true, true, 10);
        assertEquals(ReturnReason.CRITICAL_DAMAGE, evaluate(input(all, damaged, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, true)).returnReason());
        assertEquals(ReturnReason.FLIGHT_RESERVE, evaluate(input(all, SAFE, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, true)).returnReason());
        var maintenance = new Condition(true, true, false, false, true, true, 10);
        assertEquals(ReturnReason.WEAPONS_UNAVAILABLE, evaluate(input(resources(100, 10, 0, 0, 0), maintenance,
            SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true)).returnReason());
        assertEquals(ReturnReason.PREVENTIVE_MAINTENANCE, evaluate(input(resources(100, 10, 10, 10, 10), maintenance,
            SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true)).returnReason());
    }
    @Test void sortieReserveIsStricterThanEmergencyRtbAndDoesNotRequireFullCharge() {
        var waiting = evaluate(input(resources(19, 10, 10, 10, 10), SAFE, SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true));
        assertFalse(waiting.sortieReady());
        assertFalse(waiting.returnRequired());
        assertTrue(evaluate(input(resources(20, 10, 10, 10, 10), SAFE, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, true)).sortieReady());
    }
    @Test void doctrineRecomputeNeverCarriesAStaleLaserWaitOrBlocker() {
        var initial = normal(0, 0, 0);
        assertFalse(recompute(initial, SecurityLoadout.LASER).sortieReady());
        var updated = normal(0, 10, 0);
        var plan = recompute(updated, SecurityLoadout.AUTOCANNON);
        assertTrue(plan.sortieReady());
        assertTrue(plan.needs().contains(Need.LASER_ENERGY));
        assertTrue(plan.blockers().isEmpty());
        assertEquals(Set.of(CombatWeapon.AUTOCANNON), plan.effectiveWeapons());
        assertEquals(plan, recompute(updated, SecurityLoadout.AUTOCANNON));
    }
    @Test void unarmedIsAProhibitionNotAnEndlessWeaponRefillRequest() {
        var plan = recompute(normal(10, 10, 10), SecurityLoadout.UNARMED);
        assertFalse(plan.sortieReady());
        assertFalse(plan.returnRequired());
        assertEquals(Set.of(), plan.needs());
        assertTrue(recompute(normal(10, 10, 10), SecurityLoadout.AUTO).sortieReady());
    }
    @Test void onlyConstraintIsNotRelaxedByChangingPreferredDoctrine() {
        var restricted = input(resources(100, 10, 0, 10, 10), SAFE, SecurityLoadout.LASER,
            Set.of(), Optional.of(CombatWeapon.LASER), true, false, true);
        assertFalse(recompute(restricted, SecurityLoadout.AUTOCANNON).sortieReady());
        assertTrue(recompute(restricted, SecurityLoadout.AUTOCANNON).returnRequired());
    }
    @Test void missionValidityAndActualEffectivenessAreRequired() {
        assertTrue(evaluate(input(resources(100, 10, 10, 10, 10), SAFE, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), false, false, true)).blockers().contains(Blocker.MISSION_INVALID));
        var laserMission = new Armament(ALL, Set.of(CombatWeapon.LASER), Set.of(), SecurityLoadout.AUTO, Optional.empty());
        assertFalse(evaluate(new Input(resources(100, 10, 0, 10, 10), SAFE, laserMission, true, false, true)).sortieReady());
    }
    @Test void uninstalledStoresDoNotCreateNeedsAndCollectionsAreDetached() {
        var installed = EnumSet.of(CombatWeapon.AUTOCANNON);
        var armament = new Armament(installed, installed, Set.of(), SecurityLoadout.LASER, Optional.empty());
        installed.clear();
        var plan = evaluate(new Input(resources(100, 10, 0, 10, 0), SAFE, armament, true, false, true));
        assertTrue(plan.needs().isEmpty());
        assertEquals(Set.of(CombatWeapon.AUTOCANNON), plan.effectiveWeapons());
        assertThrows(UnsupportedOperationException.class, () -> plan.effectiveWeapons().clear());
        assertThrows(UnsupportedOperationException.class, () -> plan.needs().add(Need.REPAIR));
    }
    @Test void invalidInputsAndUnsupportedWeaponsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Resource(-1, 1, 10));
        assertThrows(IllegalArgumentException.class, () -> new Resource(1, 5, 4));
        assertThrows(IllegalArgumentException.class, () -> new Armament(Set.of(CombatWeapon.RAM), ALL,
            Set.of(), SecurityLoadout.AUTO, Optional.empty()));
        var zero = new Resources(store(100), store(10), new Resource(0, 0, 0), new Resource(0, 0, 0), new Resource(0, 0, 0));
        assertFalse(evaluate(input(zero, SAFE, SecurityLoadout.AUTO, Set.of(), Optional.empty(), true, false, true)).sortieReady());
    }
    @Test void unsafePropulsionCannotUseDegradedSortie() {
        var unsafe = new Condition(true, false, false, false, true, false, 10);
        var plan = evaluate(input(resources(100, 10, 10, 10, 10), unsafe, SecurityLoadout.AUTO,
            Set.of(), Optional.empty(), true, false, true));
        assertFalse(plan.sortieReady());
        assertEquals(ReturnReason.FLIGHT_UNSAFE, plan.returnReason());
    }
}
