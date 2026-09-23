package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierCodecTest {
    @Test void allTypedActionsRoundTripWithoutAnyCommandText() {
        for (var action : CarrierCommandPayload.Action.values()) {
            var input = new CarrierCommandPayload(17, UUID.randomUUID(), action, UUID.randomUUID(), -17, -64, 200, UUID.randomUUID());
            var buffer = Unpooled.buffer();
            try {
                CarrierCommandPayload.CODEC.encode(buffer, input);
                assertEquals(65, buffer.readableBytes());
                assertEquals(input, CarrierCommandPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally { buffer.release(); }
        }
    }
    @Test void unknownActionsAreRejectedDuringDecode() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeInt(1).writeLong(0).writeLong(0).writeByte(255);
            assertThrows(DecoderException.class, () -> CarrierCommandPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }
    @Test void viewIncludesFullPrecisionEnergyBoundsOwnerAndBayRoster() {
        byte[] terrainColors = new byte[CarrierViewPayload.Terrain.MAX_CELLS];
        terrainColors[0] = 5;
        UUID candidate = UUID.randomUUID();
        var view = new CarrierViewPayload(3, UUID.randomUUID(), true, false, 1, 2, UUID.randomUUID(),
            "minecraft:overworld", -1, 99, -64, 319, 40000, 98304, 100000, 99999, 2, 200,
            List.of(UUID.randomUUID()), List.of(UUID.randomUUID(), UUID.randomUUID()), true, 1,
            new CarrierViewPayload.Navigation(new CarrierAnchor("minecraft:overworld", 5, 100, 8),
                java.util.Optional.of(new CarrierAnchor("minecraft:overworld", 30, 100, 8)), 0,
                List.of(new CarrierViewPayload.Bay(UUID.randomUUID(), 0, false),
                    new CarrierViewPayload.Bay(UUID.randomUUID(), 3, true)), 119, 4,
                new CarrierViewPayload.Storage(63, 64, 3456, 3456, 221184),
                new CarrierViewPayload.Effects(CarrierPolicy.WorkPhase.FIRE, 100, 480, 987,
                    List.of(new net.minecraft.world.phys.Vec3(40.25, 70.75, -90.5)), 16384, 16384, 18000,
                    new net.minecraft.core.BlockPos(-1, -63, 8), 96), true, 97))
            .withTerrain(new CarrierViewPayload.Terrain("minecraft:overworld", 232, 200, 4, 49, 49, terrainColors))
            .withCandidates(List.of(new CarrierViewPayload.DroneCandidate(candidate, "MG-DRN-TEST")));
        var buffer = Unpooled.buffer();
        try {
            CarrierViewPayload.CODEC.encode(buffer, view);
            assertEquals(view, CarrierViewPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            buffer.clear();
            var unavailable = view.withTerrain(CarrierViewPayload.Terrain.unavailable("minecraft:overworld"));
            CarrierViewPayload.CODEC.encode(buffer, unavailable);
            var decoded = CarrierViewPayload.CODEC.decode(buffer);
            assertTrue(decoded.terrain().specified());
            assertFalse(decoded.terrain().available());
            assertEquals(unavailable, decoded);
        } finally { buffer.release(); }
    }
    @Test void effectCodecRejectsMoreThanSixteenBeamsAndInvalidStoragePage() {
        var effects = new CarrierViewPayload.Effects(CarrierPolicy.WorkPhase.FIRE, 0, 480, 5,
            java.util.Collections.nCopies(17, net.minecraft.world.phys.Vec3.ZERO), 0, 0, 0, net.minecraft.core.BlockPos.ZERO, 96);
        assertTrue(CarrierViewPayload.Effects.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, effects).error().isPresent());
        var storage = new CarrierViewPayload.Storage(64, 64, 0, 3456, 0);
        assertTrue(CarrierViewPayload.Storage.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, storage).error().isPresent());
    }
    @Test void terrainIsBoundedDefensiveAndWorldAddressable() {
        byte[] colors = new byte[9];
        colors[4] = 12;
        var terrain = new CarrierViewPayload.Terrain("minecraft:overworld", 232, 200, 4, 3, 3, colors);
        colors[4] = 1;
        assertEquals(12, terrain.packedAt(232, 200));
        byte[] copy = terrain.colors();
        copy[4] = 2;
        assertEquals(12, terrain.packedAt(232, 200));
        assertEquals(-1, terrain.packedAt(240, 200));
        assertThrows(IllegalArgumentException.class,
            () -> new CarrierViewPayload.Terrain("minecraft:overworld", 0, 0, 1, 50, 1, new byte[50]));
        assertThrows(IllegalArgumentException.class,
            () -> new CarrierViewPayload.Terrain("minecraft:overworld", 0, 0, 1, 3, 3, new byte[8]));
    }
}
