package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierExpansionLedgerTest {
    @Test void staleSecondPlacementCannotDuplicateOneInventory() {
        var ledger = new CarrierExpansionLedger();
        UUID owner = UUID.randomUUID();
        var item = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO);
        var placed = item.place("minecraft:overworld", 100L);
        assertTrue(ledger.transition(item.id(), CarrierExpansionPolicy.Location.ITEM, placed));
        assertFalse(ledger.transition(item.id(), CarrierExpansionPolicy.Location.ITEM,
            item.place("minecraft:overworld", 200L)));
        assertEquals(100L, ledger.get(item.id()).position());
    }

    @Test void hardpointCannotContainTwoModules() {
        var ledger = new CarrierExpansionLedger();
        UUID owner = UUID.randomUUID(), carrier = UUID.randomUUID();
        var first = ledger.create(owner, CarrierExpansionPolicy.Type.CARGO);
        assertTrue(ledger.hardpointAvailable(carrier, 2, first.id()));
        assertTrue(ledger.transition(first.id(), CarrierExpansionPolicy.Location.ITEM, first.install(carrier, 2)));
        var second = ledger.create(owner, CarrierExpansionPolicy.Type.MISSILE_BATTERY);
        assertFalse(ledger.hardpointAvailable(carrier, 2, second.id()));
    }
}
