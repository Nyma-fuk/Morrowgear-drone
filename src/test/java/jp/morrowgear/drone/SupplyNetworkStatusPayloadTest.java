package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.network.SupplyNetworkStatusPayload;
import org.junit.jupiter.api.Test;

final class SupplyNetworkStatusPayloadTest {
    private static List<SupplyNetworkStatusPayload.Stock> stocks() {
        return SupplyNetworkPolicy.defaults().stream().map(rule ->
            new SupplyNetworkStatusPayload.Stock(rule, 4, 2, Math.max(0, rule.minimum() - 6))).toList();
    }
    @Test void roundTripIncludesActualStockReservedCargoAndShortageReason() {
        var job = new SupplyNetworkStatusPayload.Delivery(UUID.randomUUID(), Status.IN_TRANSIT, SupplyKind.LASER, 2);
        var dock = new SupplyNetworkStatusPayload.DockStatus(-23, 45, true, Status.SOURCE_SHORTAGE, stocks(), List.of(job));
        var packet = new SupplyNetworkStatusPayload(5, UUID.randomUUID(), "minecraft:overworld", List.of(dock));
        var buffer = Unpooled.buffer();
        try {
            SupplyNetworkStatusPayload.CODEC.encode(buffer, packet);
            assertEquals(packet, SupplyNetworkStatusPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
    }
    @Test void rejectsOversizedListsBeforeReadingTheirContents() {
        var packet = new SupplyNetworkStatusPayload(8, UUID.randomUUID(), "minecraft:overworld", List.of());
        var buffer = Unpooled.buffer();
        try {
            SupplyNetworkStatusPayload.CODEC.encode(buffer, packet);
            buffer.setByte(buffer.writerIndex() - 1, SupplyNetworkPolicy.MAX_ROUTES + 1);
            assertThrows(DecoderException.class, () -> SupplyNetworkStatusPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void rejectsDuplicateKindsInvalidStocksAndDeliveryQuantities() {
        var fuel = stocks().getFirst();
        assertThrows(IllegalArgumentException.class, () -> new SupplyNetworkStatusPayload.DockStatus(
            1, 2, true, Status.READY, List.of(fuel, fuel, fuel, fuel, fuel), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SupplyNetworkStatusPayload.Stock(fuel.rule(), -2, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SupplyNetworkStatusPayload.Stock(fuel.rule(), 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new SupplyNetworkStatusPayload.Delivery(
            UUID.randomUUID(), Status.LOAD_RETAINED, SupplyKind.MISSILE, 65));
    }
    @Test void unloadedInventoryIsUnknownRatherThanZero() {
        var rule = SupplyNetworkPolicy.defaults().getFirst();
        var unknown = new SupplyNetworkStatusPayload.Stock(rule, -1, 4, -1);
        assertEquals(-1, unknown.available());
        assertEquals(-1, unknown.missing());
        assertEquals(4, unknown.incoming());
    }
}
