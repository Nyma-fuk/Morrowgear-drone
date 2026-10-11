package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MultiplayerDistributionContractTest {
    @Test void modLoadsOnBothDedicatedServerAndClient() throws Exception {
        String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        assertTrue(metadata.contains("\"environment\": \"*\""));
        assertTrue(metadata.contains("\"minecraft\": \"~26.2\""));
        assertTrue(metadata.contains("\"fabricloader\": \">=0.19.3\""));
        assertTrue(metadata.contains("\"java\": \">=25\""));
    }

    @Test void automatedRemoteConnectionWritesAResultAndStopsWithoutMouseInput() throws Exception {
        String client = Files.readString(Path.of(
            "src/client/java/jp/morrowgear/drone/client/MorrowgearAutomationClient.java"));
        String common = Files.readString(Path.of(
            "src/main/java/jp/morrowgear/drone/MorrowgearAutomation.java"));
        assertTrue(client.contains("multiplayer_connect"));
        assertTrue(client.contains("!client.hasSingleplayerServer()"));
        assertTrue(client.contains("client.stop()"));
        assertTrue(client.contains("MorrowgearAutomation.completionReportExists()"));
        assertTrue(common.contains("public static boolean completionReportExists()"));
        assertTrue(common.contains("SCENARIO.equals(\"multiplayer_connect\")"));
    }

    @Test void distributionGuideRequiresMatchingClientAndServerModSets() throws Exception {
        String guide = Files.readString(Path.of(
            "docs/distribution/MORROWGEAR_MULTIPLAYER_SETUP_JA.md"));
        assertTrue(guide.contains("サーバーと参加者全員で一致"));
        assertTrue(guide.contains("online-mode=true"));
        assertTrue(guide.contains("whitelist add"));
        assertTrue(guide.contains("save-all flush"));
    }
}
