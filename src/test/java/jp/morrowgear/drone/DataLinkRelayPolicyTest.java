package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DataLinkRelayPolicyTest {
	private static DataLinkRelayPolicy.Node node(double x) {
		return new DataLinkRelayPolicy.Node(UUID.randomUUID(), new DataLinkRelayPolicy.Point(x, 80, 0), 100);
	}

	@Test void directControlDoesNotNeedAStation() {
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0), List.of());
		var link = coverage.link(new DataLinkRelayPolicy.Point(255, 80, 0));
		assertTrue(link.connected());
		assertEquals(0, link.hops());
	}

	@Test void stationsExtendControlHopByHop() {
		var first = node(240); var second = node(480); var third = node(720);
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0),
			List.of(first, second, third));
		var link = coverage.link(new DataLinkRelayPolicy.Point(940, 80, 0));
		assertTrue(link.connected());
		assertEquals(3, link.hops());
		assertEquals(third.id(), link.relayId());
	}

	@Test void aGapCannotBeBridged() {
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0),
			List.of(node(240), node(520)));
		assertFalse(coverage.link(new DataLinkRelayPolicy.Point(700, 80, 0)).connected());
	}

	@Test void relayGraphIsBounded() {
		var nodes = java.util.stream.IntStream.range(0, 100)
			.mapToObj(index -> node(100 + index * 10.0)).toList();
		assertTrue(DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0), nodes)
			.relays().size() <= DataLinkRelayPolicy.MAX_RELAY_NODES);
	}

	@Test void relayLimitKeepsTheNearestUsableChain() {
		var far = java.util.stream.IntStream.range(0, DataLinkRelayPolicy.MAX_RELAY_NODES)
			.mapToObj(index -> node(10000 + index * 10.0)).toList();
		var first = node(240); var second = node(480);
		var candidates = new java.util.ArrayList<DataLinkRelayPolicy.Node>(far);
		candidates.add(first); candidates.add(second);
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0), candidates);
		assertTrue(coverage.link(new DataLinkRelayPolicy.Point(700, 80, 0)).connected());
	}
}
