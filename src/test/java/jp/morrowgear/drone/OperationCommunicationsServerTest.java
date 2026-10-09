package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class OperationCommunicationsServerTest {
	@Test
	void extractsOnlyTheLastThreeDigitsWithoutRegexAllocation() {
		assertEquals("347", OperationCommunicationsServer.displayNumber("WING-12-A347"));
		assertEquals("7", OperationCommunicationsServer.displayNumber("MG-7"));
		assertEquals(Integer.toString(Math.floorMod("ALPHA".hashCode(), 100)),
			OperationCommunicationsServer.displayNumber("ALPHA"));
	}
}
