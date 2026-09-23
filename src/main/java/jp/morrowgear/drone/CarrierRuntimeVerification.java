package jp.morrowgear.drone;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jp.morrowgear.drone.carrier.CarrierAnchor;
import jp.morrowgear.drone.carrier.CarrierCommandPayload;
import jp.morrowgear.drone.carrier.CarrierCommands;
import jp.morrowgear.drone.carrier.CarrierCombatPolicy;
import jp.morrowgear.drone.carrier.CarrierEntity;
import jp.morrowgear.drone.carrier.CarrierInterior;
import jp.morrowgear.drone.carrier.CarrierMenu;
import jp.morrowgear.drone.carrier.CarrierModule;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierProtection;
import jp.morrowgear.drone.carrier.CarrierSavedData;
import jp.morrowgear.drone.carrier.CarrierServiceBay;
import jp.morrowgear.drone.carrier.CarrierShip;
import jp.morrowgear.drone.carrier.CarrierSupplies;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Explicit destructive development fixture. Never enabled by a world-name substring or by startup. */
public final class CarrierRuntimeVerification {
    public static final String WORLD_NAME = "MG Carrier V27 Verification";
    private static final String PREFIX = "[MORROWGEAR CARRIER VERIFY] ";
    private static final UUID NONE = new UUID(0, 0);
    // Explicitly authorized legacy retry: this old run predates persisted death observations.
    private static final UUID LEGACY_RETRY_RUN = UUID.fromString("3ff18a61-9ebf-4319-9b01-0c1fbcf37b04");
    private static final int MAX_TICKS = 2400;
    private static final int FIXTURE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static boolean registered;
    private static UUID boot = UUID.randomUUID();
    private static Run active;

    private CarrierRuntimeVerification() {}

