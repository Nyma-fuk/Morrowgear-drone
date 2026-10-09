package jp.morrowgear.drone;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Wiring checks only; physical extraction is verified by the in-game scenarios. */
class SalvageSafetyWiringTest {
    @Test void rescueDoesNotBypassBuildClaimsOrBreakEvents() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierProtection.java"));
        String rescue = source.substring(source.indexOf("boolean rescuePermitted"), source.indexOf("void install"));
        for (String gate : new String[]{"playerBuilt(", "guard.provenance", "guard.permits", "PlayerBlockBreakEvents.BEFORE",
            "state.hasBlockEntity()", "state.is(Blocks.BEDROCK)", "state.is(PROTECTED)", "getFluidState()"})
            assertTrue(rescue.contains(gate), gate);
    }
    @Test void destructiveFixturesRequireCreativeGameMaster() throws Exception {
        String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/MorrowgearRuntimeVerifier.java"));
        String root = source.substring(source.indexOf("Commands.literal(\"morrowgear_verify\")"), source.indexOf(".then(Commands.literal(\"all\")"));
        assertTrue(root.contains("player.isCreative()"));
        assertTrue(root.contains("Permissions.COMMANDS_GAMEMASTER"));
    }
}
