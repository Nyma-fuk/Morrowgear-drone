package jp.morrowgear.drone.carrier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/** Four attended ships, each holding at most its current and one forward chunk ticket. */
final class CarrierChunkLeases {
    static final int MAX_LEASES = CarrierPolicy.MAX_ACTIVE_CHUNK_LEASES;
    static final int RADIUS = CarrierNavigation.TICKET_RADIUS;
    private static final TicketType TYPE = new TicketType(40,
        TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE
            | TicketType.FLAG_CAN_EXPIRE_IF_UNLOADED);
    private record Lease(ServerLevel level, Set<ChunkPos> chunks, long firstTick) {}
    private static final Map<UUID, Lease> ACTIVE = new HashMap<>();
    private static final Set<UUID> FAILED = new HashSet<>();
    private CarrierChunkLeases() {}

    static void tick(MinecraftServer server) {
        Set<UUID> wanted = new HashSet<>();
        var data = CarrierSavedData.get(server);
        for (var player : server.getPlayerList().getPlayers()) {
            UUID cabin = CarrierInterior.currentShip(player);
            for (var entry : data.ships().entrySet())
                if (wantsLease(entry.getKey(), entry.getValue(), player.getUUID(), cabin, player.isAlive())) wanted.add(entry.getKey());
        }
        FAILED.retainAll(wanted);
        for (UUID id : Set.copyOf(ACTIVE.keySet())) if (!wanted.contains(id)) release(id);
        for (UUID id : wanted.stream().sorted().toList()) {
            if (FAILED.contains(id)) continue;
            if (!ACTIVE.containsKey(id) && ACTIVE.size() >= MAX_LEASES) {
                data.ship(id).stop(CarrierPolicy.Stop.LEASE_LIMIT);
                continue;
            }
            CarrierShip ship = data.ship(id);
            CarrierEntity exterior = CarrierModule.find(server, id);
            CarrierAnchor anchor = exterior == null ? ship.exterior : CarrierAnchor.at(exterior);
            ServerLevel level = anchor.level(server);
            if (level == null || CarrierInterior.inside(level)) continue;
            ChunkPos chunk = new ChunkPos(anchor.x() >> 4, anchor.z() >> 4);
            Set<ChunkPos> chunks = new HashSet<>();
            chunks.add(chunk);
            if (exterior != null && ship.destination != null && !ship.navigationPaused) {
                var ahead = CarrierNavigation.ahead(exterior.position(), CarrierNavigation.destination(ship.destination));
                chunks.add(new ChunkPos(ahead.getX() >> 4, ahead.getZ() >> 4));
            }
            Lease old = ACTIVE.get(id);
            if (exterior == null && old != null && server.getTickCount() - old.firstTick > 200) {
                release(id); FAILED.add(id); ship.stop(CarrierPolicy.Stop.UNLOADED); continue;
            }
            long first = exterior != null || old == null ? server.getTickCount() : old.firstTick;
            ACTIVE.put(id, new Lease(level, Set.copyOf(chunks), first));
            if (old != null) removeUnused(old);
            for (ChunkPos wantedChunk : chunks) level.getChunkSource().addTicketWithRadius(TYPE, wantedChunk, RADIUS);
        }
    }
    static boolean wantsLease(UUID id, CarrierShip ship, UUID player, UUID cabin, boolean alive) {
        return alive && ship != null && !ship.destroyed && ship.owner.equals(player)
            && (id.equals(cabin) || ship.destination != null && !ship.navigationPaused);
    }
    private static void release(UUID id) {
        Lease lease = ACTIVE.remove(id);
        if (lease != null) removeUnused(lease);
    }
    private static void removeUnused(Lease lease) {
        for (ChunkPos chunk : lease.chunks)
            if (ACTIVE.values().stream().noneMatch(l -> l.level == lease.level && l.chunks.contains(chunk)))
                lease.level.getChunkSource().removeTicketWithRadius(TYPE, chunk, RADIUS);
    }
    static void requestResume(UUID ship) { FAILED.remove(ship); }
    static void clear() { ACTIVE.clear(); FAILED.clear(); }
}
