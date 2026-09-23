package jp.morrowgear.drone.client;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.FormationTrailHistory;
import jp.morrowgear.drone.FormationTrailHistory.Point;
import jp.morrowgear.drone.FormationTrailPolicy.TrailStyle;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierTrailPolicy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Tick-sampled world-space trails, independent of frustum visibility and render FPS. */
final class CarrierTrailController {
    private static final Map<UUID, Track> tracks = new HashMap<>();
    private static ClientLevel level;
    private static long tick = Long.MIN_VALUE;
    private CarrierTrailController() {}

    static void tick(Minecraft client) {
        if (level != client.level || client.player == null) {
            tracks.clear(); level = client.level; tick = Long.MIN_VALUE;
        }
        if (level == null || client.player == null || client.isPaused()) return;
        long now = level.getGameTime();
        if (now == tick) return;
        if (now < tick) tracks.clear();
        tick = now;
        var carriers = new java.util.ArrayList<CarrierEntity>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(CarrierEntity.class),
            client.player.getBoundingBox().inflate(CarrierTrailPolicy.RANGE), entity -> entity.isAlive(), carriers, 32);
        carriers.sort(Comparator.comparingDouble(client.player::distanceToSqr));
        var seen = new HashSet<UUID>();
        for (CarrierEntity carrier : carriers.stream().limit(CarrierTrailPolicy.MAX_TRACKED).toList()) {
            UUID id = carrier.getUUID(); seen.add(id);
            Track track = tracks.computeIfAbsent(id, ignored -> new Track());
            boolean moving = track.position != null && CarrierTrailPolicy.emitting(carrier.workMode(),
                carrier.position().distanceTo(track.position), now - track.tick);
            if (moving) track.stationaryTicks = 0;
            else track.stationaryTicks++;
            // Entity position packets arrive every other client tick. Do not retire an
            // otherwise continuous exhaust strip during that single duplicate sample.
            boolean holdPacketGap = !moving && track.history.emitting()
                && track.stationaryTicks <= CarrierTrailPolicy.PACKET_GAP_TICKS;
            if (!holdPacketGap) track.history.tick(moving ? TrailStyle.NAVIGATION : TrailStyle.NONE, carrier.position(),
                carrier.position().add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, carrier.getYRot())),
                carrier.position().add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.RIGHT, carrier.getYRot())),
                now, moving, 0);
            track.position = carrier.position(); track.tick = now;
        }
        tracks.keySet().removeIf(id -> !seen.contains(id));
    }

    static List<Point> points(CarrierEntity entity) {
        Track track = entity.level() == level ? tracks.get(entity.getUUID()) : null;
        return track == null ? List.of() : track.history.points();
    }

    static boolean emitting(CarrierEntity entity) {
        Track track = entity.level() == level ? tracks.get(entity.getUUID()) : null;
        return track != null && track.history.emitting();
    }

    static AABB bounds(CarrierEntity entity) {
        AABB result = entity.getBoundingBox();
        for (Point point : points(entity)) result = result.minmax(new AABB(point.left(), point.right()).inflate(1));
        return result;
    }

    private static final class Track {
        final FormationTrailHistory history = new FormationTrailHistory();
        Vec3 position;
        long tick;
        int stationaryTicks;
    }
}