    /** Parent calls once from common initialization, after CarrierModule and the drone adapter are registered. */
    public static void register() {
        if (registered) return;
        registered = true;
        CarrierAcceptanceVerification.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("morrowgear_carrier_verify")
                .then(Commands.literal("start").executes(c -> command(c.getSource(), "start")))
                .then(Commands.literal("status").executes(c -> command(c.getSource(), "status")))
                .then(Commands.literal("stop").executes(c -> command(c.getSource(), "stop")))
                .then(Commands.literal("retry_combat").then(Commands.argument("confirmed_run", UuidArgument.uuid())
                    .executes(c -> command(c.getSource(), "retry_combat", UuidArgument.getUuid(c, "confirmed_run")))))
                .then(Commands.literal("persistence_prepare").executes(c -> command(c.getSource(), "prepare")))
                .then(Commands.literal("persistence_check").executes(c -> command(c.getSource(), "check")))));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { boot = UUID.randomUUID(); active = null; });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active != null && active.server == server) active.fail("server stopped before runtime checks completed");
            active = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Run run = active;
            if (run == null || run.server != server) return;
            try { run.tick(); }
            catch (RuntimeException failure) { run.fail(failure.toString()); }
        });
        // Ordinary claim-protection path: even an extra scan in the final mining tick cannot break terrain.
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Run run = active;
            if (run == null || run.level != world || !player.getUUID().equals(run.proof.owner)) return true;
            return run.phase == Phase.MINING && run.mine.contains(pos) && state.is(Blocks.STONE);
        });
    }

    static boolean allowedWorld(String name, boolean singleplayer, boolean creative, int players) {
        return WORLD_NAME.equals(name) && singleplayer && creative && players == 1;
    }

    private static boolean allowed(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return allowedWorld(server.getWorldData().getLevelName(), server.isSingleplayer(), player.isCreative(),
            server.getPlayerList().getPlayers().size()) && player.isAlive() && !player.isSpectator();
    }

    private static int command(CommandSourceStack source, String action) {
        return command(source, action, NONE);
    }

    private static int command(CommandSourceStack source, String action, UUID confirmedRun) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !allowed(player)) {
            source.sendFailure(Component.literal(PREFIX + "REFUSED: dedicated world, singleplayer and creative player required"));
            return 0;
        }
        Proof proof = Proof.get(player.level().getServer());
        if (!proof.owner.equals(NONE) && !proof.owner.equals(player.getUUID())) {
            source.sendFailure(Component.literal(PREFIX + "REFUSED: another fixture owner"));
            return 0;
        }
        if (action.equals("retry_combat") && active != null) {
            source.sendFailure(Component.literal(PREFIX + "REFUSED: current verification is still running; duplicate retry ignored"));
            return 0;
        }
        try {
            require(!(action.equals("start") || action.equals("stop") || action.equals("retry_combat"))
                || !CarrierAcceptanceVerification.retained(player.level().getServer()),
                "extended verification retained: use /morrowgear_carrier_accept status; baseline fixture is protected");
            return switch (action) {
                case "start" -> start(player, proof);
                case "stop" -> cleanup(player, proof);
                case "retry_combat" -> retryCombat(player, proof, confirmedRun);
                case "prepare" -> prepare(player, proof);
                case "check" -> check(player, proof);
                default -> { say(player, proof.stage + " / " + proof.message + " / run=" + proof.run); yield 1; }
            };
        } catch (RuntimeException failure) {
            if (active != null) active.fail(failure.toString());
            else say(player, "REFUSED / " + failure.getMessage() + " / saved stage retained=" + proof.stage);
            return 0;
        }
    }

    private static int start(ServerPlayer player, Proof proof) {
        require(active == null && (proof.stage.equals("EMPTY") || proof.stage.equals("CLEANED")),
            "existing fixture: use status/stop; never spawn a second carrier");
        ServerLevel level = player.level();
        require(level == level.getServer().overworld(), "start in the exterior overworld");
        require(CarrierModule.ENTITY != null && CarrierModule.MENU != null, "carrier module is not registered");
        var live = CarrierSavedData.get(level.getServer()).ships().entrySet().stream().filter(e -> !e.getValue().destroyed).toList();
        require(live.size() <= 1, "more than one live carrier: fixture limit is one");
        CarrierEntity existing = live.isEmpty() ? null : CarrierModule.find(level.getServer(), live.getFirst().getKey());
        if (!live.isEmpty()) {
            require(existing != null && existing.level() == level && existing.controls(player)
                && existing.ship().owner.equals(player.getUUID()), "existing carrier must be loaded and owned by this player");
            CarrierShip ship = existing.ship();
            require(ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null && ship.preview == null
                && ship.progress == null && ship.pending == null && ship.bay.snapshot().isEmpty(),
                "existing carrier has work or a reservation; no mission takeover");
            require(ship.cargo.isEmpty() && ship.supplies.isEmpty(), "existing carrier cargo and supply slots must be empty; never overwrite items");
            require(CarrierSupplies.canCharge(ship.energy) && CarrierSupplies.canCharge(ship.weaponEnergy), "existing tanks need room for one test cell each");
        }
        int x = existing == null ? Math.floorDiv(player.blockPosition().getX() + 32, 16) * 16 + 8
            : (existing.blockPosition().getX() >> 4) * 16 + 8;
        int z = existing == null ? Math.floorDiv(player.blockPosition().getZ(), 16) * 16 + 8
            : (existing.blockPosition().getZ() >> 4) * 16 + 8;
        int inspected = 0;
        for (ServerLevel loaded : level.getServer().getAllLevels()) for (Entity entity : loaded.getAllEntities()) {
            require(++inspected <= 4096, "loaded entity preflight limit exceeded");
            require(!(entity instanceof DroneEntity drone) || !droneConflictsWithFixture(drone.blockPosition(), x, z),
                "existing small drone inside the bounded fixture envelope: leave it untouched and move the fixture");
        }
        int ground = level.getMinY();
        for (int dx = -16; dx <= 16; dx += 4) for (int dz = -28; dz <= 28; dz += 4) {
            require(level.hasChunkAt(new BlockPos(x + dx, 0, z + dz)), "deployment envelope is not loaded");
            ground = Math.max(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz));
        }
        int belly = ground + 18;
        require(belly + 12 < level.getMaxY(), "insufficient vertical deployment space");
        BlockPos arena = new BlockPos(x - 8, ground + 3, z - 8);
        for (BlockPos pos : BlockPos.betweenClosed(arena, arena.offset(15, 14, 15)))
            require(level.getBlockState(pos).isAir(), "fixture column is not empty: " + pos);
        require(level.getEntitiesOfClass(LivingEntity.class, new AABB(arena.getX(), level.getMinY(), arena.getZ(),
            arena.getX() + 16, belly + 12, arena.getZ() + 16), LivingEntity::isAlive).isEmpty(),
            "fixture column contains a living entity; player must stand outside chunk " + (x >> 4) + "," + (z >> 4));
        proof.reset(player, arena);
        active = new Run(level, proof);
        CarrierEntity carrier = existing == null ? CarrierModule.ENTITY.create(level, EntitySpawnReason.TRIGGERED) : existing;
        require(carrier != null, "carrier entity creation failed");
        proof.createdCarrier = existing == null;
        proof.ship = carrier.getUUID();
        proof.setDirty();
        carrier.addTag(proof.tag());
        active.carrier = carrier;
        if (existing == null) {
            require(carrier.deploy(player, new Vec3(x, belly, z)), "normal carrier deployment validation refused");
            require(level.addFreshEntity(carrier), "carrier spawn refused");
        }
        active.destination = new BlockPos(x, belly, z);
        CarrierShip ship = carrier.ship();
        require(ship != null && ship.owner.equals(player.getUUID()), "deployment did not allocate owned saved data");
        ship.supplies.setItem(CarrierSupplies.FLIGHT, supply("power_cell", 1));
        ship.supplies.setItem(CarrierSupplies.WEAPON, supply("laser_cell", 1));
        ship.supplies.setItem(CarrierSupplies.GUN, supply("autocannon_magazine", 1));
        ship.supplies.setItem(CarrierSupplies.MISSILES, supply("micro_missile_pack", 1));
        ship.supplies.setItem(CarrierSupplies.REPAIR, new ItemStack(Items.IRON_INGOT, 4));
        ship.supplies.setChanged();
        for (int y = 0; y < 2; y++) for (int dx = 7; dx <= 9; dx++) for (int dz = 7; dz <= 9; dz++) {
            BlockPos pos = arena.offset(dx, y, dz);
            active.place(level, pos, Blocks.STONE.defaultBlockState());
            active.mine.add(pos);
        }
        CarrierMenu.open(player, proof.ship);
        active.pass(existing == null ? "DEPLOYMENT" : "EXISTING_DEPLOYMENT", existing == null
            ? "normal owned exterior deployment; bounded fixture=18 stone"
            : "one manually deployed owned exterior reused; it will never be deleted by cleanup");
        proof.mark("RUNNING", "FUEL: waiting for ordinary supply-input charging");
        return 1;
    }

    static boolean retryableCombat(String stage, String message, int recordedDrones) {
        return "FAILED".equals(stage) && message != null && message.startsWith("COMBAT: ") && recordedDrones == 0;
    }

    static boolean baselineCargo(Container cargo) {
        int total = 0;
        ItemStack cobblestone = new ItemStack(Items.COBBLESTONE);
        for (int slot = 0; slot < cargo.getContainerSize(); slot++) {
            ItemStack stack = cargo.getItem(slot);
            if (stack.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(stack, cobblestone)) return false;
            total += stack.getCount();
            if (total > 18) return false;
        }
        return total == 18;
    }

    static boolean absentTargetRetry(UUID run, UUID confirmedRun, UUID target, CombatEvidence evidence) {
        return run.equals(confirmedRun) && !target.equals(NONE)
            && (run.equals(LEGACY_RETRY_RUN) && !evidence.legacyRetryUsed()
                || evidence.target().equals(target) && evidence.complete(false, false));
    }

    static boolean sameCombatCycle(UUID expected, UUID current, long elapsed, CarrierPolicy.Mode mode, CarrierPolicy.Stop stop) {
        if (expected.equals(NONE) || elapsed < 0) return false;
        return elapsed < CarrierPolicy.COMBAT_TICKS
            ? mode == CarrierPolicy.Mode.COMBAT && stop == CarrierPolicy.Stop.RUNNING && expected.equals(current)
            : mode == CarrierPolicy.Mode.IDLE && stop == CarrierPolicy.Stop.COMPLETE;
    }

    private static int retryCombat(ServerPlayer player, Proof proof, UUID confirmedRun) {
        require(active == null && retryableCombat(proof.stage, proof.message, proof.drones.size()),
            "retry_combat requires FAILED COMBAT and no recorded small drones");
        require(proof.run.equals(confirmedRun), "confirmed run UUID does not match the retained failed fixture");
        ServerLevel level = proof.origin.level(player.level().getServer());
        require(level != null && player.level() == level && level == level.getServer().overworld(), "retry in the recorded exterior overworld");
        require(!proof.run.equals(NONE) && !proof.target.equals(NONE), "recorded run and target required");
        CarrierEntity carrier = CarrierModule.find(level.getServer(), proof.ship);
        require(carrier != null && carrier.level() == level && carrier.entityTags().contains(proof.tag())
            && carrier.controls(player), "existing tagged carrier must be loaded and controlled by its owner");
        CarrierShip ship = carrier.ship();
        require(ship != null && ship.owner.equals(proof.owner) && !ship.destroyed, "recorded carrier owner changed");
        require(CarrierSavedData.get(level.getServer()).ships().entrySet().stream().filter(e -> !e.getValue().destroyed).count() == 1,
            "retry cannot add or replace another carrier");
        require(ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null && ship.preview == null
            && ship.pending == null && ship.bay.snapshot().isEmpty(), "active work, unknown pending cargo or bay lease; retry refused");
        require(baselineCargo(ship.cargo), "retry requires only the existing 18 ordinary cobblestone; cargo is never reset");
        require(ship.progress != null && ship.progress.operation().dimension().equals(proof.origin.dimension())
            && ship.progress.operation().chunkX() == (proof.arena.getX() >> 4)
            && ship.progress.operation().chunkZ() == (proof.arena.getZ() >> 4), "recorded mining arena changed");
        require((carrier.blockPosition().getX() >> 4) == (proof.arena.getX() >> 4)
            && (carrier.blockPosition().getZ() >> 4) == (proof.arena.getZ() >> 4)
            && carrier.getY() > proof.arena.getY() + 3, "carrier moved away from the recorded fixture");
        for (int dx = -32; dx <= 32; dx += 16) for (int dz = -32; dz <= 32; dz += 16)
            require(level.getChunkSource().getChunkNow((proof.arena.getX() + dx) >> 4, (proof.arena.getZ() + dz) >> 4) != null,
                "retry waits for the arena and its bounded surrounding chunks to load");
        require(proof.fixtures.size() == 18, "unexpected fixture manifest; no automatic restoration");
        Set<BlockPos> seen = new HashSet<>();
        for (Fixture fixture : proof.fixtures) {
            require(fixture.state().is(Blocks.STONE) && validFixture(level.getServer(), proof, fixture)
                && fixture.at().dimension().equals(proof.origin.dimension()) && seen.add(fixture.at().pos()), "fixture manifest changed");
            BlockPos pos = fixture.at().pos();
            require(level.getBlockState(pos).equals(pos.getY() == proof.arena.getY() ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState())
                && level.getBlockEntity(pos) == null && !CarrierSavedData.get(level.getServer()).playerBuilt(proof.origin.dimension(), pos.asLong()),
                "fixture changed or player-replaced: " + pos);
        }
        int inspected = 0;
        for (ServerLevel world : level.getServer().getAllLevels()) for (Entity entity : world.getAllEntities()) {
            require(++inspected <= 4096, "bounded retry entity preflight exceeded");
            require(!(entity instanceof DroneEntity), "existing small drone found; retry never resets or duplicates aircraft");
            if (entity.getUUID().equals(proof.target)) require(world == level && entity.getType() == EntityTypes.HUSK
                && entity.entityTags().contains(proof.tag()), "recorded target identity changed");
            if (entity.entityTags().contains(proof.tag()) && !entity.getUUID().equals(proof.ship))
                require(entity.getUUID().equals(proof.target) && entity.getType() == EntityTypes.HUSK, "another tagged fixture entity exists");
        }
        Entity previous = level.getEntity(proof.target);
        require(previous != null || absentTargetRetry(proof.run, confirmedRun, proof.target, proof.combat),
            "missing target without persisted death evidence; only the explicitly authorized legacy run may retry absence");
        if (previous != null) require(previous instanceof Mob mob && mob.isNoAi() && previous.isNoGravity()
            && previous.position().distanceToSqr(Vec3.atCenterOf(proof.arena.offset(8, 2, 8))) < 64, "live fixture target changed; no reposition or health reset");
        requireNoCompetingTargets(level, player, proof, carrier);
        boolean needsCell = ship.weaponEnergy < 1000;
        require(ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty(), "remove/consume the existing weapon supply normally before retry");
        require(player.containerMenu.getCarried().isEmpty(), "place the held menu stack before retry");
        CarrierMenu.open(player, proof.ship);
        require(player.containerMenu instanceof CarrierMenu menu && menu.shipId.equals(proof.ship), "normal existing carrier menu unavailable");

        Run run = new Run(level, proof);
        run.carrier = carrier;
        for (BlockPos pos : run.terrain()) run.combatTerrain.add(level.getBlockState(pos));
        UUID retired = proof.target;
        if (previous == null || previous instanceof LivingEntity target && !target.isAlive()) {
            if (previous == null && !proof.combat.complete(false, false)) {
                proof.combat = proof.combat.consumeLegacyRetry(); proof.setDirty();
            }
            if (previous != null) previous.discard(); // Exact previously checked fixture UUID and tag only.
            run.spawnCombatTarget();
            say(player, "RETRY / retired target=" + retired + "; fresh target=" + proof.target
                + "; prior absence is NOT kill evidence; all damage/energy checks run again");
        }
        proof.combat = proof.combat.begin(NONE, 0, 0);
        if (needsCell) {
            // One explicitly authorized fixture cell, in an empty typed slot, consumed by ordinary supply ticks.
            ship.supplies.setItem(CarrierSupplies.WEAPON, supply("laser_cell", 1)); ship.supplies.setChanged();
        }
        run.next(Phase.ARM_COMBAT);
        active = run;
        proof.mark("RUNNING", "ARM_COMBAT retry; same run/carrier, original cargo retained; no small drones created yet");
        say(player, "RETRY_COMBAT / run=" + proof.run + " / ship=" + proof.ship + " / cargo=18 retained");
        return 1;
    }

    /** Missing entities never create damage/death evidence; charge-only energy is not a successful hit. */
    record CombatEvidence(UUID target, float health, int energy, float damage, int spent, boolean killed, boolean legacyRetryUsed) {
        CombatEvidence(UUID target, float health, int energy, float damage, int spent, boolean killed) {
            this(target, health, energy, damage, spent, killed, false);
        }
        static final CombatEvidence EMPTY = new CombatEvidence(NONE, 0, 0, 0, 0, false);
        static final Codec<CombatEvidence> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("target").forGetter(CombatEvidence::target),
            Codec.floatRange(0, 200).fieldOf("health").forGetter(CombatEvidence::health),
            Codec.intRange(0, CarrierPolicy.MAX_ENERGY).fieldOf("energy").forGetter(CombatEvidence::energy),
            Codec.floatRange(0, 200).fieldOf("damage").forGetter(CombatEvidence::damage),
            Codec.intRange(0, CarrierPolicy.MAX_ENERGY).fieldOf("spent").forGetter(CombatEvidence::spent),
            Codec.BOOL.fieldOf("killed").forGetter(CombatEvidence::killed),
            Codec.BOOL.optionalFieldOf("legacy_retry_used", false).forGetter(CombatEvidence::legacyRetryUsed)
        ).apply(i, CombatEvidence::new));
        CombatEvidence begin(UUID id, float initialHealth, int initialEnergy) {
            return new CombatEvidence(id, initialHealth, initialEnergy, 0, 0, false, legacyRetryUsed);
        }
        CombatEvidence consumeLegacyRetry() {
            return new CombatEvidence(target, health, energy, damage, spent, killed, true);
        }
        CombatEvidence sample(UUID id, boolean tagged, boolean fire, boolean ownerDamage, float currentHealth, int currentEnergy, boolean dead) {
            require(target.equals(id) && tagged && Float.isFinite(currentHealth) && currentHealth >= 0 && currentHealth <= health,
                "combat target identity or health changed unexpectedly");
            require(currentEnergy >= 0 && currentEnergy <= CarrierPolicy.MAX_ENERGY, "invalid combat energy observation");
            float loss = health - currentHealth;
            int cost = jp.morrowgear.drone.carrier.CarrierPowerPolicy.observedWorkConsumption(energy, currentEnergy);
            boolean hit = loss > 0 && fire && ownerDamage && cost == CarrierCombatPolicy.HIT_ENERGY;
            require(loss == 0 || hit, "target damage without matching successful carrier fire energy/owner attribution");
            require(!dead || currentHealth == 0 && (hit || killed), "target death without verified lethal carrier damage");
            return new CombatEvidence(target, currentHealth, currentEnergy, damage + (hit ? loss : 0),
                spent + (hit ? cost : 0), killed || dead && hit, legacyRetryUsed);
        }
        boolean complete(boolean present, boolean alive) {
            return !target.equals(NONE) && damage > 0 && spent >= CarrierCombatPolicy.HIT_ENERGY
                && (killed ? health == 0 && !alive : present && alive && health > 0);
        }
    }

    private static void requireNoCompetingTargets(ServerLevel level, ServerPlayer player, Proof proof, CarrierEntity carrier) {
        double centerX = proof.arena.getX() + 8, centerZ = proof.arena.getZ() + 8;
        AABB area = CarrierCombatPolicy.searchBounds(centerX, centerZ, level.getMinY(), carrier.beamOrigin().y);
        List<LivingEntity> competing = new ArrayList<>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class), area,
            e -> competingCombatCandidate(e.getUUID(), proof.target,
                e.isAlive() && e instanceof Enemy
                    && !e.getType().builtInRegistryHolder().is(MorrowgearDrone.COMPAT_FRIENDLY_ENTITIES)
                    && !e.isAlliedTo(player) && !player.isAlliedTo(e),
                e.getEyePosition(), centerX, centerZ, level.getMinY(), carrier.beamOrigin().y), competing, 17);
        require(competing.isEmpty(), "unknown hostile in carrier combat selection; no deletion or damage attribution: "
            + competing.stream().limit(16).map(CarrierRuntimeVerification::describeEntity).toList()
            + (competing.size() > 16 ? " (more)" : ""));
    }

    static boolean competingCombatCandidate(UUID entity, UUID fixtureTarget, boolean hostile, Vec3 eye,
                                             double centerX, double centerZ, double minY, double originY) {
        return hostile && !entity.equals(fixtureTarget)
            && CarrierCombatPolicy.inCylinder(eye, centerX, centerZ, minY, originY);
    }

    private static String describeEntity(LivingEntity entity) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " uuid=" + entity.getUUID()
            + " pos=" + entity.blockPosition() + " eye=" + entity.getEyePosition();
    }

    private enum Phase { FUEL, POSITIONING, ARM_MINING, MINING, ARM_COMBAT, COMBAT, BAYS }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final Proof proof;
        final long started;
        final Set<BlockPos> mine = new HashSet<>();
        final Set<UUID> visited = new HashSet<>();
        final Set<UUID> resumed = new HashSet<>();
        final java.util.Map<UUID, Double> resumeDistance = new java.util.HashMap<>();
        final List<CarrierServiceBay.Identity> identities = new ArrayList<>();
        final List<BlockState> combatTerrain = new ArrayList<>();
        CarrierEntity carrier;
        BlockPos destination;
        Phase phase = Phase.FUEL;
        long phaseTick;
        boolean combatPreview;
        UUID combatGeneration = NONE;

        Run(ServerLevel level, Proof proof) {
            this.level = level; this.server = level.getServer(); this.proof = proof;
            started = level.getGameTime(); phaseTick = started;
        }

        void place(ServerLevel world, BlockPos pos, BlockState state) {
            require(world.hasChunkAt(pos) && world.getBlockState(pos).isAir(), "fixture placement would replace a block");
            Fixture fixture = new Fixture(new CarrierAnchor(world.dimension().identifier().toString(), pos.getX(), pos.getY(), pos.getZ()), state);
            if (!proof.fixtures.contains(fixture)) proof.fixtures.add(fixture);
            proof.setDirty();
            require(world.setBlock(pos, state, FIXTURE_FLAGS), "fixture placement failed");
        }

        void tick() {
            ServerPlayer player = server.getPlayerList().getPlayer(proof.owner);
            require(player != null && allowed(player) && player.level() == level, "owner left the authorized exterior test context");
            require(level.getGameTime() >= started && level.getGameTime() - started <= MAX_TICKS, "bounded runtime timeout");
            require(carrier != null && level.getEntity(proof.ship) == carrier && carrier.entityTags().contains(proof.tag()), "test carrier unavailable");
            CarrierShip ship = carrier.ship();
            require(ship != null && !ship.destroyed && ship.owner.equals(proof.owner), "test carrier identity changed");
            long elapsed = level.getGameTime() - phaseTick;
            switch (phase) {
                case FUEL -> {
                    if (!ship.supplies.getItem(CarrierSupplies.FLIGHT).isEmpty()
                        || !ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty()) {
                        require(elapsed < 100, "supply inputs never charged tanks"); return;
                    }
                    require(ship.energy > 0 && ship.weaponEnergy > 0, "cell charging did not replenish tanks");
                    pass("SUPPLY_INPUTS", "ordinary tick consumed one cell per tank");
                    require(carrier.setDestination(player, destination), "normal flight reposition refused="
                        + carrier.destinationRefusalReason(player, destination) + "; no teleport fallback");
                    next(Phase.POSITIONING);
                }
                case POSITIONING -> {
                    require(elapsed <= 400, "normal fixture positioning timed out");
                    if (ship.destination != null) return;
                    require(carrier.position().distanceToSqr(Vec3.atCenterOf(destination)) < 1, "normal carrier reposition stopped early");
                    pass("FLIGHT_POSITIONING", "normal movement reached the empty test column");
                    send(player, CarrierCommandPayload.Action.PREVIEW_MINING, NONE);
                    require(ship.preview != null && ship.previewMode == CarrierPolicy.Mode.MINING, "normal mining preview refused");
                    next(Phase.ARM_MINING);
                }
                case ARM_MINING -> {
                    if (elapsed < 6) return;
                    send(player, CarrierCommandPayload.Action.ACTIVATE, ship.preview.generation());
                    require(ship.mode == CarrierPolicy.Mode.MINING, "normal mining activation refused");
                    next(Phase.MINING);
                }
                case MINING -> {
                    require(elapsed < 400, "18-block mining timed out: " + ship.stop);
                    long removed = mine.stream().filter(p -> level.getBlockState(p).isAir()).count();
                    if (removed < 18) {
                        require(ship.mode == CarrierPolicy.Mode.MINING, "mining stopped before fixture completion: " + ship.stop);
                        return;
                    }
                    require(count(ship.cargo, Items.COBBLESTONE) == 18, "mined blocks and actual cargo do not conserve 18 cobblestone");
                    send(player, CarrierCommandPayload.Action.STOP, NONE);
                    require(ship.mode == CarrierPolicy.Mode.IDLE, "normal stop command refused");
                    pass("MINING_LOOT", "18/18 blocks removed and stored; STOP, not full-height completion");
                    for (int dx = 7; dx <= 9; dx++) for (int dz = 7; dz <= 9; dz++)
                        place(level, proof.arena.offset(dx, 0, dz), Blocks.STONE.defaultBlockState());
                    for (BlockPos pos : terrain()) combatTerrain.add(level.getBlockState(pos));
                    spawnCombatTarget();
                    next(Phase.ARM_COMBAT);
                }
                case ARM_COMBAT -> {
                    requireNoCompetingTargets(level, player, proof, carrier);
                    if (!ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty()) {
                        require(elapsed < 100, "ordinary retry weapon-cell charging timed out"); return;
                    }
                    if (elapsed >= 6 && !combatPreview) {
                        send(player, CarrierCommandPayload.Action.PREVIEW_COMBAT, NONE);
                        require(ship.preview != null && ship.previewMode == CarrierPolicy.Mode.COMBAT, "normal combat preview refused");
                        combatPreview = true;
                        phaseTick = level.getGameTime();
                        return;
                    }
                    if (!combatPreview || elapsed < 6) return;
                    require(ship.preview != null, "combat preview lost");
                    LivingEntity target = checkedCombatTarget();
                    require(target != null && target.isAlive() && target.getHealth() > 0 && target.getHealth() <= 200,
                        "live tagged target required before combat activation");
                    require(ship.weaponEnergy >= 640, "insufficient normal weapon supply for full combat and bay checks");
                    proof.combat = proof.combat.begin(proof.target, target.getHealth(), ship.weaponEnergy);
                    proof.setDirty();
                    combatGeneration = ship.preview.generation();
                    send(player, CarrierCommandPayload.Action.ACTIVATE, ship.preview.generation());
                    require(ship.mode == CarrierPolicy.Mode.COMBAT, "normal combat activation refused");
                    next(Phase.COMBAT);
                }
                case COMBAT -> {
                    require(sameCombatCycle(combatGeneration, ship.activeCombat == null ? NONE : ship.activeCombat.generation(), elapsed,
                        ship.mode, ship.stop), "combat cycle interrupted, replaced or ended without normal completion");
                    requireNoCompetingTargets(level, player, proof, carrier);
                    LivingEntity target = checkedCombatTarget();
                    if (target == null) require(proof.combat.killed(), "target disappeared without an observed verified death");
                    else {
                        CombatEvidence before = proof.combat;
                        proof.combat = before.sample(target.getUUID(), target.entityTags().contains(proof.tag()),
                            carrier.workPhase() == CarrierPolicy.WorkPhase.FIRE, target.getLastHurtByMob() == player,
                            target.getHealth(), ship.weaponEnergy, !target.isAlive());
                        proof.setDirty();
                        if (proof.combat.damage() > before.damage()) say(player, "OBSERVED COMBAT_HIT / target=" + proof.target
                            + " / hpLoss=" + (proof.combat.damage() - before.damage()) + " / fireEnergy=" + (proof.combat.spent() - before.spent())
                            + " / killed=" + proof.combat.killed() + " / run=" + proof.run);
                    }
                    int index = 0;
                    for (BlockPos pos : terrain()) require(combatTerrain.get(index++).equals(level.getBlockState(pos)), "combat changed terrain: " + pos);
                    if (elapsed < CarrierPolicy.COMBAT_TICKS + 5) return;
                    require(proof.combat.target().equals(proof.target) && proof.combat.complete(target != null, target != null && target.isAlive()),
                        "combat lacks verified target damage and matching successful fire energy");
                    require(ship.mode == CarrierPolicy.Mode.IDLE && ship.stop == CarrierPolicy.Stop.COMPLETE, "combat did not end normally");
                    if (target != null) target.discard();
                    proof.target = NONE; proof.setDirty();
                    pass("COMBAT_NON_TERRAIN", "observed damage=" + proof.combat.damage() + "; successful fire energy=" + proof.combat.spent()
                        + "; observed killed=" + proof.combat.killed() + "; normal full cycle and 768 block states unchanged");
                    spawnAndReserve(player);
                    next(Phase.BAYS);
                }
                case BAYS -> {
                    require(elapsed <= 1400, "four-bay service or physical exit timed out: " + droneStatus());
                    for (int i = 0; i < proof.drones.size(); i++) {
                        DroneEntity drone = drone(i);
                        if (resumed.contains(drone.getUUID())) continue;
                        require(CarrierDroneServiceAdapter.identity(drone).equals(identities.get(i)), "bay changed home, Wing, owner or mission");
                        if (CarrierServiceBay.stable(drone.position(), drone.getDeltaMovement(), carrier.bayPosition(i), carrier.getDeltaMovement()))
                            visited.add(drone.getUUID());
                        if (!visited.contains(drone.getUUID()) || !drone.carrierServiceAvailable(proof.ship)
                            || ship.bay.snapshot().stream().anyMatch(l -> l.identity().drone().equals(drone.getUUID()))) continue;
                        require(!carrier.getBoundingBox().inflate(3).contains(drone.position()) && drone.mode() == DroneMode.WAYPOINT,
                            "released drone did not physically exit into its original mission");
                        double distance = drone.position().distanceToSqr(Vec3.atCenterOf(drone.waypointPos().above(8)));
                        Double before = resumeDistance.putIfAbsent(drone.getUUID(), distance);
                        if (before == null || distance >= before - .05) continue;
                        require(drone.batteryPercent() >= 95, "bay did not replenish flight energy");
                        if (drone.role() == DroneRole.SECURITY) {
                            require(drone.weaponPowerPercent() >= 99, "weapon-energy service incomplete");
                            if (drone.securityLoadout() == SecurityLoadout.AUTOCANNON) require(drone.gunAmmo() == drone.gunCapacity(), "gun service incomplete");
                            if (drone.securityLoadout() == SecurityLoadout.MISSILE) require(drone.missiles() == drone.missileCapacity(), "missile service incomplete");
                        }
                        require(drone.getHealth() == drone.getMaxHealth(), "repair service incomplete");
                        resumed.add(drone.getUUID());
                        // Freeze only after observing real resumed navigation, so early finishers cannot complete their task first.
                        drone.setMode(DroneMode.STANDBY);
                    }
                    if (resumed.size() < 4 || !ship.bay.snapshot().isEmpty()) return;
                    require(count(ship.cargo, Items.COBBLESTONE) == 18, "bay service consumed unrelated mining cargo");
                    pass("FOUR_BAYS", "4 physical arrivals, resource service, unchanged identities and physical task resume; loot retained");
                    proof.mark("READY", "runtime checks passed; fixture retained for UI and persistence_prepare at the boarding pad");
                    say(player, "READY / runtime checks complete / ship=" + proof.ship + " / boarding=" + carrier.boardingProjection());
                    active = null;
                }
            }
            if (active != null && elapsed % 100 == 0) proof.mark("RUNNING", phase + " elapsed=" + elapsed);
        }

        Iterable<BlockPos> terrain() { return BlockPos.betweenClosed(proof.arena, proof.arena.offset(15, 2, 15)); }

        void spawnCombatTarget() {
            Mob target = EntityTypes.HUSK.create(level, EntitySpawnReason.TRIGGERED);
            require(target != null && target.getAttribute(Attributes.MAX_HEALTH) != null, "target creation failed");
            target.addTag(proof.tag()); target.setPersistenceRequired(); target.setNoAi(true);
            target.setNoGravity(true); target.setSilent(true);
            target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            target.setHealth(200); target.setPos(Vec3.atCenterOf(proof.arena.offset(8, 2, 8)));
            require(level.addFreshEntity(target), "target spawn refused");
            proof.target = target.getUUID(); proof.combat = proof.combat.begin(NONE, 0, 0); proof.setDirty();
        }

        LivingEntity checkedCombatTarget() {
            Entity entity = level.getEntity(proof.target);
            if (entity == null) return null;
            require(entity instanceof LivingEntity && entity.getType() == EntityTypes.HUSK
                && entity.entityTags().contains(proof.tag()), "recorded combat target identity/tag changed");
            return (LivingEntity)entity;
        }

        void spawnAndReserve(ServerPlayer player) {
            require(proof.drones.isEmpty() && carrier.ship().bay.snapshot().isEmpty(), "recorded aircraft or leases already exist; no duplicate bay fixture");
            int inspected = 0;
            for (ServerLevel world : server.getAllLevels()) for (Entity entity : world.getAllEntities()) {
                require(++inspected <= 4096 && !(entity instanceof DroneEntity), "existing aircraft or entity preflight limit; no duplicate fixture");
            }
            SecurityLoadout[] loadouts = {SecurityLoadout.AUTO, SecurityLoadout.AUTOCANNON, SecurityLoadout.LASER, SecurityLoadout.MISSILE};
            for (int i = 0; i < 4; i++) {
                DroneEntity drone = MorrowgearDrone.DRONE.create(level, EntitySpawnReason.TRIGGERED);
                require(drone != null, "small drone creation failed");
                proof.drones.add(drone.getUUID()); proof.setDirty();
                drone.addTag(proof.tag()); drone.initializeOwner(player);
                drone.assignRole(i == 0 ? DroneRole.SCOUT : DroneRole.SECURITY);
                drone.assignSecurityLoadout(loadouts[i]);
                drone.assignGroup("CV27-" + proof.run.toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT) + "-" + i);
                Vec3 spawn = carrier.bayApproachPosition(i).add(CarrierPolicy.bayX(i) < 0 ? -12 : 12, -2, 0);
                drone.setPos(spawn); drone.setPowerForVerification(900, drone.weaponCapacity() - 20);
                drone.setCombatResourcesForVerification(drone.gunCapacity() - 10, drone.missileCapacity() - 1, 0);
                drone.setHealth(drone.getMaxHealth() - 1);
                require(level.addFreshEntity(drone), "small drone spawn refused");
                drone.assignWaypoint(BlockPos.containing(spawn.add(0, -8, 12)), "CV27-" + proof.run + "-" + i,
                    1, 0, BlockPos.containing(spawn), level.getGameTime());
                identities.add(CarrierDroneServiceAdapter.identity(drone));
                require(CarrierModule.reserveBay(player, proof.ship, drone.getUUID()), "explicit normal bay reservation refused: " + i);
            }
            require(carrier.ship().bay.snapshot().size() == 4, "four distinct bay slots were not reserved");
            require(CarrierModule.reserveBay(player, proof.ship, proof.drones.getFirst())
                && carrier.ship().bay.snapshot().size() == 4, "duplicate request created another reservation");
        }

        DroneEntity drone(int index) {
            Entity entity = level.getEntity(proof.drones.get(index));
            require(entity instanceof DroneEntity && entity.entityTags().contains(proof.tag()), "owned test drone unavailable");
            return (DroneEntity)entity;
        }

        String droneStatus() {
            return proof.drones.stream().map(id -> level.getEntity(id) instanceof DroneEntity drone
                ? drone.unitId() + ":" + drone.dataLinkStatus() : id + ":unloaded").toList().toString();
        }

        void send(ServerPlayer player, CarrierCommandPayload.Action action, UUID generation) {
            require(player.containerMenu instanceof CarrierMenu menu && menu.shipId.equals(proof.ship), "normal carrier menu context was closed or replaced");
            CarrierCommands.handle(new CarrierCommandPayload(player.containerMenu.containerId, proof.ship, action,
                generation, proof.arena.getX(), proof.arena.getY(), proof.arena.getZ(), NONE), player);
        }

        void next(Phase next) { phase = next; phaseTick = level.getGameTime(); }
        void pass(String test, String detail) { say(server.getPlayerList().getPlayer(proof.owner), "PASS " + test + " / " + detail + " / run=" + proof.run); }
        void fail(String detail) {
            try { stopOwned(server, proof); }
            catch (RuntimeException failure) { detail += " / stop failed: " + failure; }
            active = null;
            proof.mark("FAILED", phase + ": " + detail);
            say(server.getPlayerList().getPlayer(proof.owner), "FAIL " + proof.message + " / fixture retained / use stop for cleanup");
        }
    }

    private static int prepare(ServerPlayer player, Proof proof) {
        require(active == null && proof.stage.equals("READY"), "runtime checks must finish before persistence preparation");
        CarrierEntity carrier = CarrierModule.find(player.level().getServer(), proof.ship);
        require(carrier != null && carrier.entityTags().contains(proof.tag()), "owned test carrier unavailable");
        if (!proof.ship.equals(CarrierInterior.currentShip(player))) {
            if (!CarrierInterior.board(player, proof.ship, false)) {
                say(player, "PREPARE WAIT / stand on the normal boarding pad at " + carrier.boardingProjection());
                return 0;
            }
        }
        require(proof.ship.equals(CarrierInterior.currentShip(player)), "normal boarding did not establish this cabin visit");
        CarrierShip ship = carrier.ship();
        BlockPos pos = new BlockPos(CarrierPolicy.cabinX(ship.cabin) + 6, 65, CarrierPolicy.cabinZ(ship.cabin) + 6);
        require(CarrierInterior.allowed(player, pos, true) && player.level().hasChunkAt(pos), "normal cabin placement boundary refused");
        require(player.level().getBlockState(pos).isAir(), "cabin test chest position already occupied");
        proof.fixtures.add(new Fixture(new CarrierAnchor(CarrierInterior.DIMENSION.identifier().toString(), pos.getX(), pos.getY(), pos.getZ()),
            Blocks.CHEST.defaultBlockState()));
        proof.setDirty();
        require(player.level().setBlock(pos, Blocks.CHEST.defaultBlockState(), FIXTURE_FLAGS), "cabin chest placement failed");
        require(player.level().getBlockEntity(pos) instanceof Container, "real cabin chest block entity missing");
        Container chest = (Container)player.level().getBlockEntity(pos);
        chest.setItem(0, sentinel(proof)); chest.setChanged();
        CarrierProtection.recordPlayerBuild(player.level(), pos);
        proof.expectedCargo = ship.cargo.snapshot(); proof.expectedSupplies = ship.supplies.snapshot();
        proof.expectedStock = ship.bayStock; proof.savedBoot = boot;
        proof.mark("PREPARED", "save and quit normally, restart this world while still in the cabin, then persistence_check");
        say(player, "PASS CABIN_BOARD_AND_STORAGE / real chest=7 marked paper / " + proof.message);
        return 1;
    }

    private static int check(ServerPlayer player, Proof proof) {
        require(active == null && proof.stage.equals("PREPARED"), "persistence_prepare required");
        require(!boot.equals(proof.savedBoot), "same server boot: in-memory round trips are not restart evidence");
        try { return checkRestored(player, proof); }
        catch (RuntimeException failure) {
            proof.mark("FAILED", "RESTART_PERSISTENCE: " + failure.getMessage());
            say(player, "FAIL " + proof.message + " / fixture retained");
            return 0;
        }
    }

    private static int checkRestored(ServerPlayer player, Proof proof) {
        MinecraftServer server = player.level().getServer();
        require(proof.ship.equals(CarrierInterior.currentShip(player)), "saved cabin owner visit was not restored");
        CarrierEntity carrier = CarrierModule.find(server, proof.ship);
        if (carrier == null) { say(player, "CHECK WAIT / exterior chunk is still loading; retry after its bounded cabin lease"); return 0; }
        require(carrier.entityTags().contains(proof.tag()), "saved test exterior identity changed");
        CarrierShip ship = carrier.ship();
        require(ship != null && ship.owner.equals(proof.owner) && ship.cabinReady && !ship.destroyed, "saved cabin identity changed");
        require(sameStacks(proof.expectedCargo, ship.cargo.snapshot()) && sameStacks(proof.expectedSupplies, ship.supplies.snapshot())
            && proof.expectedStock.equals(ship.bayStock), "saved cargo, supply inputs or ammunition credits changed");
        Fixture chestFixture = proof.fixtures.stream().filter(f -> f.state().is(Blocks.CHEST)).findFirst().orElseThrow();
        ServerLevel interior = chestFixture.at().level(server);
        require(interior != null && interior.hasChunkAt(chestFixture.at().pos())
            && interior.getBlockEntity(chestFixture.at().pos()) instanceof Container, "cabin chest was not restored as a block entity");
        Container chest = (Container)interior.getBlockEntity(chestFixture.at().pos());
        require(chest.getItem(0).getCount() == 7 && ItemStack.isSameItemSameComponents(chest.getItem(0), sentinel(proof)), "cabin chest contents changed");
        ServerLevel exterior = proof.origin.level(server);
        require(exterior != null && proof.drones.size() == 4, "saved exterior or fixture roster missing");
        for (UUID id : proof.drones) {
            Entity entity = exterior.getEntity(id);
            require(entity instanceof DroneEntity drone && drone.isOwnedBy(proof.owner) && entity.entityTags().contains(proof.tag()), "saved small drone unavailable: " + id);
        }
        require(ship.bay.snapshot().isEmpty(), "stale bay reservations resumed after reload");
        require(CarrierInterior.exit(player), "normal safe cabin exit refused; fixture retained");
        require(player.level() == exterior, "cabin exit selected the wrong dimension");
        proof.mark("PASS", "runtime checks and actual server-restart cabin/cargo persistence passed; fixture retained for UI");
        say(player, "PASS RESTART_PERSISTENCE_AND_EXIT / " + proof.message + " / run=" + proof.run);
        return 1;
    }

    private static int cleanup(ServerPlayer player, Proof proof) {
        if (proof.stage.equals("EMPTY") || proof.stage.equals("CLEANED")) { say(player, "CLEAN / no live test fixture"); return 1; }
        MinecraftServer server = player.level().getServer();
        if (proof.ship.equals(CarrierInterior.currentShip(player)) && !CarrierInterior.exit(player)) {
            say(player, "CLEANUP WAIT / normal cabin exit unavailable; nothing removed"); return 0;
        }
        stopOwned(server, proof);
        active = null;
        ServerLevel exterior = proof.origin.level(server);
        require(exterior != null, "cleanup exterior unavailable");
        int retained = 0, pending = 0;
        for (Fixture fixture : List.copyOf(proof.fixtures)) {
            ServerLevel world = fixture.at().level(server);
            BlockPos pos = fixture.at().pos();
            require(validFixture(server, proof, fixture), "fixture manifest escaped its bounded arena/cabin");
            if (world == null || !world.hasChunkAt(pos)) { pending++; continue; }
            if (world.getBlockState(pos).isAir()) { proof.fixtures.remove(fixture); continue; }
            if (!world.getBlockState(pos).equals(fixture.state())) { retained++; proof.fixtures.remove(fixture); continue; }
            if (world.getBlockEntity(pos) instanceof Container container) {
                boolean ours = container.getItem(0).getCount() == 7 && ItemStack.isSameItemSameComponents(container.getItem(0), sentinel(proof));
                for (int slot = 1; slot < container.getContainerSize(); slot++) ours &= container.getItem(slot).isEmpty();
                if (!ours) { retained++; proof.fixtures.remove(fixture); continue; }
                container.clearContent();
            }
            if (world.setBlock(pos, Blocks.AIR.defaultBlockState(), FIXTURE_FLAGS)) proof.fixtures.remove(fixture);
            else pending++;
        }
        for (UUID id : List.copyOf(proof.drones)) {
            Entity entity = exterior.getEntity(id);
            if (entity == null) { pending++; continue; }
            require(entity instanceof DroneEntity drone && drone.isOwnedBy(proof.owner)
                && entity.entityTags().contains(proof.tag()), "cleanup drone identity changed; retained");
            entity.discard(); proof.drones.remove(id);
        }
        if (!proof.target.equals(NONE)) {
            Entity target = exterior.getEntity(proof.target);
            boolean targetAreaLoaded = exterior.hasChunkAt(proof.arena.offset(8, 2, 8));
            if (target == null && absentFixtureEntityMayBeCleared(targetAreaLoaded, false)) proof.target = NONE;
            else if (target == null) pending++;
            else {
                require(target.getType() == EntityTypes.HUSK && target.entityTags().contains(proof.tag()), "cleanup target identity changed; retained");
                target.discard(); proof.target = NONE;
            }
        }
        if (pending == 0 && !proof.ship.equals(NONE)) {
            Entity carrier = exterior.getEntity(proof.ship);
            if (carrier == null) pending++;
            else {
                require(carrier instanceof CarrierEntity found && found.ship() != null && found.ship().owner.equals(proof.owner)
                    && carrier.entityTags().contains(proof.tag()), "cleanup carrier identity changed; retained");
                if (proof.createdCarrier) { carrier.discard(); proof.ship = NONE; }
                else carrier.removeTag(proof.tag());
            }
        }
        if (pending > 0) {
            proof.mark("CLEANUP_WAIT", "unloaded/failed fixture cleanup=" + pending + "; retry stop nearby; new start refused");
            say(player, proof.message); return 0;
        }
        proof.mark("CLEANED", "removed only tagged test entities and unchanged fixture blocks; retained changed blocks=" + retained
            + "; borrowed carrier, its cargo/supplies and cabin allocation preserved");
        say(player, proof.message);
        return 1;
    }

    private static void stopOwned(MinecraftServer server, Proof proof) {
        CarrierShip ship = CarrierSavedData.get(server).ship(proof.ship);
        CarrierEntity carrier = CarrierModule.find(server, proof.ship);
        if (stopRecordedShip(ship, proof.owner)) {
            if (carrier != null) ship.bay.cancelAll(carrier, CarrierServiceBay.Release.CANCELLED);
        }
        ServerLevel level = proof.origin.level(server);
        if (level != null) for (UUID id : proof.drones) {
            if (level.getEntity(id) instanceof DroneEntity drone && drone.entityTags().contains(proof.tag()) && drone.isOwnedBy(proof.owner))
                drone.setMode(DroneMode.STANDBY);
        }
    }

    /** Stop saved work before releasing the fixture guard, even when its entity/tag is unavailable. */
    static boolean stopRecordedShip(CarrierShip ship, UUID owner) {
        if (ship == null || !ship.owner.equals(owner)) return false;
        ship.stop(CarrierPolicy.Stop.EMERGENCY);
        return true;
    }

    static boolean absentFixtureEntityMayBeCleared(boolean expectedChunkLoaded, boolean entityPresent) {
        return expectedChunkLoaded && !entityPresent;
    }

    static boolean droneConflictsWithFixture(BlockPos position, int centerX, int centerZ) {
        return Math.abs(position.getX() - centerX) <= 64 && Math.abs(position.getZ() - centerZ) <= 64;
    }

    /** Read-only identity handoff; follow-up evidence must never replace the original READY/PASS. */
    static Baseline baseline(ServerPlayer player) {
        require(allowed(player) && active == null, "dedicated world and inactive baseline required");
        Proof proof = Proof.get(player.level().getServer());
        require(proof.owner.equals(player.getUUID()) && (proof.stage.equals("READY") || proof.stage.equals("PASS")),
            "existing owned READY/PASS fixture required");
        require(proof.drones.size() == 4, "exactly four recorded small drones required");
        return new Baseline(proof.run, proof.owner, proof.ship, List.copyOf(proof.drones), proof.origin,
            proof.arena, proof.tag());
    }

    record Baseline(UUID run, UUID owner, UUID ship, List<UUID> drones, CarrierAnchor origin, BlockPos arena, String tag) {}

    private static boolean validFixture(MinecraftServer server, Proof proof, Fixture fixture) {
        BlockPos pos = fixture.at().pos();
        if (fixture.state().is(Blocks.STONE)) return fixture.at().dimension().equals(proof.origin.dimension())
            && pos.getX() >= proof.arena.getX() + 7 && pos.getX() <= proof.arena.getX() + 9
            && pos.getZ() >= proof.arena.getZ() + 7 && pos.getZ() <= proof.arena.getZ() + 9
            && pos.getY() >= proof.arena.getY() && pos.getY() <= proof.arena.getY() + 1;
        CarrierShip ship = CarrierSavedData.get(server).ship(proof.ship);
        return fixture.state().is(Blocks.CHEST) && ship != null && ship.owner.equals(proof.owner)
            && fixture.at().dimension().equals(CarrierInterior.DIMENSION.identifier().toString())
            && pos.equals(new BlockPos(CarrierPolicy.cabinX(ship.cabin) + 6, 65, CarrierPolicy.cabinZ(ship.cabin) + 6));
    }

    private static ItemStack supply(String id, int count) {
        var item = BuiltInRegistries.ITEM.getValue(CarrierModule.id(id));
        require(item != Items.AIR, "missing registered supply item: " + id);
        return new ItemStack(item, count);
    }
    private static ItemStack sentinel(Proof proof) {
        ItemStack stack = new ItemStack(Items.PAPER, 7);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(proof.tag()));
        return stack;
    }
    private static int count(Container container, net.minecraft.world.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) if (container.getItem(slot).is(item)) count += container.getItem(slot).getCount();
        return count;
    }
    private static boolean sameStacks(List<ItemStack> expected, List<ItemStack> actual) {
        for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
            ItemStack a = i < expected.size() ? expected.get(i) : ItemStack.EMPTY;
            ItemStack b = i < actual.size() ? actual.get(i) : ItemStack.EMPTY;
            if (!ItemStack.matches(a, b)) return false;
        }
        return true;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void say(ServerPlayer player, String message) {
        MorrowgearDrone.LOGGER.info("{}{}", PREFIX, message);
        if (player != null) player.sendSystemMessage(Component.literal(PREFIX + message));
    }

    private record Fixture(CarrierAnchor at, BlockState state) {
        private static final Codec<Fixture> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierAnchor.CODEC.fieldOf("at").forGetter(Fixture::at), BlockState.CODEC.fieldOf("state").forGetter(Fixture::state)
        ).apply(i, Fixture::new));
    }

    private static final class Proof extends SavedData {
        private static final Codec<Proof> CODEC = RecordCodecBuilder.create(i -> i.group(
            CarrierPolicy.UUID_CODEC.fieldOf("run").forGetter(p -> p.run),
            CarrierPolicy.UUID_CODEC.fieldOf("owner").forGetter(p -> p.owner),
            CarrierPolicy.UUID_CODEC.fieldOf("ship").forGetter(p -> p.ship),
            CarrierPolicy.UUID_CODEC.fieldOf("target").forGetter(p -> p.target),
            CarrierPolicy.UUID_CODEC.listOf(0, 4).fieldOf("drones").forGetter(p -> p.drones),
            CarrierAnchor.CODEC.fieldOf("origin").forGetter(p -> p.origin),
            BlockPos.CODEC.fieldOf("arena").forGetter(p -> p.arena),
            Fixture.CODEC.listOf(0, 32).fieldOf("fixtures").forGetter(p -> p.fixtures),
            Codec.STRING.fieldOf("stage").forGetter(p -> p.stage),
            Codec.STRING.fieldOf("message").forGetter(p -> p.message),
            CarrierPolicy.UUID_CODEC.fieldOf("saved_boot").forGetter(p -> p.savedBoot),
            ItemStack.OPTIONAL_CODEC.listOf(0, 54 * 64).fieldOf("expected_cargo").forGetter(p -> p.expectedCargo),
            ItemStack.OPTIONAL_CODEC.listOf(0, 5).fieldOf("expected_supplies").forGetter(p -> p.expectedSupplies),
            CarrierServiceBay.Stock.CODEC.fieldOf("expected_stock").forGetter(p -> p.expectedStock),
            Codec.BOOL.optionalFieldOf("created_carrier", false).forGetter(p -> p.createdCarrier),
            CombatEvidence.CODEC.optionalFieldOf("combat_evidence", CombatEvidence.EMPTY).forGetter(p -> p.combat)
        ).apply(i, Proof::new));
        private static final SavedDataType<Proof> TYPE = new SavedDataType<>(CarrierModule.id("carrier_verification"), Proof::new, CODEC, null);
        UUID run, owner, ship, target, savedBoot;
        List<UUID> drones;
        CarrierAnchor origin;
        BlockPos arena;
        List<Fixture> fixtures;
        String stage, message;
        List<ItemStack> expectedCargo, expectedSupplies;
        CarrierServiceBay.Stock expectedStock;
        boolean createdCarrier;
        CombatEvidence combat;

        Proof() { this(NONE, NONE, NONE, NONE, List.of(), new CarrierAnchor("minecraft:overworld", 0, 0, 0),
            BlockPos.ZERO, List.of(), "EMPTY", "not run", NONE, List.of(), List.of(), new CarrierServiceBay.Stock(0, 0), false, CombatEvidence.EMPTY); }
        Proof(UUID run, UUID owner, UUID ship, UUID target, List<UUID> drones, CarrierAnchor origin, BlockPos arena,
              List<Fixture> fixtures, String stage, String message, UUID savedBoot, List<ItemStack> cargo,
              List<ItemStack> supplies, CarrierServiceBay.Stock stock, boolean createdCarrier, CombatEvidence combat) {
            this.run = run; this.owner = owner; this.ship = ship; this.target = target; this.drones = new ArrayList<>(drones);
            this.origin = origin; this.arena = arena; this.fixtures = new ArrayList<>(fixtures);
            this.stage = stage; this.message = message; this.savedBoot = savedBoot;
            this.expectedCargo = cargo; this.expectedSupplies = supplies; this.expectedStock = stock;
            this.createdCarrier = createdCarrier;
            this.combat = combat;
        }
        static Proof get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
        String tag() { return "MG-CARRIER-VERIFY-" + run; }
        void mark(String stage, String message) { this.stage = stage; this.message = message; setDirty(); }
        void reset(ServerPlayer player, BlockPos arena) {
            run = UUID.randomUUID(); owner = player.getUUID(); ship = NONE; target = NONE; savedBoot = NONE;
            createdCarrier = false;
            combat = CombatEvidence.EMPTY;
            drones.clear(); fixtures.clear(); expectedCargo = List.of(); expectedSupplies = List.of();
            expectedStock = new CarrierServiceBay.Stock(0, 0); origin = CarrierAnchor.at(player); this.arena = arena;
            mark("RUNNING", "setup");
        }
    }
}
