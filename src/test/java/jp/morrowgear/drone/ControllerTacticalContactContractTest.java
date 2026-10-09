package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class ControllerTacticalContactContractTest {
    @Test void controllerUsesAuthenticatedDataLinkInsteadOfEnumeratingLocalMobs() throws Exception {
        String dashboard = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/TacticalDashboard.java"));
        String client = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/TacticalContactClient.java"));
        assertTrue(dashboard.contains("TacticalContactClient.contacts(client)"));
        assertFalse(dashboard.contains("getEntitiesOfClass(LivingEntity.class"));
        assertTrue(client.contains("payload.owner().equals(context.client().player.getUUID())"));
        assertTrue(dashboard.contains("issueAttack(target.entityId())"));
    }
}
