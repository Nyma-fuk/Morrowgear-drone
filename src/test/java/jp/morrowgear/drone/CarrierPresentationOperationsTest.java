package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierPresentationOperationsTest {
    private static String source(String path) throws Exception { return Files.readString(Path.of(path)); }

    @Test void carrierAudioIsRegisteredTickedAndEntityLocal() throws Exception {
        String bootstrap = source("src/client/java/jp/morrowgear/drone/client/MorrowgearDroneClient.java");
        String controller = source("src/client/java/jp/morrowgear/drone/client/CarrierAudioController.java");
        String registry = source("src/main/java/jp/morrowgear/drone/MorrowgearDrone.java");
        String sounds = source("src/main/resources/assets/morrowgear_drone/sounds.json");
        assertTrue(bootstrap.contains("CarrierAudioController.register()"));
        for (String id : new String[]{"carrier_flight_idle", "carrier_flight_cruise", "carrier_laser_charge",
            "carrier_laser_fire", "carrier_laser_hit", "carrier_laser_cooldown",
            "carrier_cargo_hatch_unlock","carrier_cargo_hatch_move","carrier_winch_start",
            "carrier_winch_hoist","carrier_winch_lower","carrier_winch_brake","carrier_cable_tension",
            "carrier_container_rail","carrier_container_latch","carrier_cargo_hatch_seal",
            "carrier_container_ground","carrier_container_hydraulic","carrier_missile_hatch",
            "carrier_missile_eject","carrier_missile_ignition","carrier_missile_motor",
            "carrier_missile_penetration","carrier_missile_explosion"}) {
            assertTrue(registry.contains("registerSound(\"" + id + "\""), id);
            assertTrue(sounds.contains("\"" + id + "\""), id);
        }
        assertTrue(controller.contains("carrier.position().distanceToSqr(listener)"));
        assertTrue(controller.contains("carrier.beamPath().target()"));
        assertTrue(controller.contains("carrier.beamPath().origin()"));
        assertFalse(controller.contains("SoundSource.MASTER"));
    }

    @Test void physicalBayAndHeavyMissilePresentationAreNotDesignOnly() throws Exception {
        String renderer=source("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java");
        String missile=source("src/main/java/jp/morrowgear/drone/MorrowgearMissileEntity.java");
        String visor=source("src/client/java/jp/morrowgear/drone/client/VisorHudOverlay.java");
        assertTrue(renderer.contains("renderExpansionBay"));
        assertTrue(renderer.contains("renderFourPointSling"));
        assertTrue(renderer.contains("CONTAINER_YAW"));
        assertTrue(renderer.contains("renderHeavyMissileHatches"));
        assertTrue(renderer.contains("hingedHatch"));
        assertTrue(renderer.contains("HATCH_OPEN_ANGLE_DEGREES"));
        assertTrue(renderer.contains("missileSourceMask"));
        assertTrue(renderer.contains("missileBodies(slot)"));
        assertTrue(renderer.contains("missileWarheads(slot)"));
        assertTrue(missile.contains("launchCarrier"));
        assertTrue(missile.contains("tickHeavy"));
        assertTrue(missile.contains("CARRIER_MISSILE_PENETRATION_SOUND"));
        assertTrue(visor.contains("missile.heavy()"));
    }

    @Test void heavyMissileUsesAThinHighSpeedBodyInsteadOfTheFormerUniformScale() throws Exception {
        String renderer=source("src/client/java/jp/morrowgear/drone/client/MissileRenderer.java");
        assertTrue(renderer.contains("lengthScale=state.heavy?3.55:1.0"));
        assertTrue(renderer.contains("radiusScale=state.heavy?1.45:1.0"));
        assertFalse(renderer.contains("poseStack.scale(2.85"));
    }

    @Test void beamHitSequenceAdvancesOnlyAtMiningCommitOrCombatPulse() throws Exception {
        String entity = source("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java");
        assertTrue(entity.contains("EntityDataAccessor<Integer> BEAM_SEQUENCE"));
        assertTrue(entity.contains("void recordBeam(Vec3 target)"));
        assertTrue(entity.contains("advanceBeamSequence();"));
        assertTrue(entity.contains("phaseTick(ship.combatRemaining, ship.combatWeapon)"));
        assertTrue(entity.contains("sequence == Integer.MAX_VALUE ? 1 : sequence + 1"));
    }

    @Test void beamOcclusionAndPresentationUseOneAuthoritativeRay() throws Exception {
        String work = source("src/main/java/jp/morrowgear/drone/carrier/CarrierWork.java");
        String renderer = source("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java");
        assertTrue(work.contains("safeCombatAim(carrier, level, owner, op, target)"));
        assertTrue(work.contains("level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER"));
        assertTrue(work.contains(".getType() != HitResult.Type.MISS) return null"));
        assertTrue(renderer.contains("entity.beamPaths()"));
        assertTrue(renderer.contains("path.toRenderLocal(origin)"));
        assertTrue(renderer.contains("MorrowgearRenderTypes.condensedBeam()"));
        assertTrue(renderer.contains("MorrowgearRenderTypes.condensedCore()"));
        assertTrue(renderer.contains("MorrowgearRenderTypes.visibleEnergyCore()"));
        assertFalse(renderer.contains("NO_DEPTH"));
    }

    @Test void serviceBayStateAndUiExposeTheWholeApproachLifecycle() throws Exception {
        String bay = source("src/main/java/jp/morrowgear/drone/carrier/CarrierServiceBay.java");
        String payload = source("src/main/java/jp/morrowgear/drone/carrier/CarrierViewPayload.java");
        String screen = source("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java");
        for (String phase : new String[]{"HOLDING", "APPROACH", "DOCKING", "SERVICE"}) {
            assertTrue(bay.contains(phase));
            assertTrue(screen.contains("case " + phase));
        }
        assertTrue(payload.contains("ship.bay.phase(exterior, lease)"));
        assertTrue(screen.contains("待機点へ接近"));
        assertTrue(screen.contains("収容位置へ移動"));
        assertTrue(screen.contains("補給中"));
    }

    @Test void miningMapKeepsItsTargetAndUsesAQuietThreeByThreeSurveyOverlay() throws Exception {
        String screen = source("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java");
        assertTrue(screen.contains("CarrierMapUiPolicy.scanGrid"));
        assertTrue(screen.contains("CarrierMapUiPolicy.scannedChunks"));
        assertTrue(screen.contains("CarrierMapUiPolicy.persistentMiningFrame"));
        assertTrue(screen.contains("new String[]{\"走査\", \"充填\", \"下降\", \"解放\"}"));
        assertTrue(screen.contains("scanCorners"));
        assertTrue(screen.contains("scanned.contains(chunk) ? HmiArt.CYAN : HmiArt.LINE"));
    }

    @Test void miningNeverStopsForPlayersAlliesOrPassiveEntitiesInTheFootprint() throws Exception {
        String work = source("src/main/java/jp/morrowgear/drone/carrier/CarrierWork.java");
        String screen = source("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java");
        assertFalse(work.contains("ship.stop(CarrierPolicy.Stop.FRIENDLY_IN_AREA)"));
        assertTrue(work.contains("if (!hostile(target, owner)) continue;"));
        assertTrue(work.contains("ship.stop == CarrierPolicy.Stop.UNLOADED || ship.stop == CarrierPolicy.Stop.FRIENDLY_IN_AREA"),
            "legacy safety holds must clear without losing the accepted operation");
        assertTrue(screen.contains("対象チャンクを読み込み中 / 完了後に自動再開"));
        assertTrue(screen.contains("現任務: 対象チャンク読込待ち / 自動再開"));
    }

    @Test void navigationTrailRemainsBoundedVisibleAndWorldSpace() throws Exception {
        String policy = source("src/main/java/jp/morrowgear/drone/carrier/CarrierTrailPolicy.java");
        String renderer = source("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java");
        assertTrue(policy.contains("RANGE = 384"));
        assertTrue(policy.contains("MAX_TRACKED = 4"));
        assertTrue(renderer.contains("entityTranslucentEmissive(TRAIL_TEXTURE)"));
        assertTrue(renderer.contains("a.left() : a.right()).subtract(origin)"));
        assertTrue(renderer.contains("b.left() : b.right()).subtract(origin)"));
    }
}
