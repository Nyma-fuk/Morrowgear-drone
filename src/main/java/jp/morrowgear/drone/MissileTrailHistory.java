package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Bounded client exhaust history; never participates in guidance or collision. */
public final class MissileTrailHistory {
	public static final int LIFETIME_TICKS = 18;
	public static final int MAX_POINTS = 20;
	private final Deque<Point> points = new ArrayDeque<>();
	private long lastTick = Long.MIN_VALUE;
	private Vec3 lastPosition;

	public void tick(Vec3 position, long tick, boolean emitting) {
		if (tick == lastTick) return;
		if (!MicroMissilePolicy.finite(position)) { points.clear(); lastPosition = null; return; }
		if (lastPosition != null && (tick < lastTick || tick - lastTick > 4
			|| position.distanceToSqr(lastPosition) > 64)) points.clear();
		lastTick = tick;
		lastPosition = position;
		points.removeIf(point -> tick - point.tick() >= LIFETIME_TICKS);
		Point previous = points.peekLast();
		if (emitting && (previous == null || position.distanceToSqr(previous.position()) > 0.0001))
			points.addLast(new Point(position, tick));
		while (points.size() > MAX_POINTS) points.removeFirst();
	}

	public List<Point> points() { return List.copyOf(points); }

	public record Point(Vec3 position, long tick) {
		public float alpha(double now) {
			double remaining = Math.clamp(1.0 - (now - tick) / LIFETIME_TICKS, 0.0, 1.0);
			return (float)(remaining * remaining);
		}
	}
}
