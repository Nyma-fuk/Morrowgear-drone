package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

final class FieldRelayMeshPolicyTest {
	private static final FieldRelayMeshPolicy.Point ORIGIN = new FieldRelayMeshPolicy.Point(0, 80, 0);

	private static List<FieldRelayMeshPolicy.Field> fields(int count) {
		return IntStream.range(0, count).mapToObj(index -> new FieldRelayMeshPolicy.Field(
			new UUID(0, index + 1), new FieldRelayMeshPolicy.Point(index * 3, 80, 0), 100 - index, ""))
			.toList();
	}

	@Test void reserveCapacityScalesAtDefinedBoundaries() {
		assertEquals(0, FieldRelayMeshPolicy.reserveCount(3));
		assertEquals(1, FieldRelayMeshPolicy.reserveCount(4));
		assertEquals(1, FieldRelayMeshPolicy.reserveCount(9));
		assertEquals(2, FieldRelayMeshPolicy.reserveCount(10));
	}

	@Test void twelveIdleFieldsFormTenBaselineAndTwoReserveNodes() {
		var plan = FieldRelayMeshPolicy.plan(ORIGIN, fields(12), List.of());
		assertEquals(10, plan.baseline());
		assertEquals(2, plan.reserve());
		assertEquals(0, plan.dynamic());
		assertEquals(12, plan.assignments().size());
	}

	@Test void dynamicDemandConsumesReserveBeforeBaselineCapacity() {
		var demand = new FieldRelayMeshPolicy.Demand("combat", new FieldRelayMeshPolicy.Point(352, 80, 0), 400);
		var plan = FieldRelayMeshPolicy.plan(ORIGIN, fields(12), List.of(demand));
		assertEquals(3, plan.dynamic());
		assertEquals(9, plan.baseline());
		assertEquals(0, plan.reserve());
		assertEquals(0, plan.shortage());
	}

	@Test void assignmentRetainsAnExistingValidSlot() {
		var initial = FieldRelayMeshPolicy.plan(ORIGIN, fields(4), List.of());
		var retained = initial.assignments().getFirst();
		var updatedFields = fields(4).stream().map(field -> field.id().equals(retained.fieldId())
			? new FieldRelayMeshPolicy.Field(field.id(), new FieldRelayMeshPolicy.Point(400, 80, 0),
				field.energyPercent(), retained.slot().id()) : field).toList();
		var updated = FieldRelayMeshPolicy.plan(ORIGIN, updatedFields, List.of());
		assertEquals(retained.slot().id(), updated.assignments().stream()
			.filter(value -> value.fieldId().equals(retained.fieldId())).findFirst().orElseThrow().slot().id());
	}

	@Test void insufficientFieldsAreReportedInsteadOfPretendingCoverage() {
		var demand = new FieldRelayMeshPolicy.Demand("remote", new FieldRelayMeshPolicy.Point(704, 80, 0), 400);
		var plan = FieldRelayMeshPolicy.plan(ORIGIN, fields(3), List.of(demand));
		assertTrue(plan.shortage() > 0);
		assertEquals(3, plan.dynamic());
	}

	@Test void aSingleFieldMovesTowardDemandInsteadOfRemainingOnBaseline() {
		var demand = new FieldRelayMeshPolicy.Demand("remote", new FieldRelayMeshPolicy.Point(240, 80, 0), 400);
		var plan = FieldRelayMeshPolicy.plan(ORIGIN, fields(1), List.of(demand));
		assertEquals(1, plan.dynamic());
		assertEquals(0, plan.baseline());
		assertEquals("remote", plan.assignments().getFirst().slot().demandId());
	}
}
