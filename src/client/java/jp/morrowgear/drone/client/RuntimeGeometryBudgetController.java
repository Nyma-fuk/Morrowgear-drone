package jp.morrowgear.drone.client;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import jp.morrowgear.drone.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Render-thread cache for the current deterministic scene allocation. */
final class RuntimeGeometryBudgetController {
    private static Object level;
    private static long gameTime = Long.MIN_VALUE;
    private static Vec3 camera = new Vec3(Double.NaN, Double.NaN, Double.NaN);
    private static UUID focused;
    private static Map<UUID, RuntimeGeometryBudgetPolicy.Detail> drones = Map.of();
    private static int visibleDrones;

    private RuntimeGeometryBudgetController() {}

    static RuntimeGeometryBudgetPolicy.Detail drone(UUID id, Vec3 cameraPosition) {
        refresh(cameraPosition);
        return drones.getOrDefault(id, RuntimeGeometryBudgetPolicy.Detail.FAR);
    }

    static RuntimeGeometryBudgetPolicy.Detail carrier(double distanceSquared, Vec3 cameraPosition) {
        refresh(cameraPosition);
        return RuntimeGeometryBudgetPolicy.carrier(distanceSquared, visibleDrones);
    }

    private static void refresh(Vec3 cameraPosition) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            drones = Map.of(); visibleDrones = 0; return;
        }
        Entity picked = client.crosshairPickEntity;
        UUID nextFocused = picked instanceof DroneEntity ? picked.getUUID() : null;
        long nextTime = client.level.getGameTime();
        if (level == client.level && gameTime == nextTime && camera.equals(cameraPosition)
            && java.util.Objects.equals(focused, nextFocused)) return;
        var candidates = new java.util.ArrayList<RuntimeGeometryBudgetPolicy.Candidate>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof DroneEntity drone && !drone.isRemoved()) {
                double distance = cameraPosition.distanceToSqr(drone.position());
                if (RuntimeGeometryBudgetPolicy.participates(distance))
                    candidates.add(new RuntimeGeometryBudgetPolicy.Candidate(drone.getUUID(), distance,
                        drone.getUUID().equals(nextFocused)));
            }
        }
        var allocation = RuntimeGeometryBudgetPolicy.allocate(candidates);
        var next = new HashMap<UUID, RuntimeGeometryBudgetPolicy.Detail>();
        for (var assignment : allocation) next.put(assignment.id(), assignment.detail());
        level = client.level; gameTime = nextTime; camera = cameraPosition; focused = nextFocused;
        visibleDrones = candidates.size(); drones = Map.copyOf(next);
    }
}
