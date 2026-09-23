package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CarrierPolicyTest {
    @Test void previewIncludesCanopyImmediatelyBelowBellyAndRejectsAdjacentChunkOrigins() {
        for (double belly : new double[] {100, 100.5, 100.9}) {
            int top = CarrierPolicy.operationTop(belly);
            assertTrue(belly - .1 > top + 1);
            assertTrue(belly - .1 <= top + 2);
            var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", -1, -1, -64, top);
            assertTrue(CarrierPolicy.aligned(op, "minecraft:overworld", -.01, belly, -.01));
            assertFalse(CarrierPolicy.aligned(op, "minecraft:overworld", 0, belly, -.01));
            assertFalse(CarrierPolicy.aligned(op, "minecraft:overworld", -.01, belly, 0));
            assertFalse(CarrierPolicy.aligned(op, "minecraft:the_nether", -.01, belly, -.01));
            assertFalse(CarrierPolicy.aligned(op, "minecraft:overworld", -.01, top + 1, -.01));
        }
    }
    @Test void productionMiningDepthFollowsCarrierAltitudeAndDimensionBottomInsteadOfSixtyFourLayers() {
        UUID generation = UUID.randomUUID();
        var low = CarrierPolicy.miningOperation(generation, "minecraft:overworld", 8, 8, 100.5, -64, 320);
        var high = CarrierPolicy.miningOperation(generation, "minecraft:overworld", 8, 8, 200, -64, 320);
        var nether = CarrierPolicy.miningOperation(generation, "minecraft:the_nether", -1, -1, 80, 0, 256);
        assertEquals(-64, low.minY());
        assertEquals(99, low.maxY());
        assertEquals((99 - -64 + 1) * 256, low.volume());
        assertEquals(198, high.maxY());
        assertTrue(high.volume() > low.volume());
        assertEquals(0, nether.minY());
        assertEquals(78, nether.maxY());
        assertNotEquals(16 * 16 * 64, low.volume());
        assertThrows(IllegalArgumentException.class, () -> CarrierPolicy.miningOperation(
            generation, "minecraft:overworld", 0, 0, -64, -64, 320));
    }
    @Test void aftBoardingZoneIsInsideV27ButOutsideEveryOriginChunk() {
        assertTrue(CarrierPolicy.inBoardingProjection(0, 18));
        assertTrue(CarrierPolicy.inBoardingProjection(-1.5, 16.5));
        assertFalse(CarrierPolicy.inBoardingProjection(20, 0));
        assertFalse(CarrierPolicy.inBoardingProjection(0, 16));
        assertFalse(CarrierPolicy.inBoardingProjection(Double.NaN, 18));
        assertTrue(CarrierPolicy.BOARDING_RADIUS < CarrierPolicy.WIDTH / 2);
        assertTrue(CarrierPolicy.BOARDING_Z + CarrierPolicy.BOARDING_RADIUS < CarrierPolicy.LENGTH / 2);
        for (double z : new double[] {-32, -16.01, -.01, 0, .5, 15.99, 16}) {
            int miningChunk = Math.floorDiv((int) Math.floor(z), 16);
            int minimumBoardingChunk = Math.floorDiv((int) Math.floor(z + CarrierPolicy.BOARDING_Z - CarrierPolicy.BOARDING_RADIUS), 16);
            assertTrue(minimumBoardingChunk > miningChunk);
        }
    }
    @Test void boardingEnvelopeRejectsClearlyRemoteConsoleEntryWithoutReplacingServerChecks() {
        assertTrue(CarrierPolicy.withinBoardingEnvelope(0, 18, 0, 0));
        assertTrue(CarrierPolicy.withinBoardingEnvelope(1.5, 19.5, 0, 0));
        assertFalse(CarrierPolicy.withinBoardingEnvelope(48, 0, 0, 0));
        assertFalse(CarrierPolicy.withinBoardingEnvelope(Double.NaN, 18, 0, 0));
    }
    @Test void negativeCoordinatesUseRealChunkBoundaries() {
        for (int x : new int[] {-33, -32, -17, -16, -1, 0, 15, 16}) {
            var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", x, x, -64, 319);
            assertTrue(op.contains(x, 0, x));
            assertEquals(Math.floorDiv(x, 16) * 16, op.x(0));
            assertEquals(Math.floorDiv(x, 16) * 16 + 15, op.x(255));
        }
    }
    @Test void traversalIsBoundedUniqueAndTopDown() {
        var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", -1, 32, -64, 4);
        var seen = new HashSet<String>();
        var progress = new CarrierPolicy.Progress(op, 0, List.of());
        for (int cursor = 0; cursor < op.volume(); cursor++) {
            assertEquals(cursor, progress.cursor());
            assertTrue(op.contains(op.x(cursor), op.y(cursor), op.z(cursor)));
            assertTrue(seen.add(op.x(cursor) + "/" + op.y(cursor) + "/" + op.z(cursor)));
            if (cursor > 0) assertTrue(op.y(cursor) <= op.y(cursor - 1));
            progress = progress.advance();
        }
        assertTrue(progress.complete());
        assertEquals(progress, progress.advance());
        assertEquals(-64, op.y(op.volume() - 1));
        assertEquals(op, progress.operation());
    }
    @Test void protectedColumnsAndSavedProgressRoundTripWithoutMutatingGeneration() {
        var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", -1, -1, -64, 80);
        var input = new CarrierPolicy.Progress(op, 3, List.of()).seal();
        var encoded = CarrierPolicy.Progress.CODEC.encodeStart(JsonOps.INSTANCE, input).getOrThrow();
        var result = CarrierPolicy.Progress.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
        assertEquals(input, result);
        assertEquals(List.of(3), result.sealedColumns());
        assertTrue(new CarrierPolicy.Progress(op, 259, result.sealedColumns()).sealed());
        assertThrows(UnsupportedOperationException.class, () -> result.sealedColumns().add(4));
    }
    @Test void confirmationRejectsStaleUnloadedUnattendedOrForeignRequests() {
        var op = CarrierPolicy.Operation.at(UUID.randomUUID(), "minecraft:overworld", 0, 0, -64, 200);
        int until = CarrierPolicy.PREVIEW_TICKS;
        assertEquals(600, until);
        assertTrue(CarrierPolicy.canActivate(op.generation(), op, 300, until, true, true, true));
        assertFalse(CarrierPolicy.canActivate(UUID.randomUUID(), op, 300, until, true, true, true));
        assertFalse(CarrierPolicy.canActivate(op.generation(), op, until + 1L, until, true, true, true));
        assertFalse(CarrierPolicy.canActivate(op.generation(), op, -1, until, true, true, true));
        assertFalse(CarrierPolicy.canActivate(op.generation(), op, 300, until, false, true, true));
        assertFalse(CarrierPolicy.canActivate(op.generation(), op, 300, until, true, false, true));
        assertFalse(CarrierPolicy.canActivate(op.generation(), op, 300, until, true, true, false));
        assertFalse(CarrierPolicy.canActivate(op.generation(), null, 300, until, true, true, true));
    }
    @Test void invalidSavedBoundsAreRejected() {
        String base = "{\"generation\":\"00000000-0000-0000-0000-000000000001\",\"dimension\":\"minecraft:overworld\",\"chunk_x\":0,\"chunk_z\":0,\"min_y\":20,\"max_y\":-64}";
        assertTrue(CarrierPolicy.Operation.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(base)).error().isPresent());
        assertTrue(CarrierPolicy.UUID_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("\"bad\"")).error().isPresent());
    }
    @Test void cabinsNeverOverlapAndEntranceIsNotBuildable() {
        var origins = new HashSet<String>();
        for (int i = 0; i < CarrierPolicy.MAX_CABINS; i++) {
            int x = CarrierPolicy.cabinX(i), z = CarrierPolicy.cabinZ(i);
            assertTrue(origins.add(x + "/" + z));
            assertTrue(CarrierPolicy.inCabin(i, x + 2, 65, z + 2));
            assertTrue(CarrierPolicy.reservedEntry(i, x + 2, 65, z + 2));
            assertFalse(CarrierPolicy.inCabin(i, x, 65, z + 2));
            assertFalse(CarrierPolicy.inCabin(i, x + 2, 76, z + 2));
            assertFalse(CarrierPolicy.inCabin(i, x + 64, 65, z + 2));
        }
        assertThrows(IllegalArgumentException.class, () -> CarrierPolicy.cabinX(-1));
        assertThrows(IllegalArgumentException.class, () -> CarrierPolicy.cabinZ(CarrierPolicy.MAX_CABINS));
    }
}
