package jp.morrowgear.drone.carrier;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Bounded visual-only sampling. The actual teleport always rechecks the live destination. */
final class CarrierBoardingPreview {
    static final int INTERVAL = 10;
    private BlockPos pad;
    private Vec3 origin;
    private long sampledAt;

    Optional<BlockPos> update(long tick, Vec3 current, boolean available,
                               Predicate<BlockPos> stillLoadedAndInRange, Supplier<BlockPos> sample) {
        if (!available) { clear(); return Optional.empty(); }
        boolean moved = origin == null || current.distanceToSqr(origin) >= .5 * .5
            || !BlockPos.containing(current).equals(BlockPos.containing(origin));
        if (moved || tick < sampledAt || tick - sampledAt >= INTERVAL) {
            BlockPos result = sample.get();
            pad = result == null ? null : result.immutable();
            origin = current;
            sampledAt = tick;
        }
        if (pad != null && !stillLoadedAndInRange.test(pad)) pad = null;
        return Optional.ofNullable(pad);
    }

    void clear() { pad = null; origin = null; }
}
