package jp.morrowgear.drone.carrier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import net.minecraft.world.phys.Vec3;

/** Bounded local detours for the carrier's large hull during physical cargo transfer. */
final class CarrierTransferRoutePlanner {
    static final double LOCAL_HORIZON = 48.0;
    static final double SIDE_CLEARANCE = CarrierPolicy.WIDTH / 2.0 + 6.0;
    static final double BACKTRACK = 16.0;
    static final double[] ALTITUDE_OFFSETS = {8.0, 16.0, 32.0, 64.0};
    static final int MAX_CORRIDOR_CHECKS = 48;

    record Plan(List<Vec3> waypoints, double approachY, double distance, int checks, String strategy) {
        Plan {
            waypoints = List.copyOf(waypoints);
        }
    }

    private record Edge(Vec3 from, Vec3 to) {}
    private record Candidate(List<Vec3> checked, Vec3 finalTarget, double approachY, String strategy) {}

    private CarrierTransferRoutePlanner() {}

    static Optional<Plan> plan(Vec3 current, Vec3 baseTarget, double ceiling, float fallbackYaw,
                               BiPredicate<Vec3, Vec3> corridorClear) {
        if (current == null || baseTarget == null || corridorClear == null
            || !finite(current) || !finite(baseTarget) || !Double.isFinite(ceiling)) return Optional.empty();

        Map<Edge, Boolean> cache = new HashMap<>();
        int[] checks = {0};
        Candidate direct = direct(current, baseTarget, baseTarget.y, fallbackYaw, "DIRECT");
        if (clear(current, direct, corridorClear, cache, checks))
            return Optional.of(result(current, direct, checks[0]));

        List<Candidate> candidates = new ArrayList<>();
        addLateral(candidates, current, baseTarget, baseTarget.y, fallbackYaw, "LEVEL");
        for (double offset : ALTITUDE_OFFSETS) {
            double altitude = Math.max(current.y, baseTarget.y) + offset;
            if (altitude > ceiling + 1.0e-6) continue;
            candidates.add(direct(current, withY(baseTarget, altitude), altitude, fallbackYaw,
                "ALTITUDE_" + (int) offset));
        }

        Candidate best = bestClear(current, candidates, corridorClear, cache, checks);
        if (best == null) {
            List<Candidate> elevatedLateral = new ArrayList<>();
            for (double offset : ALTITUDE_OFFSETS) {
                double altitude = Math.max(current.y, baseTarget.y) + offset;
                if (altitude > ceiling + 1.0e-6) continue;
                addLateral(elevatedLateral, current, withY(baseTarget, altitude), altitude,
                    fallbackYaw, "ELEVATED_" + (int) offset);
            }
            best = bestClear(current, elevatedLateral, corridorClear, cache, checks);
        }
        return best == null ? Optional.empty() : Optional.of(result(current, best, checks[0]));
    }

