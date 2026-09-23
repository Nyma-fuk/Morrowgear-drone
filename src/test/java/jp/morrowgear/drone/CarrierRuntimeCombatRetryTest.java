package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CarrierRuntimeCombatRetryTest {
    private static final UUID TARGET = UUID.fromString("94a83720-5f12-4027-83ba-1f2efb56a7f1");
    private static final UUID LEGACY = UUID.fromString("3ff18a61-9ebf-4319-9b01-0c1fbcf37b04");

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var registries = net.minecraft.data.registries.VanillaRegistries.createLookup();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(registries)
            .forEach(initializer -> initializer.apply());
    }

    private static CarrierRuntimeVerification.CombatEvidence initial(float health) {
        return new CarrierRuntimeVerification.CombatEvidence(TARGET, health, 1000, 0, 0, false);
    }

    @Test void chargeConsumptionWithoutDamageNeverPasses() {
        var evidence = initial(200).sample(TARGET, true, false, false, 200, 890, false);
        assertFalse(evidence.complete(true, true));
        assertFalse(evidence.complete(false, false));
        assertEquals(0, evidence.damage());
        assertEquals(0, evidence.spent());
    }

    @Test void successfulDamageRequiresMatchingFireEnergyAndOwner() {
        var start = initial(200);
        assertThrows(IllegalStateException.class, () -> start.sample(TARGET, true, false, true, 180, 970, false));
        assertThrows(IllegalStateException.class, () -> start.sample(TARGET, true, true, false, 180, 970, false));
        assertThrows(IllegalStateException.class, () -> start.sample(TARGET, true, true, true, 180, 1010, false));
        assertThrows(IllegalStateException.class, () -> start.sample(TARGET, true, true, true, 180, 930, false));
        var hit = start.sample(TARGET, true, true, true, 180, 970, false);
        assertEquals(20, hit.damage());
        assertEquals(40, hit.spent());
        assertTrue(hit.complete(true, true));
    }

    @Test void liveDamageDoesNotProveAnUnobservedDisappearance() {
        var hit = initial(200).sample(TARGET, true, true, true, 180, 970, false);
        assertFalse(hit.complete(false, false));
        assertFalse(hit.complete(true, false));
        assertThrows(IllegalStateException.class, () -> hit.sample(TARGET, true, false, true, 180, 960, true));
    }

    @Test void observedKillSurvivesEntityRemovalAndCodecRoundTrip() {
        var killed = initial(20).sample(TARGET, true, true, true, 0, 970, true);
        assertTrue(killed.killed());
        assertTrue(killed.complete(false, false));
        var encoded = CarrierRuntimeVerification.CombatEvidence.CODEC.encodeStart(JsonOps.INSTANCE, killed).getOrThrow();
        var restored = CarrierRuntimeVerification.CombatEvidence.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(killed, restored);
        assertTrue(restored.complete(false, false));
    }

    @Test void removalWithRemainingHealthIsNotAKillEvenAfterAMatchingHit() {
        assertThrows(IllegalStateException.class, () -> initial(200).sample(TARGET, true, true, true, 180, 970, true));
        assertThrows(IllegalStateException.class, () -> initial(200).sample(TARGET, true, false, false, 200, -1, false));
    }

    @Test void earlyLethalDamageRemainsProvenAfterTheRestOfTheFullCycle() {
        var evidence = initial(200);
        int energy = 1000;
        float health = 200;
        boolean removed = false;
        for (int remaining = jp.morrowgear.drone.carrier.CarrierPolicy.COMBAT_TICKS; remaining > 0; remaining--) {
            energy = Math.min(jp.morrowgear.drone.carrier.CarrierPolicy.MAX_ENERGY,
                energy + jp.morrowgear.drone.carrier.CarrierPowerPolicy.WORK_GENERATION_PER_TICK);
            var phase = jp.morrowgear.drone.carrier.CarrierPolicy.combatPhase(remaining);
            boolean fire = phase == jp.morrowgear.drone.carrier.CarrierPolicy.WorkPhase.FIRE;
            if (phase == jp.morrowgear.drone.carrier.CarrierPolicy.WorkPhase.CHARGE) energy -= 2;
            boolean hit = fire && health > 0
                && jp.morrowgear.drone.carrier.CarrierPolicy.phaseTick(remaining)
                    % jp.morrowgear.drone.carrier.CarrierPolicy.COMBAT_PULSE_TICKS == 0;
            if (hit) {
                energy -= jp.morrowgear.drone.carrier.CarrierCombatPolicy.HIT_ENERGY;
                health = Math.max(0, health - jp.morrowgear.drone.carrier.CarrierCombatPolicy.DAMAGE);
            }
            if (!removed) {
                evidence = evidence.sample(TARGET, true, fire, hit, health, energy, health == 0);
                removed = health == 0;
            } else assertTrue(evidence.complete(false, false), "removed corpse must retain witnessed lethal damage");
        }
        assertEquals(200, evidence.damage());
        assertEquals(80, evidence.spent());
        assertTrue(evidence.killed());
    }

    @Test void stopOrReplacementCannotBeCreditedAsTheOriginalFullCycle() {
        UUID generation = UUID.randomUUID();
        var combat = jp.morrowgear.drone.carrier.CarrierPolicy.Mode.COMBAT;
        var idle = jp.morrowgear.drone.carrier.CarrierPolicy.Mode.IDLE;
        var running = jp.morrowgear.drone.carrier.CarrierPolicy.Stop.RUNNING;
        var complete = jp.morrowgear.drone.carrier.CarrierPolicy.Stop.COMPLETE;
        var emergency = jp.morrowgear.drone.carrier.CarrierPolicy.Stop.EMERGENCY;
        int end = jp.morrowgear.drone.carrier.CarrierPolicy.COMBAT_TICKS;
        assertTrue(CarrierRuntimeVerification.sameCombatCycle(generation, generation, end - 1, combat, running));
        assertFalse(CarrierRuntimeVerification.sameCombatCycle(generation, generation, end - 1, combat, emergency));
        assertFalse(CarrierRuntimeVerification.sameCombatCycle(generation, UUID.randomUUID(), end - 1, combat, running));
        assertFalse(CarrierRuntimeVerification.sameCombatCycle(generation, generation, end - 1, idle, complete));
        assertFalse(CarrierRuntimeVerification.sameCombatCycle(generation, generation, end, idle, emergency));
        assertFalse(CarrierRuntimeVerification.sameCombatCycle(generation, generation, end, combat, running));
        assertTrue(CarrierRuntimeVerification.sameCombatCycle(generation, new UUID(0, 0), end, idle, complete));
    }

    @Test void targetIdentityAndTagCannotBeSubstituted() {
        assertThrows(IllegalStateException.class, () -> initial(20).sample(UUID.randomUUID(), true, true, true, 0, 960, true));
        assertThrows(IllegalStateException.class, () -> initial(20).sample(TARGET, false, true, true, 0, 960, true));
        assertThrows(IllegalStateException.class, () -> initial(20).sample(TARGET, true, true, true, Float.NaN, 960, true));
        assertThrows(IllegalStateException.class, () -> initial(20).sample(TARGET, true, true, true, 21, 960, false));
    }

    @Test void collateralPreflightMatchesProductionCombatCylinderAndExcludesOnlyTheOwnedFixtureTarget() {
        UUID fixture = UUID.randomUUID(), unknown = UUID.randomUUID();
        double x = 8, z = 8, minY = -64, originY = 120;
        var inside = new net.minecraft.world.phys.Vec3(8, 70, 8);
        assertFalse(CarrierRuntimeVerification.competingCombatCandidate(fixture, fixture, true, inside, x, z, minY, originY));
        assertTrue(CarrierRuntimeVerification.competingCombatCandidate(unknown, fixture, true, inside, x, z, minY, originY));
        assertFalse(CarrierRuntimeVerification.competingCombatCandidate(unknown, fixture, false, inside, x, z, minY, originY));
        assertFalse(CarrierRuntimeVerification.competingCombatCandidate(unknown, fixture, true,
            new net.minecraft.world.phys.Vec3(105, 70, 8), x, z, minY, originY));
        assertFalse(CarrierRuntimeVerification.competingCombatCandidate(unknown, fixture, true,
            new net.minecraft.world.phys.Vec3(8, 121, 8), x, z, minY, originY));
    }

    @Test void retryCannotResumeOtherFailuresOrCreateAnotherRoster() {
        assertTrue(CarrierRuntimeVerification.retryableCombat("FAILED", "COMBAT: java.lang.IllegalStateException: old endpoint assertion", 0));
        assertFalse(CarrierRuntimeVerification.retryableCombat("READY", "COMBAT: done", 0));
        assertFalse(CarrierRuntimeVerification.retryableCombat("FAILED", "ARM_COMBAT: refused", 0));
        assertFalse(CarrierRuntimeVerification.retryableCombat("FAILED", "BAYS: timeout", 0));
        assertFalse(CarrierRuntimeVerification.retryableCombat("FAILED", "COMBAT: failure", 1));
    }

    @Test void legacyAbsenceRequiresThisAuthorizedRunAndExactConfirmation() {
        var empty = CarrierRuntimeVerification.CombatEvidence.EMPTY;
        assertTrue(CarrierRuntimeVerification.absentTargetRetry(LEGACY, LEGACY, TARGET, empty));
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(LEGACY, UUID.randomUUID(), TARGET, empty));
        UUID unknown = UUID.randomUUID();
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(unknown, unknown, TARGET, empty));
        var killed = initial(20).sample(TARGET, true, true, true, 0, 970, true);
        assertTrue(CarrierRuntimeVerification.absentTargetRetry(unknown, unknown, TARGET, killed));
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(unknown, unknown, UUID.randomUUID(), killed));
    }

    @Test void legacyMissingTargetExceptionIsConsumedOnceAndPersistsAcrossFreshTargets() {
        var used = CarrierRuntimeVerification.CombatEvidence.EMPTY.consumeLegacyRetry().begin(TARGET, 200, 1000);
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(LEGACY, LEGACY, TARGET, used));
        var encoded = CarrierRuntimeVerification.CombatEvidence.CODEC.encodeStart(JsonOps.INSTANCE, used).getOrThrow();
        var restored = CarrierRuntimeVerification.CombatEvidence.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(LEGACY, LEGACY, TARGET, restored));
        assertTrue(restored.begin(UUID.randomUUID(), 200, 1000).legacyRetryUsed());
        var unprovenDeath = new CarrierRuntimeVerification.CombatEvidence(TARGET, 0, 960, 0, 0, true);
        var removedAlive = new CarrierRuntimeVerification.CombatEvidence(TARGET, 180, 960, 20, 40, true);
        UUID ordinary = UUID.randomUUID();
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(ordinary, ordinary, TARGET, unprovenDeath));
        assertFalse(CarrierRuntimeVerification.absentTargetRetry(ordinary, ordinary, TARGET, removedAlive));
    }

    @Test void retryCargoRequiresExactlyEighteenPlainCobblestoneAndLeavesItemsUntouched() {
        var cargo = new SimpleContainer(4);
        cargo.setItem(0, new ItemStack(Items.COBBLESTONE, 18));
        ItemStack original = cargo.getItem(0).copy();
        assertTrue(CarrierRuntimeVerification.baselineCargo(cargo));
        assertTrue(ItemStack.matches(original, cargo.getItem(0)));
        cargo.setItem(1, new ItemStack(Items.DIAMOND));
        assertFalse(CarrierRuntimeVerification.baselineCargo(cargo));
        cargo.setItem(1, ItemStack.EMPTY);
        cargo.getItem(0).setCount(17);
        assertFalse(CarrierRuntimeVerification.baselineCargo(cargo));
        cargo.getItem(0).setCount(18);
        cargo.getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("player cargo"));
        assertFalse(CarrierRuntimeVerification.baselineCargo(cargo));
    }

    @Test void runtimeWiringObservesEveryTickAndRetryDoesNotResetCargoOrCarrier() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/CarrierRuntimeVerification.java"));
        int combat = source.indexOf("case COMBAT ->");
        int observe = source.indexOf("proof.combat = before.sample", combat);
        int endGuard = source.indexOf("if (elapsed < CarrierPolicy.COMBAT_TICKS + 5)", combat);
        assertTrue(combat >= 0 && observe > combat && observe < endGuard);
        assertTrue(source.contains("require(proof.combat.killed(), \"target disappeared without an observed verified death\")"));
        assertTrue(source.contains("optionalFieldOf(\"combat_evidence\", CombatEvidence.EMPTY)"));
        int retry = source.indexOf("private static int retryCombat(");
        String body = source.substring(retry, source.indexOf("/** Missing entities", retry));
        for (String forbidden : new String[] {"proof.reset(", "ship.cargo.setItem(", "ship.cargo.clearContent(", "carrier.discard(", "carrier.deploy(", "DRONE.create("})
            assertFalse(body.contains(forbidden), forbidden);
        assertTrue(body.contains("proof.run.equals(confirmedRun)"));
        assertTrue(source.contains("competingCombatCandidate(e.getUUID(), proof.target"));
        assertTrue(source.contains("CarrierCombatPolicy.inCylinder(eye, centerX, centerZ, minY, originY)"));
        assertTrue(source.contains("unknown hostile in carrier combat selection"));
        assertTrue(source.indexOf("require(sameCombatCycle", combat) < observe);
        int duplicate = source.indexOf("if (action.equals(\"retry_combat\") && active != null)");
        assertTrue(duplicate > 0 && duplicate < source.indexOf("case \"retry_combat\" ->"));
    }
}
