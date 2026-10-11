package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.network.FleetLinkPayload;
import org.junit.jupiter.api.Test;

final class FleetLinkPayloadTest {
	@Test void roundTripsRemoteUnitsAndRelayPathMetadata() {
		UUID owner = UUID.randomUUID(), unitId = UUID.randomUUID(), relayId = UUID.randomUUID();
		var payload = new FleetLinkPayload(owner, 7L, 91L, "minecraft:overworld", false,
			List.of(new FleetLinkPayload.Unit(42, unitId, "MG-042", "KESTREL", "WING-2",
				"engineer", 5, 640.5, 92.0, -18.5, 73, false,
				"FORESTRY / SCANNING", "TIMBER-4", 3, 82, relayId)),
			List.of(new FleetLinkPayload.Relay(relayId, 512.0, 100.0, 0.0, 64, 2, 82,
				"DYNAMIC", 520.0, 112.0, 0.0, true, null)),
			new FleetLinkPayload.Mesh("STABLE", 1, 1, 0, 0, 1, 0,
				384.0, 648.0, -128.0, 128.0));
		var buffer = Unpooled.buffer();
		try {
			FleetLinkPayload.CODEC.encode(buffer, payload);
			FleetLinkPayload decoded = FleetLinkPayload.CODEC.decode(buffer);
			assertEquals(payload, decoded);
			assertEquals(relayId, decoded.units().getFirst().relayId());
		} finally { buffer.release(); }
	}
}