    private static Candidate bestClear(Vec3 current, List<Candidate> candidates,
                                       BiPredicate<Vec3, Vec3> corridorClear,
                                       Map<Edge, Boolean> cache, int[] checks) {
        Candidate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Candidate candidate : candidates) {
            if (checks[0] >= MAX_CORRIDOR_CHECKS
                || !clear(current, candidate, corridorClear, cache, checks)) continue;
            double distance = distance(current, route(candidate));
            if (distance + 1.0e-6 < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static Candidate direct(Vec3 current, Vec3 finalTarget, double altitude, float fallbackYaw,
                                    String strategy) {
        Vec3 planeStart = withY(current, altitude);
        Vec3 local = localTarget(planeStart, finalTarget, fallbackYaw);
        List<Vec3> checked = new ArrayList<>();
        addDistinct(checked, planeStart, current);
        addDistinct(checked, local, checked.isEmpty() ? current : checked.getLast());
        return new Candidate(List.copyOf(checked), finalTarget, altitude, strategy);
    }

    private static void addLateral(List<Candidate> out, Vec3 current, Vec3 finalTarget,
                                   double altitude, float fallbackYaw, String prefix) {
        Vec3 planeStart = withY(current, altitude);
        Vec3 horizontal = new Vec3(finalTarget.x - current.x, 0, finalTarget.z - current.z);
        Vec3 forward = horizontal.lengthSqr() > 1.0e-8
            ? horizontal.normalize()
            : CarrierNavigation.rotate(new Vec3(0, 0, -1), fallbackYaw);
        Vec3 right = new Vec3(-forward.z, 0, forward.x);
        Vec3 local = localTarget(planeStart, finalTarget, fallbackYaw);
        for (int sign : new int[] {-1, 1}) {
            Vec3 side = right.scale(SIDE_CLEARANCE * sign);
            Vec3 sideStart = planeStart.add(side);
            Vec3 sideForward = local.add(side);
            out.add(candidate(current, finalTarget, altitude, prefix + (sign < 0 ? "_LEFT" : "_RIGHT"),
                planeStart, sideStart, sideForward, local));

            Vec3 back = planeStart.add(forward.scale(-BACKTRACK));
            Vec3 backSide = back.add(side);
            out.add(candidate(current, finalTarget, altitude,
                prefix + (sign < 0 ? "_BACK_LEFT" : "_BACK_RIGHT"),
                planeStart, back, backSide, sideForward, local));
        }
    }

    private static Candidate candidate(Vec3 current, Vec3 finalTarget, double altitude, String strategy,
                                       Vec3... points) {
        List<Vec3> checked = new ArrayList<>();
        Vec3 previous = current;
        for (Vec3 point : points) {
            addDistinct(checked, point, previous);
            if (!checked.isEmpty()) previous = checked.getLast();
        }
        return new Candidate(List.copyOf(checked), finalTarget, altitude, strategy);
    }

    private static Vec3 localTarget(Vec3 from, Vec3 finalTarget, float fallbackYaw) {
        Vec3 horizontal = new Vec3(finalTarget.x - from.x, 0, finalTarget.z - from.z);
        if (horizontal.lengthSqr() < 1.0e-8)
            return new Vec3(finalTarget.x, from.y, finalTarget.z);
        double distance = Math.min(LOCAL_HORIZON, horizontal.length());
        Vec3 direction = horizontal.lengthSqr() < 1.0e-8
            ? CarrierNavigation.rotate(new Vec3(0, 0, -1), fallbackYaw)
            : horizontal.normalize();
        return new Vec3(from.x + direction.x * distance, from.y, from.z + direction.z * distance);
    }

    private static boolean clear(Vec3 current, Candidate candidate, BiPredicate<Vec3, Vec3> corridorClear,
                                 Map<Edge, Boolean> cache, int[] checks) {
        Vec3 previous = current;
        for (Vec3 point : candidate.checked()) {
            Edge edge = new Edge(previous, point);
            Boolean result = cache.get(edge);
            if (result == null) {
                if (checks[0] >= MAX_CORRIDOR_CHECKS) return false;
                result = corridorClear.test(previous, point);
                cache.put(edge, result);
                checks[0]++;
            }
            if (!result) return false;
            previous = point;
        }
        return true;
    }

    private static Plan result(Vec3 current, Candidate candidate, int checks) {
        List<Vec3> route = route(candidate);
        return new Plan(route, candidate.approachY(), distance(current, route), checks, candidate.strategy());
    }

    private static List<Vec3> route(Candidate candidate) {
        List<Vec3> route = new ArrayList<>(candidate.checked());
        Vec3 previous = route.isEmpty() ? null : route.getLast();
        if (previous == null || previous.distanceToSqr(candidate.finalTarget()) > 1.0e-8)
            route.add(candidate.finalTarget());
        return List.copyOf(route);
    }

    private static double distance(Vec3 current, List<Vec3> route) {
        double distance = 0;
        Vec3 previous = current;
        for (Vec3 point : route) {
            distance += previous.distanceTo(point);
            previous = point;
        }
        return distance;
    }

    private static void addDistinct(List<Vec3> points, Vec3 point, Vec3 previous) {
        if (point.distanceToSqr(previous) > 1.0e-8) points.add(point);
    }

    private static Vec3 withY(Vec3 value, double y) { return new Vec3(value.x, y, value.z); }
    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
