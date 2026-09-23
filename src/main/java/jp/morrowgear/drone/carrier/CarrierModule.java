package jp.morrowgear.drone.carrier;

import java.util.UUID;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;

/** Parent entrypoint calls register(); this module does not modify the existing mod initializer. */
public final class CarrierModule {
    public static EntityType<CarrierEntity> ENTITY;
    public static Item DEPLOYMENT_ITEM, CONSOLE_ITEM;
    public static ExtendedMenuType<CarrierMenu, CarrierViewPayload> MENU;
    private static boolean registered;
    public interface DronePorts { CarrierServiceBay.DronePort find(CarrierEntity carrier, UUID drone); }
    private static DronePorts ports = (carrier, drone) -> null;
    private CarrierModule() {}
    public static Identifier id(String path) { return Identifier.fromNamespaceAndPath("morrowgear_drone", path); }
    public static void installDronePorts(DronePorts integration) { ports = java.util.Objects.requireNonNull(integration); }
    static DronePorts dronePorts() { return ports; }
    /** Drone ticks should clear their temporary role when this becomes false, even after their own reload. */
    public static boolean serviceAvailable(MinecraftServer server, UUID shipId, UUID droneId) {
        CarrierEntity entity = find(server, shipId);
        CarrierShip ship = CarrierSavedData.get(server).ship(shipId);
        if (entity == null || ship == null || ship.destroyed) return false;
        var owner = server.getPlayerList().getPlayer(ship.owner);
        return owner != null && entity.controls(owner)
            && ship.bay.snapshot().stream().anyMatch(lease -> lease.identity().drone().equals(droneId));
    }

    /** Explicit owner request; not an automatic replacement for a drone's HomeDock. */
    public static boolean reserveBay(ServerPlayer owner, UUID shipId, UUID droneId) {
        CarrierEntity carrier = find(owner.level().getServer(), shipId);
        if (carrier == null || !carrier.controls(owner)) return false;
        CarrierShip ship = carrier.ship();
        var port = ports.find(carrier, droneId);
        if (ship == null || ship.destroyed || port == null || !port.available()
            || !ship.owner.equals(port.identity().owner())
            || !droneId.equals(port.identity().drone()) || port.position().distanceToSqr(carrier.position()) > 64 * 64) return false;
        for (var other : CarrierSavedData.get(owner.level().getServer()).ships().entrySet()) {
            if (other.getKey().equals(shipId)) continue;
            if (other.getValue().bay.snapshot().stream().anyMatch(l -> l.identity().drone().equals(droneId))) {
                if (find(owner.level().getServer(), other.getKey()) != null && !other.getValue().destroyed) return false;
                other.getValue().bay.release(droneId);
                port.release(CarrierServiceBay.Release.SHIP_UNAVAILABLE);
            }
        }
        var lease = ship.bay.reserve(owner.getUUID(), port.identity(), carrier.level().getGameTime());
        if (lease.isEmpty()) return false;
        // Bind the drone's temporary role before a manual command can arrive ahead of the first bay tick.
        port.approach(carrier.bayApproachPosition(lease.get().slot()), carrier.bayVelocity(lease.get().slot()));
        return true;
    }

