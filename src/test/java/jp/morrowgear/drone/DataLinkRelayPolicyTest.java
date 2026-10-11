package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class DataLinkRelayPolicyTest {
	private static DataLinkRelayPolicy.Node node(UUID id, double x) {
		return new DataLinkRelayPolicy.Node(id, new DataLinkRelayPolicy.Point(x, 80, 0), 100);
	}

	@Test void directControlEndsAtTheControllerRange() {
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0), List.of());
		assertTrue(coverage.link(new DataLinkRelayPolicy.Point(128, 80, 0)).connected());
		assertFalse(coverage.link(new DataLinkRelayPolicy.Point(128.01, 80, 0)).connected());
		assertEquals(0, coverage.link(new DataLinkRelayPolicy.Point(64, 80, 0)).hops());
	}

	@Test void connectedFieldChainExtendsCoverageAndPublishesParents() {
		UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0),
			List.of(node(firstId, 88), node(secondId, 176)));
		var link = coverage.link(new DataLinkRelayPolicy.Point(260, 80, 0));
		assertTrue(link.connected());
		assertEquals(2, link.hops());
		assertEquals(secondId, link.relayId());
		var second = coverage.relays().stream().filter(entry -> entry.node().id().equals(secondId)).findFirst().orElseThrow();
		assertEquals(firstId, second.parentId());
	}

	@Test void gapInFieldChainDoesNotFakeAConnection() {
		UUID firstId = UUID.randomUUID(), isolatedId = UUID.randomUUID();
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0),
			List.of(node(firstId, 88), node(isolatedId, 240)));
		assertEquals(1, coverage.relays().size());
		assertEquals(firstId, coverage.relays().getFirst().node().id());
		assertFalse(coverage.link(new DataLinkRelayPolicy.Point(350, 80, 0)).connected());
	}

	@Test void qualityUsesDistanceBandsAndHopPenalty() {
		UUID firstId = UUID.randomUUID(), secondId = UUID.randomUUID();
		var coverage = DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0),
			List.of(node(firstId, 64), node(secondId, 128)));
		assertEquals(100, coverage.relays().getFirst().quality());
		var second = coverage.relays().stream().filter(entry -> entry.node().id().equals(secondId)).findFirst().orElseThrow();
		assertEquals(97, second.quality());
		assertNull(coverage.relays().getFirst().parentId());
	}

	@Test void relayGraphIsBounded() {
		var nodes = java.util.stream.IntStream.range(0, 100)
			.mapToObj(index -> node(new UUID(0, index + 1), 8 + index * 8.0)).toList();
		assertTrue(DataLinkRelayPolicy.coverage(new DataLinkRelayPolicy.Point(0, 80, 0), nodes)
			.relays().size() <= DataLinkRelayPolicy.MAX_RELAY_NODES);
	}
}
