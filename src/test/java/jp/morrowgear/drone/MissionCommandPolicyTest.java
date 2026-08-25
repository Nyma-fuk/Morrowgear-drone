package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

final class MissionCommandPolicyTest {
	@TestFactory
	Stream<DynamicTest> onlyCommandsThatTakePrimaryFlightControlPauseCargo() {
		Map<String, Boolean> cases = new LinkedHashMap<>();
		cases.put("work:ore:1:2:3:24:op", true);
		cases.put("move:10:20:mission:4:0", true);
		cases.put("track:42:mission:4:0", true);
		cases.put("follow:mission:4:0:42", true);
		cases.put("patrol:mission:4:0:1,2;3,4", true);
		cases.put("standby", true);
		cases.put("return", true);
		cases.put("dock", true);
		cases.put("orbit", true);
		cases.put("cargo_source:123", false);
		cases.put("cargo_target:456", false);
		cases.put("wing_join:WING-A", false);
		cases.put("wing_leave", false);
		cases.put("module:cargo", false);
		cases.put("assign_dock:123", false);
		cases.put("guard:1:2:3:16:op", false);
		cases.put("decommission", false);
		cases.put("", false);
		return cases.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
			entry.getKey().isEmpty() ? "empty command" : entry.getKey(), () ->
			assertEquals(entry.getValue(), MissionCommandPolicy.preemptsCargoRoute(entry.getKey()))));
	}
}
