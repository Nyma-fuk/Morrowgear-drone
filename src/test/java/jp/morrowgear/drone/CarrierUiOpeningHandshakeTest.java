package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class CarrierUiOpeningHandshakeTest {
    @Test void openingSnapshotAdoptsLiveMenuAndEnablesOwnerControls() {
        UUID ship = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);

        policy.accept(snapshot(-1, ship, generation, true, 100), 1_000);

        assertTrue(policy.cargoAccessible(1_100));
        assertTrue(policy.queueMove(new CarrierUiPolicy.Destination("minecraft:overworld", 160, 120, 160), 1_100));
    }

    @Test void bootstrapExceptionNeverAdoptsAnotherShipOrLaterStaleMenu() {
        UUID ship = UUID.randomUUID();
        UUID generation = UUID.randomUUID();
        CarrierUiPolicy wrongShip = new CarrierUiPolicy(17, ship);
        wrongShip.accept(snapshot(-1, UUID.randomUUID(), generation, true, 100), 1_000);
        assertFalse(wrongShip.cargoAccessible(1_100));

        CarrierUiPolicy staleMenu = new CarrierUiPolicy(17, ship);
        staleMenu.accept(snapshot(17, ship, generation, true, 100), 1_000);
        staleMenu.accept(snapshot(-1, ship, generation, true, 110), 1_100);
        assertTrue(staleMenu.cargoAccessible(1_200));
        assertFalse(staleMenu.queueMove(new CarrierUiPolicy.Destination("minecraft:the_nether", 0, 80, 0), 1_200));
    }

    @Test void moveNeedsMatchingAuthoritativeDestinationAcknowledgement() {
        UUID ship = UUID.randomUUID(), generation = UUID.randomUUID();
        var target = new CarrierUiPolicy.Destination("minecraft:overworld", 160, 120, 160);
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, generation, true, 100), 1_000);
        assertTrue(policy.queueMove(target, 1_010));
        policy.accept(snapshot(17, ship, generation, true, 106), 1_300);
        assertNotNull(policy.pollCommand(1_300));

        var wrong = new CarrierUiPolicy.Destination("minecraft:overworld", 176, 120, 160);
        policy.accept(snapshot(17, ship, generation, true, 107, true,
            new CarrierUiPolicy.Navigation(wrong, false, 1)), 1_350);
        assertEquals(CarrierUiPolicy.Phase.WAITING_MOVE, policy.phase());
        assertEquals(CarrierUiPolicy.MoveResult.PENDING, policy.moveResult());

        policy.accept(snapshot(17, ship, generation, true, 108, true,
            new CarrierUiPolicy.Navigation(target, false, 1)), 1_400);
        assertEquals(CarrierUiPolicy.MoveResult.ACCEPTED, policy.moveResult());
        assertFalse(policy.busy());
    }

    @Test void timedOutMoveCannotBeRelabeledByALateMatchingSnapshot() {
        UUID ship = UUID.randomUUID(), generation = UUID.randomUUID();
        var target = new CarrierUiPolicy.Destination("minecraft:overworld", 160, 120, 160);
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, generation, true, 100), 1_000);
        assertTrue(policy.queueMove(target, 1_010));
        policy.accept(snapshot(17, ship, generation, true, 106), 1_300);
        assertNotNull(policy.pollCommand(1_300));
        policy.tick(3_801);
        assertEquals(CarrierUiPolicy.MoveResult.UNCONFIRMED, policy.moveResult());
        assertTrue(policy.reopenRequired());

        policy.accept(snapshot(17, ship, generation, true, 120, true,
            new CarrierUiPolicy.Navigation(target, false, 1)), 3_850);
        assertFalse(policy.moveResult() == CarrierUiPolicy.MoveResult.ACCEPTED);
        assertTrue(policy.reopenRequired());
    }

    @Test void authoritativeRevisionStopsRepeatedMoveWithoutWaitingForTimeout() {
        UUID ship = UUID.randomUUID(), generation = UUID.randomUUID();
        var target = new CarrierUiPolicy.Destination("minecraft:overworld", 160, 120, 160);
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, generation, true, 100, false,
            new CarrierUiPolicy.Navigation(target, true, 10, 4)), 1_000);
        assertTrue(policy.queueMove(target, 1_010));
        policy.accept(snapshot(17, ship, generation, true, 106, false,
            new CarrierUiPolicy.Navigation(target, true, 10, 4)), 1_300);
        assertNotNull(policy.pollCommand(1_300));

        policy.accept(snapshot(17, ship, generation, true, 107, false,
            new CarrierUiPolicy.Navigation(target, true, 10, 5)), 1_350);

        assertEquals(CarrierUiPolicy.MoveResult.STOPPED, policy.moveResult());
        assertFalse(policy.busy());
        assertFalse(policy.reopenRequired());
    }

    @Test void rejectedAttackPreviewIsAcknowledgedWithoutReopeningMenu() {
        UUID ship = UUID.randomUUID(), generation = UUID.randomUUID();
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, generation, true, 100, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 7)), 1_000);
        policy.select(0, 0, "minecraft:overworld");
        assertTrue(policy.queuePreview(2, false, 1_010));
        policy.accept(snapshot(17, ship, generation, true, 106, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 7)), 1_300);
        assertNotNull(policy.pollCommand(1_300));

        policy.accept(snapshot(17, ship, generation, true, 107, false,
            new CarrierUiPolicy.Navigation(null, false, 20, 8)), 1_350);

        assertFalse(policy.busy());
        assertTrue(policy.expired());
        assertFalse(policy.reopenRequired());
    }

    @Test void rejectedAttackActivationIsAcknowledgedWithoutReopeningMenu() {
        UUID ship = UUID.randomUUID(), first = UUID.randomUUID(), preview = UUID.randomUUID();
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, first, true, 100, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 2)), 1_000);
        policy.select(0, 0, "minecraft:overworld");
        assertTrue(policy.queuePreview(2, false, 1_010));
        policy.accept(snapshot(17, ship, first, true, 106, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 2)), 1_300);
        assertNotNull(policy.pollCommand(1_300));
        policy.accept(previewSnapshot(17, ship, preview, 107, 100,
            new CarrierUiPolicy.Navigation(null, false, 1, 3)), 1_350);
        policy.consent(true);
        assertTrue(policy.queueStart(1_360));
        policy.accept(previewSnapshot(17, ship, preview, 113, 94,
            new CarrierUiPolicy.Navigation(null, false, 1, 3)), 1_700);
        assertNotNull(policy.pollCommand(1_700));

        policy.accept(previewSnapshot(17, ship, preview, 114, 93,
            new CarrierUiPolicy.Navigation(null, false, 13, 4)), 1_750);

        assertFalse(policy.busy());
        assertTrue(policy.expired());
        assertFalse(policy.reopenRequired());
    }

    @Test void expiredAttackConfirmationKeepsSelectionAndRequiresANewPreviewGeneration() {
        UUID ship = UUID.randomUUID(), initial = UUID.randomUUID(), preview = UUID.randomUUID(), renewed = UUID.randomUUID();
        CarrierUiPolicy policy = new CarrierUiPolicy(17, ship);
        policy.accept(snapshot(17, ship, initial, true, 100, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 1)), 1_000);
        policy.select(0, 0, "minecraft:overworld");
        assertTrue(policy.queuePreview(2, false, 1_010));
        policy.accept(snapshot(17, ship, initial, true, 106, false,
            new CarrierUiPolicy.Navigation(null, false, 0, 1)), 1_300);
        assertNotNull(policy.pollCommand(1_300));
        policy.accept(previewSnapshot(17, ship, preview, 107, 600,
            new CarrierUiPolicy.Navigation(null, false, 1, 2)), 1_350);
        assertEquals(CarrierUiPolicy.Phase.CONFIRM, policy.phase());
        policy.consent(true);
        assertTrue(policy.canStart(1_360));

        policy.accept(previewSnapshot(17, ship, preview, 707, 0,
            new CarrierUiPolicy.Navigation(null, false, 1, 2)), 31_350);

        assertEquals(CarrierUiPolicy.Phase.EXPIRED_CONFIRM, policy.phase());
        assertTrue(policy.selected());
        assertTrue(policy.expired());
        assertFalse(policy.canStart(31_350));
        assertTrue(policy.queuePreview(2, false, 31_360));
        assertNotNull(policy.pollCommand(31_360));
        policy.accept(previewSnapshot(17, ship, renewed, 714, 600,
            new CarrierUiPolicy.Navigation(null, false, 1, 3)), 31_400);
        assertEquals(CarrierUiPolicy.Phase.CONFIRM, policy.phase());
        assertEquals(renewed, policy.approved().generation());
        assertFalse(policy.consent());
    }

    private static CarrierUiPolicy.Snapshot snapshot(int menu, UUID ship, UUID generation, boolean owner, int tick) {
        return snapshot(menu, ship, generation, owner, tick, false, CarrierUiPolicy.Navigation.EMPTY);
    }

    private static CarrierUiPolicy.Snapshot snapshot(int menu, UUID ship, UUID generation, boolean owner, int tick,
                                                     boolean moving, CarrierUiPolicy.Navigation navigation) {
        return new CarrierUiPolicy.Snapshot(ship, owner, false, 0, moving, generation,
            "minecraft:overworld", 0, 0, -64, 320, 0, 0, menu, tick,
            navigation);
    }

    private static CarrierUiPolicy.Snapshot previewSnapshot(int menu, UUID ship, UUID generation, int tick,
                                                             int previewTicks, CarrierUiPolicy.Navigation navigation) {
        return new CarrierUiPolicy.Snapshot(ship, true, false, 0, false, generation,
            "minecraft:overworld", 0, 0, -64, 320, 2, previewTicks, menu, tick, navigation);
    }
}
