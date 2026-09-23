package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierBoardingPreviewTest {
    private final Vec3 origin = new Vec3(.25, 100, 18.25);
    private final BlockPos pad = new BlockPos(0, 70, 18);

    @Test void inactiveStateClearsImmediatelyAndDoesNotScanUntilAuthorizedAgain() {
        var preview = new CarrierBoardingPreview();
        assertEquals(Optional.of(pad), preview.update(0, origin, true, p -> true, () -> pad));
        assertTrue(preview.update(1, origin, false, p -> true, () -> { fail("Inactive scan"); return pad; }).isEmpty());
        assertEquals(Optional.of(pad), preview.update(2, origin, true, p -> true, () -> pad));
        preview.clear();
        assertTrue(preview.update(3, origin, false, p -> true, () -> pad).isEmpty());
    }

    @Test void stationarySamplingIsBoundedAndFailedGroundSearchDoesNotRetryEveryTick() {
        var preview = new CarrierBoardingPreview();
        var calls = new AtomicInteger();
        for (int tick = 0; tick < 30; tick++)
            assertTrue(preview.update(tick, origin, true, p -> true, () -> { calls.incrementAndGet(); return null; }).isEmpty());
        assertEquals(3, calls.get());
        assertEquals(Optional.of(pad), preview.update(30, origin, true, p -> true, () -> pad));
    }

    @Test void MovementCellChangeAndClockRollbackRefreshBeforeTheNormalInterval() {
        var preview = new CarrierBoardingPreview();
        var calls = new AtomicInteger();
        java.util.function.Supplier<BlockPos> sample = () -> { calls.incrementAndGet(); return pad; };
        preview.update(20, origin, true, p -> true, sample);
        preview.update(21, origin.add(.1, 0, 0), true, p -> true, sample);
        assertEquals(1, calls.get());
        preview.update(22, origin.add(.6, 0, 0), true, p -> true, sample);
        assertEquals(2, calls.get());
        preview.update(23, origin.add(.8, 0, 0), true, p -> true, sample);
        assertEquals(3, calls.get());
        preview.update(0, origin.add(.8, 0, 0), true, p -> true, sample);
        assertEquals(4, calls.get());
    }

    @Test void UnloadedOrOutOfRangePadsDisappearWithoutDemandLoading() {
        var preview = new CarrierBoardingPreview();
        preview.update(0, origin, true, p -> true, () -> pad);
        assertTrue(preview.update(1, origin, true, p -> false, () -> { fail("Must not load a missing chunk"); return pad; }).isEmpty());
        assertTrue(preview.update(10, origin, true, p -> false, () -> pad).isEmpty());
        assertEquals(Optional.of(pad), preview.update(20, origin, true, p -> true, () -> pad));
    }

    @Test void PublishedBlockPositionsAreImmutableCopies() {
        var preview = new CarrierBoardingPreview();
        var mutable = new BlockPos.MutableBlockPos(0, 70, 18);
        var published = preview.update(0, origin, true, p -> true, () -> mutable);
        mutable.set(99, 99, 99);
        assertEquals(Optional.of(pad), published);
    }
}
