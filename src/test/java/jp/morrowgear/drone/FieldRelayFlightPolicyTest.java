package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class FieldRelayFlightPolicyTest {
	@Test void deploymentUsesTheStableSlotAltitudeWithoutAPlayerRelativeClimbStage() {
		Vec3 current = new Vec3(0, 70, 0), slot = new Vec3(88, 108, 0);
		assertEquals(slot, FieldRelayFlightPolicy.guidance(slot));
		assertEquals(slot.y, FieldRelayFlightPolicy.guidance(slot).y, 1.0e-9);
	}

	@Test void establishedUsesTheWholeStableLoiterEnvelope() {
		Vec3 slot = new Vec3(88, 108, 0);
		assertTrue(FieldRelayFlightPolicy.established(new Vec3(99.9, 111.9, 0), slot));
		assertFalse(FieldRelayFlightPolicy.established(new Vec3(100.1, 108, 0), slot));
		assertFalse(FieldRelayFlightPolicy.established(new Vec3(88, 112.1, 0), slot));
	}

	@Test void loiterMovesInOneDirectionAtAConstantAltitudeAndRadius() {
		Vec3 slot = new Vec3(88, 108, 0);
		UUID field = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
		Vec3 first = FieldRelayFlightPolicy.loiterTarget(slot, field, 100);
		Vec3 next = FieldRelayFlightPolicy.loiterTarget(slot, field, 101);
		assertEquals(FieldRelayFlightPolicy.LOITER_RADIUS, first.subtract(slot).horizontalDistance(), 1.0e-9);
		assertEquals(slot.y, first.y, 1.0e-9);
		assertTrue(next.distanceTo(first) > 0.01);
		assertEquals(next.subtract(first), FieldRelayFlightPolicy.loiterVelocity(slot, field, 100));
	}
}
