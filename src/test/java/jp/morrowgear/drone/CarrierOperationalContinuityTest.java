package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CarrierOperationalContinuityTest {
    private static final Path ENTITY = Path.of(
        "src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java");

    @Test
    void acceptedJourneyIsNotCancelledByOwnerRangeOrDeckOccupants() throws Exception {
        String source = Files.readString(ENTITY);
        assertTrue(source.contains("if (owner == null)"),
            "an accepted autonomous journey must only require its owner to remain online");
        assertFalse(source.contains("owner == null || !controls(owner)"),
            "command range must not become an in-flight leash");
        assertFalse(source.contains("rotationOccupantsClear"),
            "the carrier's own deck occupants must not block a turn");
        assertTrue(source.contains("turning && !ship.bay.clearForTurn(this)"),
            "service-bay hardware remains the turning safety interlock");
    }
}
