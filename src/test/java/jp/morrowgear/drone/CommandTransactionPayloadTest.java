package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import jp.morrowgear.drone.network.DroneCommandPayload;
import jp.morrowgear.drone.network.DroneCommandResultPayload;
import org.junit.jupiter.api.Test;

final class CommandTransactionPayloadTest {
    @Test void requestRoundTripPreservesSharedCommandIdentity() {
        UUID command = UUID.randomUUID();
        var first = new DroneCommandPayload(command, 12, "dock");
        var second = new DroneCommandPayload(command, 13, "dock");
        var buffer = Unpooled.buffer();
        try {
            DroneCommandPayload.CODEC.encode(buffer, first);
            DroneCommandPayload.CODEC.encode(buffer, second);
            assertEquals(first, DroneCommandPayload.CODEC.decode(buffer));
            assertEquals(second, DroneCommandPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void resultRoundTripCarriesPerUnitStatusAndReason() {
        var result = new DroneCommandResultPayload(UUID.randomUUID(), UUID.randomUUID(), 8,
            DroneCommandResultPayload.Status.HELD, "cargo_source:42", "補給元枯渇 / 再確認待ち", 900L);
        var buffer = Unpooled.buffer();
        try {
            DroneCommandResultPayload.CODEC.encode(buffer, result);
            assertEquals(result, DroneCommandResultPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
}
