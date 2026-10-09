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

	@Test
	void ownerDatalinkCanCompleteTheOperationHandshakeAfterLongRangeTravel() throws Exception {
		String entity = Files.readString(ENTITY);
		assertFalse(entity.contains("return \"OUT_OF_RANGE_\""),
			"the owner must be able to send preview/start after the carrier reaches a remote target");
		String menu = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierMenu.java"));
		assertTrue(menu.contains("ship.owner.equals(player.getUUID()) || entity.distanceToSqr(player) <= 128 * 128"));
		String screen = Files.readString(Path.of("src/client/java/jp/morrowgear/drone/client/CarrierScreen.java"));
		assertTrue(screen.contains("continueOperationAfterMove()"));
		assertTrue(screen.contains("採掘位置へ移動して範囲確認"));
		assertTrue(screen.contains("confirmation.queuePreview(mode, false, now())"));
		assertTrue(screen.contains("Action.MOVE_MINING"));
		String ship = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/carrier/CarrierShip.java"));
		assertTrue(ship.contains("arrivalOperation"));
		assertTrue(ship.contains("Reads legacy saves"));
	}
}
