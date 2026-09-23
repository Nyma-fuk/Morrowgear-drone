package jp.morrowgear.drone.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Deterministic mesh quality allocation for one visible drone scene. */
public final class RuntimeGeometryBudgetPolicy {
    public enum Detail { FULL, MEDIUM, FAR }
    public record Candidate(UUID id, double distanceSquared, boolean focused) {}
    public record Assignment(UUID id, Detail detail) {}

    private static final double FULL_DISTANCE_SQUARED = 12 * 12;
    private static final double MEDIUM_DISTANCE_SQUARED = 24 * 24;
    private static final double CARRIER_FULL_DISTANCE_SQUARED = 64 * 64;
    private static final double CARRIER_FAR_DISTANCE_SQUARED = 192 * 192;

    private RuntimeGeometryBudgetPolicy() {}

    public static List<Assignment> allocate(List<Candidate> candidates) {
        var ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparing(Candidate::focused).reversed()
            .thenComparingDouble(Candidate::distanceSquared).thenComparing(Candidate::id));
        int visible = ordered.size();
        int fullRemaining = visible >= 8 ? 1 : visible >= 4 ? 1 : 2;
        int mediumRemaining = visible >= 8 ? 1 : visible >= 4 ? 3 : 4;
        var result = new ArrayList<Assignment>(visible);
        for (Candidate candidate : ordered) {
            Detail detail;
            if (fullRemaining > 0 && candidate.distanceSquared <= FULL_DISTANCE_SQUARED) {
                detail = Detail.FULL; fullRemaining--;
            } else if (mediumRemaining > 0 && candidate.distanceSquared <= MEDIUM_DISTANCE_SQUARED) {
                detail = Detail.MEDIUM; mediumRemaining--;
            } else detail = Detail.FAR;
            result.add(new Assignment(candidate.id, detail));
        }
        return List.copyOf(result);
    }

    public static boolean participates(double distanceSquared) {
        return Double.isFinite(distanceSquared) && distanceSquared >= 0 && distanceSquared <= MEDIUM_DISTANCE_SQUARED;
    }

    public static Detail carrier(double distanceSquared, int visibleDrones) {
        if (distanceSquared > CARRIER_FAR_DISTANCE_SQUARED) return Detail.FAR;
        if (distanceSquared <= CARRIER_FULL_DISTANCE_SQUARED && visibleDrones < 8) return Detail.FULL;
        return Detail.MEDIUM;
    }
}
