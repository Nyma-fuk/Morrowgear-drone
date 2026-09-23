package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WaypointFormationIntegrationTest {
	@Test
	void runtimeAppliesNonRegressionToMovingAndRendezvousFormationTargets() throws Exception {
		String source = Files.readString(Path.of("src/main/java/jp/morrowgear/drone/DroneEntity.java"));
		assertEquals(2, occurrences(source,
			"SwarmFormation.nonRegressiveMergeTarget("));
		String moving = source.substring(source.indexOf("private Vec3 waypointTarget("),
			source.indexOf("private Vec3 rendezvousTarget("));
		String rendezvous = source.substring(source.indexOf("private Vec3 rendezvousTarget("),
			source.indexOf("private int plannedFormationSize("));
		assertTrue(moving.contains("nonRegressiveMergeTarget(position(), destination, formationSlot)"));
		assertTrue(rendezvous.contains("nonRegressiveMergeTarget(member.position(), destination, formationSlot)"));
	}

	private static int occurrences(String source, String target) {
		int count = 0;
		for (int index = 0; (index = source.indexOf(target, index)) >= 0; index += target.length()) count++;
		return count;
	}
}
