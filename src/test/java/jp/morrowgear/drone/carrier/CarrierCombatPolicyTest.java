package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.EnumMap;
import java.util.HashSet;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierCombatPolicyTest {
    @Test void oneBroadDischargeCycleDefeatsSixteenWardenHealthContacts() {
        for (int count : new int[] {1, 8, 16}) {
            float[] health = new float[count];
            java.util.Arrays.fill(health, 500);
            int energy = CarrierPolicy.MAX_ENERGY, cursor = 0;
            for (int remaining = CarrierPolicy.COMBAT_TICKS; remaining > 0; remaining--) {
                energy = Math.min(CarrierPolicy.MAX_ENERGY, energy + CarrierPowerPolicy.WORK_GENERATION_PER_TICK);
                var phase = CarrierPolicy.combatPhase(remaining);
                if (phase == CarrierPolicy.WorkPhase.CHARGE) energy -= CarrierCombatPolicy.CHARGE_ENERGY;
                if (phase != CarrierPolicy.WorkPhase.FIRE
                    || CarrierPolicy.phaseTick(remaining) % CarrierPolicy.COMBAT_PULSE_TICKS != 0) continue;
                var indices = CarrierCombatPolicy.rotation(count, cursor);
                cursor = (cursor + indices.size()) % count;
                assertTrue(energy >= CarrierCombatPolicy.HIT_ENERGY);
                for (int index : indices) {
                    health[index] -= CarrierCombatPolicy.DAMAGE;
                }
                if (!indices.isEmpty()) energy -= CarrierCombatPolicy.HIT_ENERGY;
            }
            for (float remaining : health) assertTrue(remaining <= 0, "A full cycle must cover every Warden-health contact");
        }
    }

    @Test void primarySelectionRanksThreatOnceThenRetainsItUntilInvalid() {
        var initial = java.util.List.of(
            new CarrierCombatPolicy.Threat<>("near-small", 40, 4),
            new CarrierCombatPolicy.Threat<>("far-large", 500, 900),
            new CarrierCombatPolicy.Threat<>("near-large", 500, 25));
        assertEquals("near-large", CarrierCombatPolicy.selectPrimary(initial).orElseThrow());
        var changed = java.util.List.of(
            new CarrierCombatPolicy.Threat<>("near-large", 500, 25),
            new CarrierCombatPolicy.Threat<>("new-larger", 1000, 1));
        assertEquals("near-large", CarrierCombatPolicy.retainOrSelectPrimary("near-large", changed).orElseThrow(),
            "A valid lock must not oscillate when a stronger contact appears");
        assertEquals("new-larger", CarrierCombatPolicy.retainOrSelectPrimary("near-large",
            java.util.List.of(changed.get(1))).orElseThrow(), "An invalid lock may be replaced");
    }

    @Test void chargeAimsConvergeGraduallyAndMeetPrimaryOnFinalTick() {
        Vec3 primary = new Vec3(8, 20, 8);
        var scan = java.util.List.of(new Vec3(0, 20, 0), new Vec3(16, 20, 16));
        var first = CarrierCombatPolicy.converge(scan, primary, 0, CarrierPolicy.COMBAT_CHARGE_TICKS);
        assertEquals(2, first.size());
        assertNotEquals(primary, first.getFirst());
        var last = CarrierCombatPolicy.converge(scan, primary,
            CarrierPolicy.COMBAT_CHARGE_TICKS - 1, CarrierPolicy.COMBAT_CHARGE_TICKS);
        assertEquals(java.util.List.of(primary, primary), last);
        assertEquals(java.util.List.of(primary), CarrierCombatPolicy.converge(
            java.util.List.of(), primary, 0, CarrierPolicy.COMBAT_CHARGE_TICKS));
    }

    @Test void broadImpactUsesExactEightBlockSphereAndBodyIntersection() {
        Vec3 center = new Vec3(10, 20, 30);
        assertEquals(8.0, CarrierPolicy.COMBAT_IMPACT_RADIUS, 0.0);
        assertTrue(CarrierCombatPolicy.inImpactArea(center.add(8, 0, 0), center));
        assertFalse(CarrierCombatPolicy.inImpactArea(center.add(8.001, 0, 0), center));
        assertTrue(CarrierCombatPolicy.intersectsImpactArea(new AABB(18, 19, 29, 19, 21, 31), center));
        assertFalse(CarrierCombatPolicy.intersectsImpactArea(new AABB(18.01, 19, 29, 19, 21, 31), center));
        assertEquals(16, CarrierCombatPolicy.rotation(40, 0).size(), "One area pulse is capped at sixteen recipients");
    }

    @Test void broadRenderedConeUsesConservativeEightBlockFriendlyCorridor() {
        Vec3 from = new Vec3(0, 100, 0), to = new Vec3(0, 0, 0);
        assertTrue(CarrierCombatPolicy.intersectsAreaBeam(from, to, AABB.ofSize(new Vec3(4, 50, 0), 1, 2, 1)));
        assertFalse(CarrierCombatPolicy.intersectsFriendly(from, to, AABB.ofSize(new Vec3(4, 50, 0), 1, 2, 1)),
            "The former pencil-beam guard did not cover the rendered cone");
        assertFalse(CarrierCombatPolicy.intersectsAreaBeam(from, to, AABB.ofSize(new Vec3(6, 50, 0), 1, 2, 1)));
    }
    @Test void scanThenThirtySecondCycleHasExactPhaseDurationsAndNormalImmunityCadence() {
        var counts = new EnumMap<CarrierPolicy.WorkPhase, Integer>(CarrierPolicy.WorkPhase.class);
        int pulses = 0;
        for (int remaining = CarrierPolicy.COMBAT_TICKS; remaining > 0; remaining--) {
            var phase = CarrierPolicy.combatPhase(remaining);
            counts.merge(phase, 1, Integer::sum);
            assertTrue(CarrierPolicy.phaseTick(remaining) < CarrierPolicy.phaseDuration(phase));
            if (phase == CarrierPolicy.WorkPhase.FIRE && CarrierPolicy.phaseTick(remaining) % CarrierPolicy.COMBAT_PULSE_TICKS == 0) pulses++;
        }
        assertEquals(40, counts.get(CarrierPolicy.WorkPhase.SCAN));
        assertEquals(60, counts.get(CarrierPolicy.WorkPhase.CHARGE));
        assertEquals(480, counts.get(CarrierPolicy.WorkPhase.FIRE));
        assertEquals(60, counts.get(CarrierPolicy.WorkPhase.COOLDOWN));
        assertEquals(48, pulses);
        assertEquals(10, CarrierPolicy.COMBAT_PULSE_TICKS);
        assertEquals(200, CarrierCombatPolicy.DAMAGE * 20 / CarrierPolicy.COMBAT_PULSE_TICKS);
        assertEquals(CarrierPolicy.WorkPhase.IDLE, CarrierPolicy.combatPhase(0));
        assertEquals(CarrierPolicy.WorkPhase.IDLE, CarrierPolicy.combatPhase(CarrierPolicy.COMBAT_TICKS + 1));
    }

    @Test void everyEnemyGetsATurnForSeventeenThrough128Targets() {
        for (int size = 17; size <= 128; size++) {
            int cursor = 0;
            var seen = new HashSet<Integer>();
            for (int pulse = 0; pulse < (size + 15) / 16; pulse++) {
                var chosen = CarrierCombatPolicy.rotation(size, cursor);
                assertEquals(16, chosen.size());
                assertEquals(16, new HashSet<>(chosen).size());
                seen.addAll(chosen);
                cursor = (cursor + chosen.size()) % size;
            }
            assertEquals(size, seen.size());
        }
        assertTrue(CarrierCombatPolicy.rotation(0, 10).isEmpty());
        assertEquals(java.util.List.of(0), CarrierCombatPolicy.rotation(1, Integer.MIN_VALUE));
    }

    @Test void friendsOnlyBlockTheirIntersectingBeamNotOtherEnemiesInTheSearchCylinder() {
        Vec3 from = new Vec3(8, 100, 8), left = new Vec3(-30, 60, 8), right = new Vec3(46, 60, 8);
        AABB friend = AABB.ofSize(from.lerp(left, .5), .6, 1.8, .6);
        assertTrue(CarrierCombatPolicy.intersectsFriendly(from, left, friend));
        assertFalse(CarrierCombatPolicy.intersectsFriendly(from, right, friend));
        assertFalse(CarrierCombatPolicy.intersectsFriendly(from, left, new AABB(0, 103, 0, 3, 104, 3)));
        assertTrue(CarrierCombatPolicy.intersectsFriendly(from, left, new AABB(7.8, 100.5, 7.8, 8.2, 102, 8.2)));
    }

    @Test void searchBoundsIncludeFriendsWhoseInflatedBodiesOnlyTouchAtTheTopBottomAndSides() {
        Vec3 from = new Vec3(8, 100, 8), to = new Vec3(8, -64, 8);
        AABB query = CarrierCombatPolicy.searchBounds(8, 8, -64, 100);
        for (AABB friend : java.util.List.of(new AABB(7.8, 100.5, 7.8, 8.2, 101, 8.2),
            new AABB(7.8, -65, 7.8, 8.2, -64.5, 8.2), new AABB(8.8, 0, 7.8, 9, 1, 8.2))) {
            assertTrue(CarrierCombatPolicy.intersectsFriendly(from, to, friend));
            assertTrue(query.intersects(friend));
        }
    }

    @Test void targetRadiusIsNinetySixAndTheBellyCannotFireUpwards() {
        assertTrue(CarrierCombatPolicy.inCylinder(new Vec3(104, 70, 8), 8, 8, -64, 100));
        assertFalse(CarrierCombatPolicy.inCylinder(new Vec3(104.01, 70, 8), 8, 8, -64, 100));
        assertFalse(CarrierCombatPolicy.inCylinder(new Vec3(8, 100, 8), 8, 8, -64, 100));
        assertFalse(CarrierCombatPolicy.inCylinder(new Vec3(8, -65, 8), 8, 8, -64, 100));
    }
    @Test void continuousBeamValidationCannotAcquireUnknownTargetsOrKeepUnsafeOnes() {
        var successful = java.util.List.of("confirmed-a", "confirmed-b", "confirmed-c");
        var safe = new HashSet<>(java.util.List.of("confirmed-a", "confirmed-b", "confirmed-c", "new-enemy"));
        var held = CarrierCombatPolicy.retainBeams(successful, safe::contains);
        assertEquals(successful, held);
        safe.remove("confirmed-b");
        held = CarrierCombatPolicy.retainBeams(held, safe::contains);
        assertEquals(java.util.List.of("confirmed-a", "confirmed-c"), held);
        safe.add("confirmed-b");
        assertEquals(held, CarrierCombatPolicy.retainBeams(held, safe::contains), "A removed beam needs another successful pulse");
        assertTrue(CarrierCombatPolicy.retainBeams(held, id -> false).isEmpty());
        assertFalse(held.contains("new-enemy"));
        assertEquals(16, CarrierCombatPolicy.retainBeams(java.util.stream.IntStream.range(0, 40).boxed().toList(), id -> true).size());
        assertThrows(UnsupportedOperationException.class, () -> successful.add("new-enemy"));
    }
}
