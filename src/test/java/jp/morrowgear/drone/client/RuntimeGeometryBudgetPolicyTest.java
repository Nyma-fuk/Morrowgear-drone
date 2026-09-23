package jp.morrowgear.drone.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class RuntimeGeometryBudgetPolicyTest {
    @Test void denseSceneKeepsOnlyFocusedClosestFullAndOneMedium() {
        UUID focused = id(20);
        var candidates = new ArrayList<RuntimeGeometryBudgetPolicy.Candidate>();
        for (int i = 0; i < 24; i++) candidates.add(new RuntimeGeometryBudgetPolicy.Candidate(id(i), i + 1, id(i).equals(focused)));
        var result = RuntimeGeometryBudgetPolicy.allocate(candidates);
        assertEquals(focused, result.getFirst().id());
        assertEquals(1, count(result, RuntimeGeometryBudgetPolicy.Detail.FULL));
        assertEquals(1, count(result, RuntimeGeometryBudgetPolicy.Detail.MEDIUM));
        assertEquals(22, count(result, RuntimeGeometryBudgetPolicy.Detail.FAR));
    }

    @Test void sparseScenePreservesFullDetailAndDistanceStillCapsQuality() {
        var result = RuntimeGeometryBudgetPolicy.allocate(List.of(
            new RuntimeGeometryBudgetPolicy.Candidate(id(1), 4, false),
            new RuntimeGeometryBudgetPolicy.Candidate(id(2), 9, false),
            new RuntimeGeometryBudgetPolicy.Candidate(id(3), 25 * 25, true)));
        assertEquals(RuntimeGeometryBudgetPolicy.Detail.FAR, result.getFirst().detail(), "focus cannot defeat the distance cap");
        assertEquals(2, count(result, RuntimeGeometryBudgetPolicy.Detail.FULL));
    }

    @Test void equalDistancesUseStableUuidOrdering() {
        var result = RuntimeGeometryBudgetPolicy.allocate(List.of(
            new RuntimeGeometryBudgetPolicy.Candidate(id(3), 9, false),
            new RuntimeGeometryBudgetPolicy.Candidate(id(1), 9, false),
            new RuntimeGeometryBudgetPolicy.Candidate(id(2), 9, false)));
        assertEquals(List.of(id(1), id(2), id(3)), result.stream().map(RuntimeGeometryBudgetPolicy.Assignment::id).toList());
    }

    @Test void denseCarrierUsesMediumEvenWhenCameraIsUnderTheHull() {
        assertEquals(RuntimeGeometryBudgetPolicy.Detail.FULL, RuntimeGeometryBudgetPolicy.carrier(4, 7));
        assertEquals(RuntimeGeometryBudgetPolicy.Detail.MEDIUM, RuntimeGeometryBudgetPolicy.carrier(4, 8));
        assertEquals(RuntimeGeometryBudgetPolicy.Detail.MEDIUM, RuntimeGeometryBudgetPolicy.carrier(100 * 100, 24));
        assertEquals(RuntimeGeometryBudgetPolicy.Detail.FAR, RuntimeGeometryBudgetPolicy.carrier(193 * 193, 1));
    }

    @Test void onlyFiniteDronesInsideTheCloseSceneConsumeAdaptiveSlots() {
        assertEquals(true, RuntimeGeometryBudgetPolicy.participates(0));
        assertEquals(true, RuntimeGeometryBudgetPolicy.participates(24 * 24));
        assertEquals(false, RuntimeGeometryBudgetPolicy.participates(Math.nextUp(24.0 * 24.0)));
        assertEquals(false, RuntimeGeometryBudgetPolicy.participates(Double.NaN));
        assertEquals(false, RuntimeGeometryBudgetPolicy.participates(-1));
    }

    private static long count(List<RuntimeGeometryBudgetPolicy.Assignment> values, RuntimeGeometryBudgetPolicy.Detail detail) {
        return values.stream().filter(value -> value.detail() == detail).count();
    }

    private static UUID id(long value) { return new UUID(0, value); }
}
