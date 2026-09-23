package jp.morrowgear.drone;

import static jp.morrowgear.drone.OperationEvent.Kind.*;
import static jp.morrowgear.drone.OperationTestFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import jp.morrowgear.drone.network.OperationCuePayload;
import jp.morrowgear.drone.network.OperationCueRequestPayload;
import jp.morrowgear.drone.network.OperationEventsPayload;
import net.minecraft.network.codec.ByteBufCodecs;
import org.junit.jupiter.api.Test;

class OperationCodecTest {
    @Test void persistedHistoryRoundTripsWithoutRuntimeQueueOrCrossOwnerEntries() {
        var event = event(POWER_LOST, 12);
        var codec = OperationEventCodecs.EVENT.listOf(0, OperationEvent.HISTORY_LIMIT);
        var json = codec.encodeStart(JsonOps.INSTANCE, List.of(event)).getOrThrow();
        var restored = codec.parse(JsonOps.INSTANCE, json).getOrThrow();
        var store = new OperationClientStore();
        store.snapshot(UUID.randomUUID(), OWNER, restored);
        assertEquals(List.of(event), store.history());
        assertEquals(0, store.queuedCount());
        store.snapshot(UUID.randomUUID(), OTHER, restored);
        assertTrue(store.history().isEmpty());
    }

    @Test void diskSchemaRejectsOversizedHistoriesStringsAndUnknownEnums() {
        var event = event(WORK_STARTED, 0);
        var json = OperationEventCodecs.EVENT.encodeStart(JsonOps.INSTANCE, event).getOrThrow().getAsJsonObject();
        JsonArray oversized = new JsonArray();
        for (int i = 0; i < 129; i++) oversized.add(json);
        assertTrue(OperationEventCodecs.EVENT.listOf(0, 128).parse(JsonOps.INSTANCE, oversized).error().isPresent());
        json.addProperty("source", "x".repeat(97));
        assertTrue(OperationEventCodecs.EVENT.parse(JsonOps.INSTANCE, json).error().isPresent());
        json.addProperty("source", "Unit 07");
        json.addProperty("kind", "UNKNOWN_STATUS");
        assertTrue(OperationEventCodecs.EVENT.parse(JsonOps.INSTANCE, json).error().isPresent());
    }

    @Test void allThreePayloadsRoundTripWithBoundedRecords() {
        var event = event(POWER_LOST, 5);
        var buffer = Unpooled.buffer();
        try {
            var history = new OperationEventsPayload(SESSION, OWNER, 25, true, List.of(event));
            OperationEventsPayload.CODEC.encode(buffer, history);
            assertEquals(history, OperationEventsPayload.CODEC.decode(buffer));
            var request = new OperationCueRequestPayload(SESSION, event.id());
            OperationCueRequestPayload.CODEC.encode(buffer, request);
            assertEquals(request, OperationCueRequestPayload.CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
            for (var response : List.of(new OperationCuePayload(SESSION, event.id(), event), new OperationCuePayload(SESSION, event.id(), null))) {
                OperationCuePayload.CODEC.encode(buffer, response);
                assertEquals(response, OperationCuePayload.CODEC.decode(buffer));
            }
        } finally { buffer.release(); }
    }

    @Test void packetRejectsOversizedCountBeforeAllocatingOrReadingAnyEvent() {
        var buffer = Unpooled.buffer();
        try {
            buffer.writeZero(32); // session and owner
            buffer.writeLong(0); buffer.writeBoolean(true);
            ByteBufCodecs.VAR_INT.encode(buffer, 129);
            assertThrows(io.netty.handler.codec.DecoderException.class, () -> OperationEventsPayload.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void mixedOwnerAndOversizedConstructorDataCannotBeEncoded() {
        assertThrows(IllegalArgumentException.class, () -> new OperationEventsPayload(SESSION, OWNER, 0, false,
                List.of(event(OTHER, POWER_LOST, 0, "WING-2"))));
        assertThrows(IllegalArgumentException.class, () -> new OperationEventsPayload(SESSION, OWNER, 0, false,
                java.util.Collections.nCopies(129, event(WORK_STARTED, 0))));
        assertThrows(IllegalArgumentException.class, () -> new OperationEvent(UUID.randomUUID(), OWNER, CONTEXT, UNIT,
                0, 0, WORK_STARTED, "x".repeat(97), "overworld", "unit", "2", 1, 0, 0, 0));
    }

    @Test void settingsRejectNanAndClampVolume() {
        assertEquals(0.7f, new OperationRadioSettings(null, Float.NaN, true).volume());
        assertEquals(1, new OperationRadioSettings(null, 20, true).volume());
        assertEquals(0, new OperationRadioSettings(null, -1, true).volume());
    }

}
