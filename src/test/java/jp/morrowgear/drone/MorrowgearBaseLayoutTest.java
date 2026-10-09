package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class MorrowgearBaseLayoutTest {
	@Test
	void baseProvidesSixteenDistinctWallEmbeddedWideDocks() {
		var stations = MorrowgearBaseLayout.dockStations();
		assertEquals(16, stations.size());
		assertEquals(16, new HashSet<>(stations).size());
		assertTrue(stations.stream().allMatch(station -> station.y() == 0));
		assertTrue(stations.stream().allMatch(MorrowgearBaseLayout::hasClearDeparture));
		Map<Direction, Long> byFacing = stations.stream().collect(Collectors.groupingBy(
			MorrowgearBaseLayout.DockStation::facing, Collectors.counting()));
		for (Direction direction : Direction.Plane.HORIZONTAL) assertEquals(4L, byFacing.get(direction));
	}

	@Test
	void fiveByFiveDockFootprintsNeverOverlapAndRemainInsideTheWall() {
		var stations = MorrowgearBaseLayout.dockStations();
		for (int i = 0; i < stations.size(); i++) {
			var a = stations.get(i);
			assertTrue(Math.abs(a.x()) + MorrowgearBaseLayout.DOCK_FOOTPRINT / 2 < MorrowgearBaseLayout.HALF_SIZE);
			assertTrue(Math.abs(a.z()) + MorrowgearBaseLayout.DOCK_FOOTPRINT / 2 < MorrowgearBaseLayout.HALF_SIZE);
			for (int j = i + 1; j < stations.size(); j++) {
				var b = stations.get(j);
				assertTrue(Math.abs(a.x() - b.x()) >= MorrowgearBaseLayout.DOCK_FOOTPRINT
					|| Math.abs(a.z() - b.z()) >= MorrowgearBaseLayout.DOCK_FOOTPRINT);
			}
		}
	}

	@Test
	void everyWallDockReceivesOneAircraftAndTheFleetRetainsAllOperationalRoles() {
		var roles = MorrowgearBaseGenerator.fleetRoles();
		var loadouts = MorrowgearBaseGenerator.fleetLoadouts();
		assertEquals(MorrowgearBaseLayout.dockStations().size(), roles.size());
		assertEquals(roles.size(), loadouts.size());
		assertEquals(4, roles.stream().filter(role -> role == DroneRole.SCOUT).count());
		assertEquals(8, roles.stream().filter(role -> role == DroneRole.SECURITY).count());
		assertEquals(1, roles.stream().filter(role -> role == DroneRole.ENGINEER).count());
		assertEquals(1, roles.stream().filter(role -> role == DroneRole.CARGO).count());
		assertEquals(1, roles.stream().filter(role -> role == DroneRole.FIELD).count());
		assertEquals(1, roles.stream().filter(role -> role == DroneRole.SALVAGE).count());
		assertEquals(3, loadouts.stream().filter(loadout -> loadout == SecurityLoadout.AUTOCANNON).count());
		assertEquals(3, loadouts.stream().filter(loadout -> loadout == SecurityLoadout.LASER).count());
		assertEquals(2, loadouts.stream().filter(loadout -> loadout == SecurityLoadout.MISSILE).count());
	}

	@Test
	void generatedDockSupplyRulesOnlyStockTheInstalledWeapon() {
		for (SecurityLoadout loadout : SecurityLoadout.values()) {
			var rules = MorrowgearBaseGenerator.supplyRules(loadout);
			assertEquals(DockSupplyPolicy.SupplyKind.values().length, rules.size());
			for (var rule : rules) {
				if (rule.kind() == DockSupplyPolicy.SupplyKind.FUEL
					|| rule.kind() == DockSupplyPolicy.SupplyKind.REPAIR) assertTrue(rule.minimum() > 0);
			}
			assertEquals(loadout == SecurityLoadout.AUTOCANNON ? 8 : 0, minimum(rules, DockSupplyPolicy.SupplyKind.GUN));
			assertEquals(loadout == SecurityLoadout.LASER ? 8 : 0, minimum(rules, DockSupplyPolicy.SupplyKind.LASER));
			assertEquals(loadout == SecurityLoadout.MISSILE ? 4 : 0, minimum(rules, DockSupplyPolicy.SupplyKind.MISSILE));
		}
	}

	private static int minimum(java.util.List<SupplyNetworkPolicy.Rule> rules, DockSupplyPolicy.SupplyKind kind) {
		return rules.stream().filter(rule -> rule.kind() == kind).findFirst().orElseThrow().minimum();
	}

	@Test
	void fourWallsRetainWideCardinalGateOpenings() {
		assertTrue(MorrowgearBaseLayout.gateOpening(0, -40));
		assertTrue(MorrowgearBaseLayout.gateOpening(0, 39));
		assertTrue(MorrowgearBaseLayout.gateOpening(-40, 0));
		assertTrue(MorrowgearBaseLayout.gateOpening(39, 0));
		assertFalse(MorrowgearBaseLayout.gateOpening(8, -40));
		assertFalse(MorrowgearBaseLayout.gateOpening(39, 8));
	}
}
