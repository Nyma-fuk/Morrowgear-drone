package jp.morrowgear.drone.carrier;

import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class CarrierBayTest {
    @Test void reservationsEnforceOwnerCapacityAndIdentity() {
        UUID owner = UUID.randomUUID();
        var bay = new CarrierServiceBay(List.of(), () -> {});
        var identity = identity(owner);
        assertTrue(bay.reserve(UUID.randomUUID(), identity, 0).isEmpty());
        var first = bay.reserve(owner, identity, 0).orElseThrow();
        assertEquals(first, bay.reserve(owner, identity, 10).orElseThrow());
        assertEquals(1, bay.snapshot().size());
        assertTrue(bay.reserve(owner, new CarrierServiceBay.Identity(identity.drone(), owner, identity.homeDock(), "other", identity.mission()), 20).isEmpty());
        for (int i = 1; i < CarrierPolicy.BAY_SLOTS; i++) assertTrue(bay.reserve(owner, identity(owner), 0).isPresent());
        assertTrue(bay.reserve(owner, identity(owner), 0).isEmpty());
        assertTrue(bay.release(identity.drone()));
        assertFalse(bay.release(identity.drone()));
        assertTrue(bay.reserve(owner, identity(owner), 0).isPresent());
    }
    @Test void saveRoundTripRetainsHomeDockWingAndMission() {
        var input = new CarrierServiceBay.Lease(identity(UUID.randomUUID()), 3);
        var encoded = CarrierServiceBay.Lease.CODEC.encodeStart(JsonOps.INSTANCE, input).getOrThrow();
        assertEquals(input, CarrierServiceBay.Lease.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
        assertThrows(IllegalArgumentException.class, () -> new CarrierServiceBay(List.of(input, input), () -> {}));
    }
    @Test void movingBayRequiresRelativePositionAndVelocityMatch() {
        Vec3 bay = new Vec3(20, 100, 40), carrierVelocity = new Vec3(.15, 0, 0);
        assertTrue(CarrierServiceBay.stable(bay.add(.2, 0, 0), carrierVelocity, bay, carrierVelocity));
        assertFalse(CarrierServiceBay.stable(bay, Vec3.ZERO, bay, carrierVelocity));
        assertFalse(CarrierServiceBay.stable(bay.add(2, 0, 0), carrierVelocity, bay, carrierVelocity));
    }
    @Test void allFourBaysMatchApprovedV27WithoutOverlapping() {
        assertEquals(26, CarrierPolicy.WIDTH);
        assertEquals(50, CarrierPolicy.LENGTH);
        assertEquals(11, CarrierPolicy.HEIGHT);
        double[][] expected = {{-7.15, -5.2}, {7.15, -5.2}, {-3.6, 7.8}, {3.6, 7.8}};
        for (int slot = 0; slot < 4; slot++) {
            assertEquals(expected[slot][0], CarrierPolicy.bayX(slot));
            assertEquals(expected[slot][1], CarrierPolicy.bayZ(slot));
            assertTrue(Math.abs(CarrierPolicy.bayX(slot)) + 1.5 < CarrierPolicy.WIDTH / 2);
            assertTrue(Math.abs(CarrierPolicy.bayZ(slot)) + 1.5 < CarrierPolicy.LENGTH / 2);
            for (int other = 0; other < slot; other++)
                assertTrue(Math.abs(CarrierPolicy.bayX(slot) - CarrierPolicy.bayX(other)) >= 3
                    || Math.abs(CarrierPolicy.bayZ(slot) - CarrierPolicy.bayZ(other)) >= 3);
        }
        assertThrows(IllegalArgumentException.class, () -> CarrierPolicy.bayX(4));
    }
    private static CarrierServiceBay.Identity identity(UUID owner) {
        return new CarrierServiceBay.Identity(UUID.randomUUID(), owner,
            Optional.of(new CarrierAnchor("minecraft:overworld", -100, 80, 250)), "WING-007", "original-mission");
    }
}