    public static void register() {
        if (registered) return;
        registered = true;
        var entityKey = ResourceKey.create(Registries.ENTITY_TYPE, id("chunkbuster_carrier"));
        ENTITY = Registry.register(BuiltInRegistries.ENTITY_TYPE, entityKey,
            EntityType.Builder.<CarrierEntity>of(CarrierEntity::new, MobCategory.MISC)
                .sized((float) CarrierPolicy.WIDTH, (float) CarrierPolicy.HEIGHT)
                .clientTrackingRange(16).updateInterval(2).fireImmune().build(entityKey));
        var itemKey = ResourceKey.create(Registries.ITEM, id("carrier_unit"));
        DEPLOYMENT_ITEM = Registry.register(BuiltInRegistries.ITEM, itemKey,
            new CarrierDeploymentItem(new Item.Properties().setId(itemKey).stacksTo(1).fireResistant()));
        var consoleKey = ResourceKey.create(Registries.ITEM, id("carrier_console"));
        CONSOLE_ITEM = Registry.register(BuiltInRegistries.ITEM, consoleKey,
            new CarrierConsoleItem(new Item.Properties().setId(consoleKey).stacksTo(1)));
        MENU = Registry.register(BuiltInRegistries.MENU, id("carrier"), new ExtendedMenuType<>(CarrierMenu::new, CarrierViewPayload.CODEC));
        PayloadTypeRegistry.serverboundPlay().register(CarrierCommandPayload.TYPE, CarrierCommandPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CarrierViewPayload.TYPE, CarrierViewPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(CarrierCommandPayload.TYPE,
            (payload, context) -> context.server().execute(() -> CarrierCommands.handle(payload, context.player())));
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(DEPLOYMENT_ITEM); entries.accept(CONSOLE_ITEM);
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> { CarrierInterior.tick(server); CarrierChunkLeases.tick(server); });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { CarrierInterior.reset(); CarrierChunkLeases.clear(); });
        ServerPlayerEvents.JOIN.register(CarrierInterior::recover);
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> CarrierInterior.recover(newPlayer));
        // Remote mining coordinates belong to the event level, not the operator's cabin dimension.
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
            !CarrierInterior.inside(level) || !(player instanceof ServerPlayer serverPlayer)
                || (serverPlayer.level() == level && CarrierInterior.allowed(serverPlayer, pos, true)));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
            if (!CarrierInterior.inside(level)) {
                if (player.getItemInHand(hand).getItem() instanceof BlockItem) {
                    var center = hit.getBlockPos().relative(hit.getDirection());
                    // Conservative attempted-placement footprint, including beds/doors/replaced plants.
                    for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++)
                        CarrierProtection.recordPlayerBuild(serverPlayer.level(), center.offset(dx, dy, dz));
                }
                return InteractionResult.PASS;
            }
            UUID shipId = CarrierInterior.currentShip(serverPlayer);
            CarrierShip ship = shipId == null ? null : CarrierSavedData.get(serverPlayer.level().getServer()).ship(shipId);
            if (ship != null && ship.permits(player.getUUID()) && CarrierPolicy.reservedEntry(ship.cabin,
                hit.getBlockPos().getX(), hit.getBlockPos().getY(), hit.getBlockPos().getZ())) {
                if (player.isShiftKeyDown()) CarrierInterior.exit(serverPlayer); else CarrierMenu.open(serverPlayer, shipId);
                return InteractionResult.SUCCESS;
            }
            if (!CarrierInterior.allowedItem(player.getItemInHand(hand))) return InteractionResult.FAIL;
            if (player.getItemInHand(hand).getItem() instanceof BlockItem blockItem) {
                // The bedrock floor/walls may support a placement, but must never be replaced.
                var placement = new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit);
                return CarrierInterior.allowedPlacement(serverPlayer, blockItem, placement)
                    ? InteractionResult.PASS : InteractionResult.FAIL;
            }
            if (!CarrierInterior.allowed(serverPlayer, hit.getBlockPos(), false)) return InteractionResult.FAIL;
            if (!player.getItemInHand(hand).isEmpty()
                && (!CarrierInterior.allowed(serverPlayer, hit.getBlockPos(), true)
                    || !CarrierInterior.allowed(serverPlayer, hit.getBlockPos().relative(hit.getDirection()), true))) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, level, hand) ->
            CarrierInterior.inside(level) && !CarrierInterior.allowedItem(player.getItemInHand(hand))
                ? InteractionResult.FAIL : InteractionResult.PASS);
        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            player instanceof ServerPlayer serverPlayer && CarrierInterior.inside(level)
                && (!CarrierInterior.allowed(serverPlayer, entity.blockPosition(), false)
                    || !CarrierInterior.allowedItem(player.getItemInHand(hand))) ? InteractionResult.FAIL : InteractionResult.PASS);
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
            player instanceof ServerPlayer serverPlayer && CarrierInterior.inside(level)
                && !CarrierInterior.allowed(serverPlayer, entity.blockPosition(), false) ? InteractionResult.FAIL : InteractionResult.PASS);
    }
    public static CarrierEntity find(MinecraftServer server, UUID id) {
        for (var level : server.getAllLevels()) {
            var entity = level.getEntity(id);
            if (entity instanceof CarrierEntity carrier && !carrier.isRemoved()) return carrier;
        }
        return null;
    }
}
