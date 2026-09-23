package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class SolarServiceResumePolicyTest {
	@Test
	void resumeOrderIsSavedThenWingThenDockThenFollowThenAutonomousOrbit() {
		assertEquals(SolarServiceResumePolicy.Action.RESUME_SAVED,
			SolarServiceResumePolicy.choose(true, true, true, true));
		assertEquals(SolarServiceResumePolicy.Action.REJOIN_WING,
			SolarServiceResumePolicy.choose(false, true, true, true));
		assertEquals(SolarServiceResumePolicy.Action.REQUEST_DOCK,
			SolarServiceResumePolicy.choose(false, false, true, true));
		assertEquals(SolarServiceResumePolicy.Action.FOLLOW_OWNER,
			SolarServiceResumePolicy.choose(false, false, false, true));
		assertEquals(SolarServiceResumePolicy.Action.SAFE_ORBIT,
			SolarServiceResumePolicy.choose(false, false, false, false));
	}

	@Test
	void everyInputCombinationProducesAnExplicitNonNullExit() {
		IntStream.range(0, 16).forEach(mask -> {
			var action = SolarServiceResumePolicy.choose((mask & 1) != 0, (mask & 2) != 0,
				(mask & 4) != 0, (mask & 8) != 0);
			assertFalse(action.name().isBlank());
		});
	}

	@Test
	void droneIntegrationKeepsSolarChangesScopedAndHasReasonedFallbacks() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		assertEquals(true, source.contains("solarResumeTask = currentTaskSnapshot()"));
		assertEquals(true, source.contains("resumeAfterSolarService"));
		assertEquals(true, source.contains("saved.kind() == DroneTaskStack.Kind.IDLE"));
		assertEquals(true, source.contains("solarResumeTask.kind() == DroneTaskStack.Kind.TRACKING"));
		assertEquals(true, source.contains("SOLAR LINK END / DOCK QUEUED / NO VALID MISSION"));
		assertEquals(true, source.contains("SOLAR LINK END / AUTONOMOUS ORBIT / OWNER UNAVAILABLE"));
		assertEquals(true, source.contains("-SOLAR-SAFE-ORBIT"));
	}
}
