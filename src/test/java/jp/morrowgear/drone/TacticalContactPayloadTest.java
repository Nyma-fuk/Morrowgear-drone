package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.network.TacticalContactPayload;
import org.junit.jupiter.api.Test;

final class TacticalContactPayloadTest {
    @Test void ownerScopedContactSnapshotRoundTrips() {
        var payload = new TacticalContactPayload(UUID.randomUUID(), 12, 500, "minecraft:overworld", false,
            List.of(new TacticalContactPayload.Row(71, 10.5, 64, -8.5, 80, 75, 30,
                "SCOUT-WING", "PATROL-1", 496)));
        var buffer = Unpooled.buffer();
        try {
            TacticalContactPayload.CODEC.encode(buffer, payload);
            assertEquals(payload, TacticalContactPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void requestRateIsBounded() {
        assertTrue(TacticalContactPolicy.mayRequest(100, null));
        assertFalse(TacticalContactPolicy.mayRequest(109, 100L));
        assertTrue(TacticalContactPolicy.mayRequest(110, 100L));
    }
}
