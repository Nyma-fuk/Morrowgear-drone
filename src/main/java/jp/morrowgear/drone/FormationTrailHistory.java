package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Client visual history only; no flight or mission decisions are changed here. */
public final class FormationTrailHistory {
	private final Deque<Point> points = new ArrayDeque<>();
	private Vec3 lastPosition;
	private long lastTick = Long.MIN_VALUE;
	private int activeTicks;
	private int strip;
	private boolean emitting;
	private FormationTrailPolicy.TrailStyle style = FormationTrailPolicy.TrailStyle.NONE;
	private int color = FormationLightTrailProfile.CYAN;
	private int blendFrom = color;
	private int blendTo = color;
	private long blendStart;

	public void tick(FormationTrailPolicy.TrailStyle nextStyle, Vec3 position, Vec3 left, Vec3 right,
		long tick, boolean moving, int activationDelay) {
		if (lastPosition != null && FormationLightTrailProfile.discontinuity(
			position.distanceTo(lastPosition), tick - lastTick)) {
			points.clear();
			emitting = false;
			activeTicks = 0;
		}
		if (lastTick == tick) return;
		lastPosition = position;
		lastTick = tick;
		boolean active = nextStyle != FormationTrailPolicy.TrailStyle.NONE;
		if (active && nextStyle != style) {
			blendFrom = color;
			blendTo = FormationLightTrailProfile.color(nextStyle);
			blendStart = tick;
		}
		color = FormationLightTrailProfile.blendColor(blendFrom, blendTo,
			(float) (tick - blendStart) / FormationLightTrailProfile.STYLE_BLEND_TICKS);
		style = nextStyle;
		activeTicks = active ? activeTicks + 1 : 0;
		boolean immediate = style == FormationTrailPolicy.TrailStyle.COMBAT_ENTRY
			|| style == FormationTrailPolicy.TrailStyle.SERVICE_RETURN || style == FormationTrailPolicy.TrailStyle.REJOIN;
		boolean nextEmitting = active && moving && (immediate || activeTicks > activationDelay);
		if (emitting && !nextEmitting) {
			List<Point> retired = points.stream().map(point -> point.retire(tick)).toList();
			points.clear();
			points.addAll(retired);
		}
		if (!emitting && nextEmitting) strip++;
		emitting = nextEmitting;
		if (emitting) {
			Point newest = points.peekLast();
			double distance = newest == null ? Double.POSITIVE_INFINITY
				: Math.max(left.distanceTo(newest.left), right.distanceTo(newest.right));
			if (newest == null || newest.strip != strip || (distance > 0.0001
				&& FormationLightTrailProfile.shouldSample(distance, tick - newest.tick))) {
				points.addLast(new Point(left, right, tick, color, strip, Long.MAX_VALUE));
			}
		}
		points.removeIf(point -> tick - point.tick >= FormationLightTrailProfile.LIFETIME_TICKS
			|| (point.stoppedAt != Long.MAX_VALUE && tick - point.stoppedAt >= FormationLightTrailProfile.STOP_FADE_TICKS));
		while (points.size() > FormationLightTrailProfile.MAX_CONTROL_POINTS) points.removeFirst();
	}

	public List<Point> points() { return List.copyOf(points); }
	public boolean active() { return style != FormationTrailPolicy.TrailStyle.NONE; }
	public boolean emitting() { return emitting; }
	public boolean hasPoints() { return !points.isEmpty(); }

	public record Point(Vec3 left, Vec3 right, long tick, int color, int strip, long stoppedAt) {
		private Point retire(long now) {
			return stoppedAt != Long.MAX_VALUE ? this : new Point(left, right, tick, color, strip, now);
		}

		public Point at(Vec3 newLeft, Vec3 newRight) {
			return new Point(newLeft, newRight, tick, color, strip, stoppedAt);
		}

		public float alpha(double now) {
			float ageAlpha = FormationLightTrailProfile.alpha((float) (now - tick));
			return stoppedAt == Long.MAX_VALUE ? ageAlpha
				: ageAlpha * FormationLightTrailProfile.stopAlpha((float) (now - stoppedAt));
		}
	}
}
