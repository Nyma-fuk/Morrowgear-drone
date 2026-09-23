package jp.morrowgear.drone;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EffectReadabilityPolicyTest {
	@Test void distantEffectsCannotBecomeGiantBeacons() {
		for (double pixels : new double[] {0.01, 1, 4, 18, 50, 500}) {
			float width = EffectReadabilityPolicy.width(.05f, pixels, 1.1f, 2);
			assertTrue(width >= .05f && width <= .1f);
		}
		assertEquals(.05f, EffectReadabilityPolicy.width(.05f, 500, 1.1f, 2));
	}

	@Test void invalidProjectionDoesNotInflateEffects() {
		for (double pixels : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertEquals(.05f, EffectReadabilityPolicy.width(.05f, pixels, 1.1f, 2));
			assertEquals(0, EffectReadabilityPolicy.detail(pixels));
		}
	}

	@Test void DetailFadesContinuouslyAndMonotonically() {
		double last = 0;
		for (int pixels = 0; pixels <= 100; pixels++) {
			double next = EffectReadabilityPolicy.detail(pixels);
			assertTrue(next >= last && next <= 1);
			assertTrue(next - last < .05);
			last = next;
		}
		assertEquals(1, last);
	}
}
