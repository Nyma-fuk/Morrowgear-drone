package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Server command preflight; preserves selection order before assigning counts or mutating units. */
final class OperatorCommandAdmission {
    private OperatorCommandAdmission() {}

    static <T> Selection<T> select(List<T> requested, Predicate<T> accepts) {
        List<T> accepted = new ArrayList<>(), rejected = new ArrayList<>();
        for (T candidate : requested) (accepts.test(candidate) ? accepted : rejected).add(candidate);
        return new Selection<>(List.copyOf(accepted), List.copyOf(rejected));
    }

    record Selection<T>(List<T> accepted, List<T> rejected) {
        boolean allAccepted() { return rejected.isEmpty(); }
    }
}
