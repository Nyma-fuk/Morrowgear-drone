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
            "carrier_laser_fire", "carrier_laser_hit", "carrier_laser_cooldown"}) {
            assertTrue(registry.contains("registerSound(\"" + id + "\""), id);
            assertTrue(sounds.contains("\"" + id + "\""), id);
        }
        assertTrue(controller.contains("carrier.position().distanceToSqr(listener)"));
        assertTrue(controller.contains("carrier.beamPath().target()"));
        assertTrue(controller.contains("carrier.beamPath().origin()"));
        assertFalse(controller.contains("SoundSource.MASTER"));
    }

    @Test void beamHitSequenceAdvancesOnlyAtMiningCommitOrCombatPulse() throws Exception {
        String entity = source("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java");
        assertTrue(entity.contains("EntityDataAccessor<Integer> BEAM_SEQUENCE"));
        assertTrue(entity.contains("void recordBeam(Vec3 target)"));
        assertTrue(entity.contains("advanceBeamSequence();"));
        assertTrue(entity.contains("phaseTick(ship.combatRemaining) % CarrierPolicy.COMBAT_PULSE_TICKS == 0"));
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
        assertTrue(renderer.contains("MorrowgearRenderTypes.energyBeam()"));
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
