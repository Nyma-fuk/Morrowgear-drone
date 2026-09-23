package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OperationIntegrationContractTest {
    @Test void observingCoordinatorDoesNotCallMutatingRosterOrControlApis() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/OperationCommunicationsServer.java"));
        assertFalse(source.contains("ownedDrones("));
        assertFalse(source.contains("getAllEntities("));
        for (String mutation : new String[] {".assignGroup(", ".setMode(", ".assignWaypoint(", ".initializeOwner(", ".isOwnedBy(sender)"}) {
            assertFalse(source.contains(mutation), mutation);
        }
        assertTrue(source.contains("authorize(context.player(), payload)"));
        assertTrue(source.contains(".isOwnedBy(sender.getUUID())"));
        assertTrue(source.contains("ServerEntityEvents.ENTITY_LOAD"));
        assertTrue(source.contains("session.id.equals(request.session())"));
        assertTrue(source.contains("OperationShotPolicy.confirmed(drone.combatWeapon()"));
        assertTrue(source.contains("drone.combatShotTick(), drone.combatStateTick()"));
        assertFalse(source.contains("case GUN_RUN -> states.add"));
        assertFalse(source.contains("case MISSILE_APPROACH, MISSILE_EGRESS -> states.add"));
    }

    @Test void nativeVoiceIsParentOwnedAndLimitedToThreeSeconds() throws Exception {
        String source = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/OperationCommunicationsClient.java"));
        assertTrue(source.contains("MAX_VOICE_MS = 3_000"));
        assertTrue(source.contains("boolean play(VoiceCue cue)"));
        assertTrue(source.contains("void stop()"));
        assertFalse(source.contains("getNarrator("));
        assertFalse(source.contains("playSound("));
    }
}
