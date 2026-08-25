package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class SalvageTowPolicyTest {
	@Test void clearanceUsesTheLowestSurfaceOfTheSuspendedAirframe() {
		assertEquals(75.85, SalvageTowPolicy.requiredFlightY(70.0, 1.70), 0.0001);
	}

	@Test void ropeConstraintPreservesSwingButPreventsUnboundedSeparation() {
		Vec3 hook = new Vec3(0, 10, 0);
		SalvageTowPolicy.Step step = SalvageTowPolicy.step(new Vec3(1.9, 8.1, 0),
			new Vec3(0.6, 0, 0), hook, new Vec3(0.7, 0, 0), Vec3.ZERO, 0.42, true);
		assertTrue(step.predictedPosition().distanceTo(hook) <= SalvageTowPolicy.ROPE_LENGTH + 0.0001);
		assertTrue(Math.abs(step.velocity().x) > 0.01, "lateral pendulum motion remains visible");
		assertTrue(step.velocity().length() <= SalvageTowPolicy.MAX_LOAD_SPEED + 0.0001);
	}

	@Test void dockTransferUsesSafeOverheadEnvelopeInsteadOfImpossibleThreeDimensionalRadius() {
		Vec3 dock = new Vec3(100.5, 65.5, 200.5);
		assertTrue(SalvageTowPolicy.readyForDockTransfer(new Vec3(104.8, 71.2, 200.5), dock));
		assertTrue(!SalvageTowPolicy.readyForDockTransfer(new Vec3(106.2, 71.2, 200.5), dock));
		assertTrue(!SalvageTowPolicy.readyForDockTransfer(new Vec3(100.5, 78.0, 200.5), dock));
	}
}
