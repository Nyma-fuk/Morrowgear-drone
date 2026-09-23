package jp.morrowgear.drone.carrier;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Stable target selection and safety geometry for scan rays and the broad fire cone. */
public final class CarrierCombatPolicy {
    public static final int HIT_ENERGY = 40, CHARGE_ENERGY = 2;
    public static final double IMPACT_RADIUS = CarrierPolicy.COMBAT_IMPACT_RADIUS;
    // Even at the 128-contact limit each target receives six pulses per full cycle.
    public static final float DAMAGE = 100;
    private CarrierCombatPolicy() {}
    public record Threat<T extends Comparable<? super T>>(T id, float maxHealth, double distanceSquared) {
        public Threat {
            if (id == null || !Float.isFinite(maxHealth) || maxHealth < 0
                || !Double.isFinite(distanceSquared) || distanceSquared < 0) throw new IllegalArgumentException("Invalid threat");
        }
    }
    /** Highest maximum health wins; distance and stable identity break ties. */
    public static <T extends Comparable<? super T>> Optional<T> selectPrimary(List<Threat<T>> threats) {
        Threat<T> best = null;
        for (Threat<T> candidate : threats) {
            if (best == null || candidate.maxHealth() > best.maxHealth()
                || candidate.maxHealth() == best.maxHealth() && candidate.distanceSquared() < best.distanceSquared()
                || candidate.maxHealth() == best.maxHealth() && candidate.distanceSquared() == best.distanceSquared()
                    && candidate.id().compareTo(best.id()) < 0) best = candidate;
        }
        return best == null ? Optional.empty() : Optional.of(best.id());
    }
    public static <T extends Comparable<? super T>> Optional<T> retainOrSelectPrimary(T current, List<Threat<T>> threats) {
        if (current != null && threats.stream().anyMatch(threat -> threat.id().equals(current))) return Optional.of(current);
        return selectPrimary(threats);
    }
    public static List<Vec3> converge(List<Vec3> scanAims, Vec3 primary, int tick, int duration) {
        if (primary == null || duration <= 0) return List.of();
        if (scanAims.isEmpty()) return List.of(primary);
        double progress = Math.clamp((tick + 1.0) / duration, 0, 1);
        return scanAims.stream().limit(CarrierPolicy.COMBAT_TARGETS)
            .map(aim -> aim.lerp(primary, progress)).toList();
    }
    public static List<Integer> rotation(int size, int cursor) {
        if (size <= 0) return List.of();
        int start = Math.floorMod(cursor, size);
        return IntStream.range(0, Math.min(size, CarrierPolicy.COMBAT_TARGETS)).map(i -> (start + i) % size).boxed().toList();
    }
    public static boolean inCylinder(Vec3 point, double x, double z, double minY, double originY) {
        double dx = point.x - x, dz = point.z - z;
        return point.y >= minY && point.y < originY && dx * dx + dz * dz <= CarrierPolicy.COMBAT_RADIUS * CarrierPolicy.COMBAT_RADIUS;
    }
    public static boolean intersectsFriendly(Vec3 from, Vec3 to, AABB friendly) {
        AABB volume = friendly.inflate(CarrierPolicy.MAX_BEAM_RADIUS);
        return volume.contains(from) || volume.contains(to) || volume.clip(from, to).isPresent();
    }
    /** Conservative body intersection against the rendered tapered cone. */
    public static boolean intersectsAreaBeam(Vec3 from, Vec3 to, AABB friendly) {
        Vec3 axis = to.subtract(from);
        double lengthSquared = axis.lengthSqr();
        if (lengthSquared <= 1.0e-9) return false;
        Vec3 body = friendly.getCenter();
        double t = Math.clamp(body.subtract(from).dot(axis) / lengthSquared, 0, 1);
        double radius = CarrierPolicy.MAX_BEAM_RADIUS + (IMPACT_RADIUS - CarrierPolicy.MAX_BEAM_RADIUS) * t;
        double bodyRadius = Math.sqrt(friendly.getXsize() * friendly.getXsize()
            + friendly.getYsize() * friendly.getYsize() + friendly.getZsize() * friendly.getZsize()) * .5;
        return body.distanceToSqr(from.add(axis.scale(t))) <= (radius + bodyRadius) * (radius + bodyRadius);
    }
    public static boolean inImpactArea(Vec3 point, Vec3 center) {
        return point.distanceToSqr(center) <= IMPACT_RADIUS * IMPACT_RADIUS;
    }
    public static boolean intersectsImpactArea(AABB body, Vec3 center) {
        double x = Math.clamp(center.x, body.minX, body.maxX);
        double y = Math.clamp(center.y, body.minY, body.maxY);
        double z = Math.clamp(center.z, body.minZ, body.maxZ);
        return center.distanceToSqr(x, y, z) <= IMPACT_RADIUS * IMPACT_RADIUS;
    }
    public static AABB searchBounds(double x, double z, double minY, double originY) {
        double margin = Math.ceil(CarrierPolicy.MAX_BEAM_RADIUS);
        double extent = CarrierPolicy.COMBAT_RADIUS + margin;
        return new AABB(x - extent, minY - margin, z - extent, x + extent, originY + margin, z + extent);
    }
    public static <T> List<T> retainBeams(List<T> targets, java.util.function.Predicate<T> safe) {
        return targets.stream().limit(CarrierPolicy.COMBAT_TARGETS).filter(safe).toList();
    }
}
