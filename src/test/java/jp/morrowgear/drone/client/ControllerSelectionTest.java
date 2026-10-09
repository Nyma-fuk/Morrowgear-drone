package jp.morrowgear.drone.client;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ControllerSelectionTest {
    private final UUID alpha = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID beta = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void reloadResolvesSameUuidWithoutSelectingReusedId() {
        var selection = new ControllerSelection();
        selection.receive(Map.of(10, alpha));
        selection.add(10);
        selection.receive(Map.of(10, beta, 20, alpha));
        assertFalse(selection.contains(10));
        assertTrue(selection.contains(20));
        assertEquals(Set.of(20), Set.copyOf(selection));
    }

    @Test void missingUnitsRemainRememberedButCannotReceiveCommands() {
        var selection = new ControllerSelection();
        selection.receive(Map.of(1, alpha));
        selection.add(1);
        selection.receive(Map.of());
        assertTrue(selection.isEmpty());
        assertEquals(1, selection.unavailableCount());
        selection.receive(Map.of(4, alpha));
        assertEquals(Set.of(4), Set.copyOf(selection));
        assertEquals(0, selection.unavailableCount());
    }

    @Test void newReceptionNeverExpandsSelection() {
        var selection = new ControllerSelection();
        selection.receive(Map.of(1, alpha));
        selection.addAll(Set.of(1));
        selection.receive(Map.of(1, alpha, 2, beta));
        assertEquals(Set.of(1), Set.copyOf(selection));
        assertFalse(selection.add(999));
    }

    @Test void explicitClearAlsoClearsUnavailableIdentities() {
        var selection = new ControllerSelection();
        selection.receive(Map.of(1, alpha));
        selection.add(1);
        selection.receive(Map.of());
        selection.clear();
        selection.receive(Map.of(4, alpha));
        assertTrue(selection.isEmpty());
        assertEquals(0, selection.unavailableCount());
    }

    @Test void iteratorRemovalAndToggleKeepOtherSelection() {
        var selection = new ControllerSelection();
        selection.receive(Map.of(1, alpha, 2, beta));
        selection.addAll(Set.of(1, 2));
        assertTrue(selection.remove(1));
        assertEquals(Set.of(2), Set.copyOf(selection));
        var iterator = selection.iterator();
        assertEquals(2, iterator.next());
        iterator.remove();
        assertTrue(selection.isEmpty());
        assertThrows(IllegalStateException.class, iterator::remove);
    }

    /** Standalone runner avoids compiling unrelated work during parallel integration. */
    public static void main(String[] args) {
        var tests = new ControllerSelectionTest();
        tests.reloadResolvesSameUuidWithoutSelectingReusedId();
        tests.missingUnitsRemainRememberedButCannotReceiveCommands();
        tests.newReceptionNeverExpandsSelection();
        tests.explicitClearAlsoClearsUnavailableIdentities();
        tests.iteratorRemovalAndToggleKeepOtherSelection();
        System.out.println("ControllerSelection: 5 tests passed");
    }
}
