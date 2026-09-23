package jp.morrowgear.drone;

import static jp.morrowgear.drone.CarrierAcceptancePolicy.*;
import static jp.morrowgear.drone.CarrierAcceptanceData.NONE;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jp.morrowgear.drone.carrier.*;
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
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Opt-in real ServerLevel acceptance checks, never a startup task or an inventory replacement. */
public final class CarrierAcceptanceVerification {
    private static final String PREFIX = "[MORROWGEAR CARRIER ACCEPT] ";
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static UUID boot = UUID.randomUUID();
    private static Run active;
    private static Run pausedTravel;
    private static boolean registered;
    private CarrierAcceptanceVerification() {}

    static void register() {
        if (registered) return;
        registered = true;
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var root = Commands.literal("morrowgear_carrier_accept");
            for (String action : List.of("status", "deep_start", "deep_resume", "extract", "combat_start", "combat_return", "travel_start", "travel_resume",
                    "manual_begin", "manual_audit", "pause", "restart_prepare", "restart_check", "cleanup"))
                root.then(Commands.literal(action).executes(c -> command(c.getSource(), action, NONE)));
            root.then(Commands.literal("deep_start_legacy").then(Commands.argument("confirmed_generation", UuidArgument.uuid())
                .executes(c -> command(c.getSource(), "deep_start", UuidArgument.getUuid(c, "confirmed_generation")))));
            dispatcher.register(root);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { boot = UUID.randomUUID(); active = null; pausedTravel = null; });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (active != null && active.server == server) active.pause("server stopping; explicit restart_check/deep_resume required");
            active = null;
            pausedTravel = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (pausedTravel != null && pausedTravel.server == server
                && (!pausedTravel.ship.navigationPaused
                    || !CarrierAcceptanceDeparture.unchanged(pausedTravel.previousPosition, pausedTravel.carrier.position())))
                pausedTravel = null;
            Run run = active;
            if (run == null || run.server != server) return;
            try { run.tick(); }
            catch (RuntimeException failure) { run.fail(failure.getMessage()); }
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            Run run = active;
            if (run == null || world != run.level || !player.getUUID().equals(run.data.plan().owner())) return true;
            return run.miningPhase() && run.minePosition(pos) && state.equals(run.expected(pos));
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            Run run = active;
            if (run == null || world != run.level || !player.getUUID().equals(run.data.plan().owner()) || !run.minePosition(pos)) return;
            // The production event follows cargo.commit/recordCapture. Do not roll Block.getDrops a second time.
            try {
                require(run.miningPhase() && pos.equals(run.carrier.lastCapturePosition()), "capture event/position mismatch");
                require(run.carrier.captureSequence() > run.lastSequence, "duplicate capture event");
                run.lastSequence = run.carrier.captureSequence();
                int mineIndex = run.mineIndex(pos);
                require(run.data.recordBreak(mineIndex), "duplicate fixture capture event at " + pos + " index=" + mineIndex);
                List<ItemStack> drops = run.carrier.lastCaptureDrops();
                for (ItemStack stack : drops) {
                    require(stack.getComponents().equals(new ItemStack(stack.getItem()).getComponents()), "unexpected component-bearing fixture drop");
                    run.data.add("loot:" + itemId(stack), stack.getCount());
                    run.data.add("captured", stack.getCount());
                }
                run.data.add("broken", 1);
            } catch (RuntimeException failure) { run.fail(failure.getMessage()); }
        });
    }

    static boolean retained(MinecraftServer server) {
        CarrierAcceptanceData d = CarrierAcceptanceData.get(server);
        return d.manifest.isPresent() && !d.stage.equals("CLEANED");
    }
    private static boolean allowed(ServerPlayer p) {
        MinecraftServer s = p.level().getServer();
        return CarrierRuntimeVerification.allowedWorld(s.getWorldData().getLevelName(), s.isSingleplayer(), p.isCreative(),
            s.getPlayerList().getPlayers().size()) && p.isAlive() && !p.isSpectator();
    }
    private static int command(CommandSourceStack source, String action, UUID confirmation) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !allowed(player)) {
            source.sendFailure(Component.literal(PREFIX + "REFUSED: exact dedicated world, creative, singleplayer, one live player required"));
            return 0;
        }
        CarrierAcceptanceData data = CarrierAcceptanceData.get(player.level().getServer());
        try {
            require(data.manifest.isEmpty() || data.plan().owner().equals(player.getUUID()), "different evidence owner");
            if (action.equals("status")) {
                var baseline = CarrierRuntimeVerification.baseline(player);
                var ship = CarrierSavedData.get(player.level().getServer()).ship(baseline.ship());
                say(player, data.stage + " / " + data.message + " / baseline=" + baseline.run() + " / run="
                    + data.manifest.map(m -> m.run().toString()).orElse("none") + " / counters=" + data.counts
                    + " / passed=" + data.passed + " / currentMiningGeneration="
                    + (ship == null || ship.progress == null ? "none" : ship.progress.operation().generation()));
                CarrierEntity entity = CarrierModule.find(player.level().getServer(), baseline.ship());
                if (entity != null) say(player, "TELEMETRY / phase=" + entity.workPhase() + " " + entity.phaseTick() + "/" + entity.phaseDuration()
                    + " / aims=" + entity.beamAims().size() + " / weapon=" + entity.ship().weaponEnergy + " / position=" + entity.position());
                return 1;
            }
            if (action.equals("deep_start")) return start(player, data, confirmation);
            require(data.manifest.isPresent(), "deep_start required");
            if (action.equals("pause")) {
                if (active != null) active.pause("explicit pause; owned fixtures retained");
                return 1;
            }
            require(active == null, "a bounded check is running; use status/pause");
            if (action.equals("travel_resume")) {
                require(pausedTravel != null && pausedTravel.server == player.level().getServer()
                    && pausedTravel.data == data && data.stage.equals("TRAVEL_PAUSED"),
                    "no same-boot paused journey; normal MOVE does not restore acceptance tracking");
                pausedTravel.resumeTravel();
                return 1;
            }
            Run run = new Run(player, data);
            switch (action) {
                case "deep_resume" -> run.resumeMining();
                case "extract" -> { require(data.passed.contains("M01-M03"), "complete deep mining first"); run.begin("EXTRACT"); }
                case "manual_begin" -> run.beginManual();
                case "manual_audit" -> run.auditManual();
                case "combat_start" -> run.startCombat();
                case "combat_return" -> run.returnCombat();
                case "travel_start" -> run.startTravel();
                case "restart_prepare" -> run.prepareRestart();
                case "restart_check" -> run.checkRestart();
                case "cleanup" -> { run.stopOwnOperation(); run.begin("CLEANUP_FILLER"); }
                default -> throw new IllegalArgumentException("unknown action");
            }
            return 1;
        } catch (RuntimeException failure) {
            say(player, "REFUSED / " + failure.getMessage() + " / existing state retained");
            return 0;
        }
    }

    private static int start(ServerPlayer player, CarrierAcceptanceData data, UUID confirmation) {
        require(active == null && (data.stage.equals("EMPTY") || data.stage.equals("CLEANED")), "retained follow-up fixture; no duplicate run");
        var baseline = CarrierRuntimeVerification.baseline(player);
        MinecraftServer server = player.level().getServer();
        CarrierEntity carrier = CarrierModule.find(server, baseline.ship());
        require(carrier != null && carrier.entityTags().contains(baseline.tag()) && carrier.ship() != null, "recorded carrier not loaded");
        CarrierShip ship = carrier.ship();
        require(ship.owner.equals(player.getUUID()) && !ship.destroyed && carrier.controls(player), "carrier ownership/access changed");
        require(baseline.ship().equals(CarrierInterior.currentShip(player)), "board the existing carrier normally before deep_start");
        require(ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null && ship.preview == null
            && ship.pending == null && ship.bay.snapshot().isEmpty(), "active/unknown work or bay reservation; no automatic reset");
        require(ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty(), "remove existing laser supply cells normally; sustained-reactor mining must be verified without item assistance");
        if (ship.progress != null && !ship.progress.complete()) {
            CarrierPolicy.Operation old = ship.progress.operation();
            require(old.generation().equals(confirmation) && old.dimension().equals(baseline.origin().dimension())
                && old.chunkX() == (baseline.arena().getX() >> 4) && old.chunkZ() == (baseline.arena().getZ() >> 4),
                "legacy cursor is not proven: inspect status, confirm only the recorded READY arena generation via deep_start_legacy <UUID>");
        }
        require(server.getAllLevels() != null && CarrierSavedData.get(server).ships().values().stream().filter(s -> !s.destroyed).count() == 1,
            "exactly one existing carrier required");
        ServerLevel level = (ServerLevel)carrier.level();
        List<CarrierServiceBay.Identity> identities = new ArrayList<>();
        for (UUID id : baseline.drones()) {
            Entity found = level.getEntity(id);
            require(found instanceof DroneEntity d && d.isOwnedBy(baseline.owner()) && d.entityTags().contains(baseline.tag()), "recorded drone unavailable");
            DroneEntity drone = (DroneEntity)found;
            require(drone.mode() == DroneMode.STANDBY && drone.carrierServiceAvailable(baseline.ship()),
                "small drone busy; end its current work through ordinary controls first: " + drone.unitId());
            identities.add(CarrierDroneServiceAdapter.identity(drone));
        }
        BlockPos arena = fixtureArena(level, carrier);
        require(arena != null, "no bounded nearby loaded AIR-only entity-free fixture chunk; move the carrier normally and retry");
        List<CarrierAnchor> barrels = new ArrayList<>();
        int cabinX = CarrierPolicy.cabinX(ship.cabin), cabinZ = CarrierPolicy.cabinZ(ship.cabin);
        Vec3 entry = Vec3.atBottomCenterOf(CarrierInterior.entry(ship));
        for (CabinCell cell : warehouseCandidates(player.getX() - cabinX, player.getZ() - cabinZ)) {
            BlockPos p = new BlockPos(cabinX + cell.x(), 65, cabinZ + cell.z());
            if (!loaded(player.level(), p) || !CarrierInterior.allowed(player, p, true) || !player.level().getBlockState(p).isAir()
                || player.distanceToSqr(Vec3.atCenterOf(p)) >= WAREHOUSE_REACH_SQUARED
                || entry.distanceToSqr(Vec3.atCenterOf(p)) >= WAREHOUSE_REACH_SQUARED) continue;
            barrels.add(new CarrierAnchor(player.level().dimension().identifier().toString(), p.getX(), p.getY(), p.getZ()));
            if (barrels.size() == 10) break;
        }
        require(barrels.size() == 10, "fewer than 10 AIR-only cabin barrel positions are reachable from both owner and normal entry");
        require(player.containerMenu.getCarried().isEmpty() && emptyPlayerSlot(player) >= 0, "empty cursor and one empty main inventory slot required");
        data.manifest = Optional.of(new CarrierAcceptanceData.Manifest(UUID.randomUUID(), baseline.run(), player.getUUID(), baseline.ship(),
            CarrierAnchor.at(carrier), arena, identities, barrels, Optional.ofNullable(ship.progress)));
        data.initial = Optional.of(new CarrierAcceptanceData.Snapshot(copy(ship.cargo), copy(player.getInventory()), copy(ship.supplies),
            List.of(), Optional.ofNullable(ship.progress), Optional.empty()));
        data.restart = Optional.empty(); data.restartEvidence = Optional.empty(); data.cursor = 0; data.placed = 0; data.generation = NONE;
        data.minedWords.clear(); data.shell.clear(); data.restartBlocks.clear(); data.targets.clear(); data.counts.clear(); data.passed.clear();
        data.boot = boot; data.resume = "";
        Run run = new Run(player, data);
        run.begin("PREFLIGHT");
        say(player, "PLAN / 16384 mineable + 256 bedrock; 256 columns; 10 real cabin barrels; original 1 carrier + 4 drones reused"
            + " / AIR-only at " + arena + " / baseline READY unchanged / run=" + data.plan().run());
        return 1;
    }

    private static final class Run {
        final MinecraftServer server;
        final ServerLevel level;
        final CarrierAcceptanceData data;
        final CarrierEntity carrier;
        final CarrierShip ship;
        long phaseAt, started, lastSequence;
        Vec3 previousPosition;
        double traveled;
        int shellIndex;
        int combatCycle;
        String observedPhase = "";
        UUID combatGeneration = NONE;
        BlockPos ownedDestination;
        BlockPos departureTarget;
        CarrierAnchor departureIntent;
        Vec3 departurePosition;
        String departureStage;
        final Map<UUID, Float> health = new HashMap<>();
        final Map<BlockPos, BlockState> combatTerrain = new java.util.LinkedHashMap<>();
        CarrierAcceptanceCombatEvidence combatEvidence = new CarrierAcceptanceCombatEvidence();
        int previousEnergy = -1, travelEnergySpent;

        Run(ServerPlayer player, CarrierAcceptanceData data) {
            this.server = player.level().getServer(); this.data = data;
            carrier = CarrierModule.find(server, data.plan().ship());
            require(carrier != null && carrier.ship() != null && carrier.ship().owner.equals(data.plan().owner()), "existing owned carrier unavailable");
            level = (ServerLevel)carrier.level(); ship = carrier.ship();
            require(data.plan().origin().dimension().equals(level.dimension().identifier().toString()), "exterior dimension changed");
            require(carrier.entityTags().contains("MG-CARRIER-VERIFY-" + data.plan().baseline()), "baseline carrier tag changed");
            started = server.getTickCount(); phaseAt = started; lastSequence = carrier.captureSequence();
        }
        void begin(String phase) { data.cursor = 0; next(phase); active = this; }
        void next(String phase) { data.mark(phase, phase); phaseAt = server.getTickCount(); }
        long elapsed() { return server.getTickCount() - phaseAt; }
        ServerPlayer player() {
            ServerPlayer player = server.getPlayerList().getPlayer(data.plan().owner());
            require(player != null && allowed(player) && data.plan().ship().equals(CarrierInterior.currentShip(player)),
                "owner must remain in the authorized carrier cabin; no external position proxy");
            return player;
        }
        void tick() {
            ServerPlayer player = player();
            require(server.getTickCount() - started <= 16000, "bounded run timeout; no automatic retry");
            require(!ship.destroyed && level.getEntity(data.plan().ship()) == carrier && carrier.controls(player), "carrier became unavailable");
            require(player.containerMenu.getCarried().isEmpty(), "player cursor occupied; pause to avoid overwriting items");
            if (server.getTickCount() % 40 == 0) checkDrones(false);
            if (server.getTickCount() % 200 == 0) say(player, "RUN " + data.stage + " / blocks=" + data.count("broken") + "/16384"
                + " / drops=" + data.count("captured") + " / cursor=" + (ship.progress == null ? 0 : ship.progress.cursor())
                + " / status=" + ship.stop + " / phase=" + carrier.workPhase() + ":" + carrier.phaseTick() + "/" + carrier.phaseDuration());
            switch (data.stage) {
                case "PREFLIGHT" -> preflight(player);
                case "POSITION_FUEL" -> {
                    require(elapsed() < 120, "initial ordinary flight-cell charging timed out");
                    if (ship.energy >= 1000 && elapsed() >= 6) {
                        command(player, CarrierCommandPayload.Action.MOVE, NONE, destination());
                        require(ship.destination != null && !ship.navigationPaused, "normal MOVE to fixture refused");
                        previousPosition = carrier.position(); next("POSITION");
                    }
                }
                case "POSITION" -> position(player);
                case "BUILD" -> build(player);
                case "BUILD_BARRELS" -> buildBarrels(player);
                case "FILL" -> fill(player);
                case "ARM_FIRST" -> activate(player, "TO_FULL");
                case "TO_FULL" -> awaitFull(player, false);
                case "FREE_ONE" -> freeOne(player);
                case "FREE_PREVIEW_WAIT" -> { if (elapsed() >= 6) { preview(player, true); next("ARM_RESUME"); } }
                case "ARM_RESUME" -> activate(player, "RESUMED_FULL");
                case "RESUMED_FULL" -> awaitFull(player, true);
                case "DRAIN_FILLER" -> { if (drainFiller(player)) { preview(player, true); next("ARM_MINE"); } }
                case "ARM_MINE" -> activate(player, "MINING");
                case "MINING" -> mine(player);
                case "REFUEL_WAIT" -> refuelWait(player);
                case "VERIFY_MINE" -> verifyMine(player);
                case "EXTRACT" -> extract(player);
                case "MANUAL_STORE" -> storeManual(player);
                case "SNAPSHOT_RESTART" -> snapshotRestart(player);
                case "CHECK_RESTART" -> verifyRestartBlocks(player);
                case "TRAVEL_OUT", "TRAVEL_BACK" -> travel(player);
                case "TRAVEL_FUEL", "COMBAT_TRAVEL_FUEL" -> departure(player);
                case "ARM_COMBAT" -> activate(player, "COMBAT");
                case "COMBAT" -> combat(player);
                case "COMBAT_TRAVEL_OUT", "COMBAT_TRAVEL_BACK" -> combatTravel(player);
                case "COMBAT_PREFLIGHT" -> combatTerrain(player, false);
                case "COMBAT_FUEL" -> {
                    require(elapsed() < 500, "ordinary combat-cell charging timed out");
                    if (ship.weaponEnergy >= 10000) setupCombat(player);
                }
                case "VERIFY_COMBAT" -> combatTerrain(player, true);
                case "CLEANUP_FILLER" -> { if (drainFiller(player)) { data.cursor = 0; next("CLEANUP_BLOCKS"); } }
                case "CLEANUP_BLOCKS" -> cleanupBlocks(player);
                default -> throw new IllegalStateException("no active handler for " + data.stage);
            }
            data.setDirty();
        }
        void checkDrones(boolean requireLoaded) {
            for (var identity : data.plan().drones()) {
                Entity e = level.getEntity(identity.drone());
                if (e == null && !requireLoaded && (data.stage.startsWith("TRAVEL") || data.stage.contains("COMBAT"))) continue;
                require(e instanceof DroneEntity d && d.isOwnedBy(identity.owner())
                    && CarrierDroneServiceAdapter.identity(d).equals(identity), "original drone missing/identity or mission changed");
                DroneEntity d = (DroneEntity)e;
                require(!d.combatActive() && !d.emergencyInterceptActive(), "small-drone combat would contaminate carrier measurements");
            }
        }
        boolean miningPhase() { return List.of("TO_FULL", "RESUMED_FULL", "MINING").contains(data.stage); }
        boolean minePosition(BlockPos pos) { BlockPos a = data.plan().arena(); return CarrierAcceptancePolicy.mine(pos.getX() - a.getX(), pos.getY() - a.getY(), pos.getZ() - a.getZ()); }
        int mineIndex(BlockPos pos) {
            BlockPos a = data.plan().arena();
            return CarrierAcceptancePolicy.mineIndex(pos.getX() - a.getX(), pos.getY() - a.getY(), pos.getZ() - a.getZ());
        }
        BlockPos pos(int index) { return data.plan().arena().offset(x(index), y(index), z(index)); }
        BlockPos shellPos(int index) { return data.plan().arena().offset(index % 18 - 1, index / (18 * 18) - 1, index / 18 % 18 - 1); }
        BlockState expected(BlockPos p) {
            BlockPos a = data.plan().arena();
            int layer = p.getY() - a.getY();
            if (p.getX() == a.getX() && p.getZ() == a.getZ()) {
                if (layer == 1) return Blocks.REDSTONE_ORE.defaultBlockState();
                if (layer == 2) return Blocks.LAPIS_ORE.defaultBlockState();
                if (layer == 3) return Blocks.COPPER_ORE.defaultBlockState();
            }
            return materialState(material(layer));
        }
        void preflight(ServerPlayer player) {
            for (int n = 0; n < WORLD_BUDGET && data.cursor < BOX_VOLUME; n++, data.cursor++) {
                BlockPos p = shellPos(data.cursor); BlockPos a = data.plan().arena();
                require(loaded(level, p), "preflight chunk unloaded");
                boolean inside = fixture(p.getX() - a.getX(), p.getY() - a.getY(), p.getZ() - a.getZ());
                if (inside) require(level.getBlockState(p).isAir(), "AIR-only fixture refused at " + p);
                else data.shell.add(level.getBlockState(p));
            }
            if (data.cursor < BOX_VOLUME) return;
            require(data.shell.size() == SHELL_BLOCKS, "incomplete exterior snapshot");
            refuel(player, false);
            next("POSITION_FUEL");
        }
        BlockPos destination() { return data.plan().arena().offset(8, LAYERS + 10, 8); }
        void position(ServerPlayer player) {
            require(elapsed() < 2000, "normal positioning timed out");
            measureMovement();
            if (ship.destination != null) { require(!ship.navigationPaused, "positioning paused: " + ship.stop); return; }
            require(carrier.position().distanceToSqr(Vec3.atCenterOf(destination())) < 1, "positioning ended before destination");
            data.cursor = 0; next("BUILD");
        }
        void build(ServerPlayer player) {
            for (int n = 0; n < WORLD_BUDGET && data.placed < FIXTURE_BLOCKS; n++) {
                BlockPos p = pos(data.placed);
                require(loaded(level, p) && level.getBlockState(p).isAir(), "fixture changed during setup: " + p);
                BlockState state = expected(p);
                data.placed++; data.setDirty();
                require(level.setBlock(p, state, FLAGS), "fixture placement failed");
            }
            if (data.placed < FIXTURE_BLOCKS) return;
            next("BUILD_BARRELS");
        }
        void buildBarrels(ServerPlayer player) {
            require(data.stage.equals("BUILD_BARRELS"), "warehouse initialization outside its build stage");
            while (data.count("barrels") < 10) {
                int index = data.count("barrels"); CarrierAnchor at = data.plan().barrels().get(index);
                require(at.level(server) == player.level() && loaded(player.level(), at.pos()) && player.level().getBlockState(at.pos()).isAir(), "barrel AIR preflight changed");
                require(player.level().setBlock(at.pos(), Blocks.BARREL.defaultBlockState(), FLAGS), "barrel placement failed");
                // Only this successful AIR-only placement may initialize an unmarked container.
                require(player.level().getBlockEntity(at.pos()) instanceof Container, "new barrel container missing");
                Container barrel = (Container)player.level().getBlockEntity(at.pos());
                require(barrel.isEmpty(), "new barrel not empty");
                barrel.setItem(26, marker(Items.PAPER, 1, "warehouse")); barrel.setChanged();
                require(barrel(index) == barrel, "new warehouse marker readback failed");
                data.add("barrels", 1);
                CarrierProtection.recordPlayerBuild(player.level(), at.pos());
            }
            pass("FIXTURE", "16384 real mineable blocks + 256 bedrock, 10 AIR-only cabin barrels; no existing blocks overwritten");
            data.cursor = 0; next("FILL");
        }
        void fill(ServerPlayer player) {
            CarrierMenu menu = carrierMenu(player);
            int size = ship.cargo.getContainerSize();
            for (int n = 0; n < CLICK_BUDGET / 2 - 1 && data.cursor < size; n++) {
                int slot = data.cursor;
                if (!ship.cargo.getItem(slot).isEmpty()) { data.cursor++; continue; }
                if (!page(player, slot / 54)) return;
                menu = (CarrierMenu)player.containerMenu;
                int scratch = emptyPlayerSlot(player); require(scratch >= 0, "no empty player scratch slot");
                ItemStack created = marker(Items.PAPER, 64, "filler");
                player.getInventory().setItem(scratch, created); data.add("filler_created", 64);
                click(menu, menuPlayerSlot(menu, player, scratch), ContainerInput.PICKUP, player);
                click(menu, slot % 54, ContainerInput.PICKUP, player);
                require(ship.cargo.getItem(slot).getCount() == 64 && isMarker(ship.cargo.getItem(slot), "filler"), "ordinary filler insert failed");
                require(same(data.initial.orElseThrow().player(), copy(player.getInventory())), "filler insertion changed original player inventory");
                data.cursor++;
            }
            if (data.cursor < size) return;
            refuel(player, true); preview(player, false); next("ARM_FIRST");
        }
		void preview(ServerPlayer player, boolean resume) {
            require(ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null, "cannot replace active work");
            command(player, resume ? CarrierCommandPayload.Action.RESUME_PREVIEW : CarrierCommandPayload.Action.PREVIEW_MINING, NONE, data.plan().arena());
			require(ship.preview != null && ship.previewMode == CarrierPolicy.Mode.MINING, "normal mining preview refused");
			require(ship.preview.minY() == level.getMinY(), "mining preview does not start at dimension floor: " + ship.preview.minY());
			require(ship.preview.maxY() == CarrierPolicy.operationTop(carrier.getY()),
				"mining preview does not end directly below carrier: " + ship.preview.maxY());
			require(ship.preview.volume() == 256L * (ship.preview.maxY() - level.getMinY() + 1L),
				"mining preview is not a full 16x16 vertical column: " + ship.preview.volume());
			if (resume) require(ship.preview.generation().equals(data.generation), "resume changed generation");
            else { data.generation = ship.preview.generation(); data.setDirty(); }
        }
        void activate(ServerPlayer player, String phase) {
            if (elapsed() < 6) return;
            if (ship.weaponEnergy < 10 && elapsed() < 100 && !ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty()) return;
            require(ship.preview != null, "preview disappeared");
            command(player, CarrierCommandPayload.Action.ACTIVATE, ship.preview.generation(), data.plan().arena());
            require(ship.mode == (phase.equals("COMBAT") ? CarrierPolicy.Mode.COMBAT : CarrierPolicy.Mode.MINING), "normal activation refused");
            next(phase);
        }
        void awaitFull(ServerPlayer player, boolean resumed) {
            refuel(player, true);
            require(elapsed() < 1800, "FULL was not reached within bounded mining interval");
            if (ship.mode == CarrierPolicy.Mode.MINING) return;
            require(ship.stop == CarrierPolicy.Stop.FULL && ship.pending != null && minePosition(ship.pending.pos())
                && level.getBlockState(ship.pending.pos()).equals(ship.pending.state()), "FULL must retain real block and pending vanilla drops: " + ship.stop);
            verifyConservation(player, false);
            if (!resumed) {
                data.counts.put("full_cursor", ship.progress.cursor());
                data.counts.put("full_pending_index", mineIndex(ship.pending.pos()));
                data.counts.put("full_broken", data.count("broken"));
                data.resume = "FREE_ONE"; data.mark("FULL_WAIT", "real FULL with pending block retained; restart_prepare or deep_resume");
                pass("M07_FULL", "normal cargo capacity exhausted before destroying pending block; cursor=" + ship.progress.cursor());
                active = null;
            } else {
                require(ship.progress.cursor() > data.count("full_cursor"), "freeing space did not advance the same operation");
                require(data.breakRecorded(data.count("full_pending_index")), "the retained FULL block was not captured after resume");
                require(data.count("broken") > data.count("full_broken"), "resume did not produce a new unique capture");
                require(mineIndex(ship.pending.pos()) != data.count("full_pending_index"), "resume stopped on the original pending block again");
                pass("M07_RESUME", "same generation advanced and reached FULL again, without loss/duplication");
                data.cursor = 0; next("DRAIN_FILLER");
            }
        }
        void resumeMining() {
            ServerPlayer player = player();
            require(ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null && ship.progress != null
                && ship.progress.operation().generation().equals(data.generation) && !ship.progress.complete(), "saved mining assignment changed or complete");
            require(data.boot.equals(boot), "different server boot: restart_check before resume");
            verifyConservation(player, false);
            if (!data.passed.contains("M07_RESUME")) begin("FREE_ONE");
            else { data.cursor = 0; begin("DRAIN_FILLER"); }
        }
        void freeOne(ServerPlayer player) {
            for (int n = 0; n < 54 && data.cursor < ship.cargo.getContainerSize(); n++, data.cursor++) {
                if (!isMarker(ship.cargo.getItem(data.cursor), "filler")) continue;
                if (!takeCargo(player, data.cursor, true)) return;
                next("FREE_PREVIEW_WAIT"); return;
            }
            require(data.cursor < ship.cargo.getContainerSize(), "no owned filler to free");
        }
        boolean drainFiller(ServerPlayer player) {
            for (int n = 0; n < CLICK_BUDGET / 2 && data.cursor < ship.cargo.getContainerSize(); n++) {
                int slot = data.cursor;
                if (isMarker(ship.cargo.getItem(slot), "filler") && !takeCargo(player, slot, true)) return false;
                data.cursor++;
            }
            if (data.cursor < ship.cargo.getContainerSize()) return false;
            require(data.count("filler_created") == data.count("filler_removed"), "filler conservation mismatch; nothing overwritten");
            return true;
        }
        void mine(ServerPlayer player) {
            require(elapsed() < 6000, "deep mining progress timeout: " + ship.stop);
            if (ship.mode == CarrierPolicy.Mode.MINING) return;
            require(ship.stop == CarrierPolicy.Stop.COMPLETE && ship.progress != null && ship.progress.complete()
                && ship.pending == null && ship.progress.sealedColumns().size() == 256, "not full operation completion: " + ship.stop);
            require(data.count("broken") == MINE_BLOCKS, "real AFTER count is not 16384: " + data.count("broken"));
            require(data.recordedBreaks() == MINE_BLOCKS, "unique captured fixture positions are not 16384: " + data.recordedBreaks());
            pass("M08_POWER", "reactor sustained the same mining generation without cell-assisted restart");
            data.cursor = 0; shellIndex = 0; next("VERIFY_MINE");
        }
        void refuelWait(ServerPlayer player) {
            require(elapsed() < 1600 && ship.mode == CarrierPolicy.Mode.IDLE
                && ship.progress.cursor() == data.count("power_cursor"), "power wait changed paused cursor or timed out");
            if (ship.weaponEnergy < 50000 && !ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty()) return;
            require(ship.weaponEnergy >= 1000, "normal laser-cell charging failed");
            data.add("power_resumes", 1);
            pass("M08_POWER", "NO_POWER retained cursor; normal typed laser supply charged tank; explicit same-generation resume");
            preview(player, true); next("ARM_MINE");
        }
        void verifyMine(ServerPlayer player) {
            for (int n = 0; n < WORLD_BUDGET && data.cursor < BOX_VOLUME; n++, data.cursor++) {
                BlockPos p = shellPos(data.cursor), a = data.plan().arena();
                require(loaded(level, p), "verification chunk unloaded");
                int dx = p.getX() - a.getX(), dy = p.getY() - a.getY(), dz = p.getZ() - a.getZ();
                BlockState actual = level.getBlockState(p);
                if (fixture(dx, dy, dz)) {
                    require(dy == 0 ? actual.is(Blocks.BEDROCK) : actual.isAir(), "unmined column/bedrock changed: " + p);
                    if (dy > 0) require(data.breakRecorded(CarrierAcceptancePolicy.mineIndex(dx, dy, dz)), "missing unique break evidence: " + p);
                }
                else require(actual.equals(data.shell.get(shellIndex++)), "outside fixture changed: " + p);
            }
            if (data.cursor < BOX_VOLUME) return;
            verifyConservation(player, false);
            pass("M01-M03", "256/256 bedrock columns; 16384 actual breaks; all surrounding shell states unchanged; actual committed vanilla drops=" + data.count("captured"));
            data.mark("MINED", "all drops stored; extract uses normal menu.clicked -> real player -> real barrels; visual checks remain separate");
            active = null;
        }

        void extract(ServerPlayer player) {
            require(ship.mode == CarrierPolicy.Mode.IDLE && ship.pending == null, "extraction requires completed idle operation");
            int size = ship.cargo.getContainerSize();
            for (int n = 0; n < CLICK_BUDGET / 4 && data.cursor < size; n++) {
                int slot = data.cursor;
                ItemStack current = ship.cargo.getItem(slot);
                ItemStack original = data.initial.orElseThrow().cargo().get(slot);
                if (ItemStack.matches(current, original)) { data.cursor++; continue; }
                require(original.isEmpty() || ItemStack.isSameItemSameComponents(original, current), "original cargo slot changed type; no blind restoration");
                int extra = current.getCount() - original.getCount();
                require(extra > 0 && data.count("loot:" + itemId(current)) > 0, "non-fixture cargo in changed slot");
                var planned = warehousePlan(warehouses(), current.copyWithCount(extra), warehouseTargets(3));
                int capacity = planned.stream().mapToInt(WarehouseMove::count).sum();
                require(capacity > 0, "fixture warehouse full; cargo retained");
                int amount = extractionCount(current, original, capacity);
                boolean wholeStack = original.isEmpty() && amount == extra;
                if (!page(player, slot / 54)) return;
                CarrierMenu menu = (CarrierMenu)player.containerMenu;
                int scratch = emptyPlayerSlot(player); require(scratch >= 0, "one empty main inventory slot required");
                ItemStack transfer = current.copyWithCount(amount);
                if (wholeStack) {
                    click(menu, slot % 54, ContainerInput.PICKUP, player);
                    click(menu, menuPlayerSlot(menu, player, scratch), ContainerInput.PICKUP, player);
                } else {
                    // Preserve baseline cargo and bound fragmented transfers to this tick's click budget.
                    click(menu, slot % 54, ContainerInput.PICKUP, player);
                    menu.clicked(menuPlayerSlot(menu, player, scratch), 1, ContainerInput.PICKUP, player);
                    click(menu, slot % 54, ContainerInput.PICKUP, player);
                }
                require(ItemStack.matches(transfer, player.getInventory().getItem(scratch)), "real player did not receive the expected mined stack");
                require(store(player, scratch, transfer, warehouseTargets(wholeStack ? 2 : 3), "extracted") == transfer.getCount(),
                    "bounded warehouse transfer incomplete; remaining real player items retained");
                require(same(data.initial.orElseThrow().player(), copy(player.getInventory())), "extraction changed original player inventory");
                if (ship.cargo.getItem(slot).getCount() == original.getCount()) data.cursor++;
                // Opening a real barrel changes containerId; reopen the carrier only in the next bounded tick.
                break;
            }
            if (data.cursor < size) return;
            verifyConservation(player, true);
            require(data.count("extracted") + data.count("manual_extracted") == data.count("captured"), "not all generated loot passed through player inventory");
            require(same(data.initial.orElseThrow().cargo(), copy(ship.cargo)), "baseline cargo changed after full extraction");
            pass("M05-M06", "automatic=" + data.count("extracted") + ", operator UI audited=" + data.count("manual_extracted")
                + "; all drops through real player -> 10 real barrels; original inventory/cargo unchanged");
            data.mark("EXTRACTED", "server-menu evidence, not human UI evidence; barrel contents retained for inspection/restart"); active = null;
        }
        void beginManual() {
            ServerPlayer player = player();
            require(data.stage.equals("MINED") && data.passed.contains("M01-M03") && !data.passed.contains("M05-M06") && ship.mode == CarrierPolicy.Mode.IDLE,
                "manual sample belongs between completed mining and automatic extraction");
            require(same(data.initial.orElseThrow().player(), copy(player.getInventory())), "original player inventory already changed");
            verifyConservation(player, false);
            data.restart = Optional.of(snapshot(player));
            data.mark("MANUAL_WAIT", "use normal UI to take <=64 mined items into originally empty inventory slots, then manual_audit; no unrelated moves");
            say(player, data.message);
            for (int slot = 0; slot < ship.cargo.getContainerSize(); slot++) {
                ItemStack stack = ship.cargo.getItem(slot);
                if (!stack.isEmpty() && data.initial.orElseThrow().cargo().get(slot).isEmpty()) {
                    say(player, "UI SAMPLE / page=" + (slot / 54 + 1) + " / slot=" + (slot % 54 + 1) + " / item=" + itemId(stack)
                        + " / count=" + stack.getCount() + " / emptyPlayerSlot=" + (emptyPlayerSlot(player) + 1)); break;
                }
            }
        }
        void auditManual() {
            ServerPlayer player = player();
            require(data.stage.equals("MANUAL_WAIT") && data.restart.isPresent(), "manual_begin required");
            require(player.containerMenu.getCarried().isEmpty(), "finish placing the held stack before manual_audit; manual wait retained");
            var before = data.restart.orElseThrow();
            int gained = 0;
            for (int i = 0; i < before.player().size(); i++) {
                ItemStack was = before.player().get(i), now = player.getInventory().getItem(i);
                if (ItemStack.matches(was, now)) continue;
                require(i < 36 && was.isEmpty() && !now.isEmpty() && data.count("loot:" + itemId(now)) > 0,
                    "manual sample must use originally empty main inventory slots; no baseline item changes");
                gained += now.getCount();
            }
            require(gained > 0 && gained <= 64, "manual sample must contain 1..64 real mined items");
            require(itemCount(before.cargo()) - itemCount(copy(ship.cargo)) == gained,
                "manual cargo decrease does not equal player inventory gain");
            require(baselineCargoRetained(data.initial.orElseThrow().cargo(), ship.cargo),
                "manual sample removed baseline cargo; return that stack normally before manual_audit");
            require(same(before.storage(), storage()), "manual sample changed warehouse contents");
            verifyConservation(player, false);
            begin("MANUAL_STORE");
        }
        void storeManual(ServerPlayer player) {
            for (; data.cursor < 36; data.cursor++) {
                ItemStack original = data.initial.orElseThrow().player().get(data.cursor);
                ItemStack now = player.getInventory().getItem(data.cursor);
                if (ItemStack.matches(original, now)) continue;
                require(original.isEmpty() && data.count("loot:" + itemId(now)) > 0, "manual transfer changed after audit");
                store(player, data.cursor, now.copy(), warehouseTargets(0), "manual_extracted");
                if (player.getInventory().getItem(data.cursor).isEmpty()) data.cursor++;
                return;
            }
            require(same(data.initial.orElseThrow().player(), copy(player.getInventory())), "manual sample did not preserve original inventory");
            verifyConservation(player, false);
            pass("M05_MANUAL_AUDIT", "operator-requested UI sample cargo decrease/player increase audited=" + data.count("manual_extracted")
                + "; then normal barrel menu transfer; visual/human interaction attribution requires parent observation");
            data.mark("MINED", "manual sample separately accounted; extract moves only remaining cargo"); active = null;
        }
        List<Container> warehouses() {
            List<Container> result = new ArrayList<>(10);
            for (int index = 0; index < 10; index++) result.add(barrel(index));
            return result;
        }
        int store(ServerPlayer player, int inventorySlot, ItemStack transfer, int targetLimit, String counter) {
            require(ItemStack.matches(transfer, player.getInventory().getItem(inventorySlot))
                && player.containerMenu.getCarried().isEmpty(), "warehouse source changed before transfer");
            List<Container> barrels = warehouses();
            var plan = warehousePlan(barrels, transfer, targetLimit);
            require(!plan.isEmpty(), "fixture warehouse full; actual loot retained, never deleted");
            int moved = 0;
            for (WarehouseMove move : plan) {
                Container target = barrels.get(move.barrel());
                require(ItemStack.matches(move.before(), target.getItem(move.slot())), "warehouse destination changed before transfer");
                ItemStack source = transfer.copyWithCount(transfer.getCount() - moved);
                require(ItemStack.matches(source, player.getInventory().getItem(inventorySlot)), "warehouse source changed during transfer");
                CarrierAnchor at = data.plan().barrels().get(move.barrel());
                require(at.level(server) == player.level() && player.distanceToSqr(Vec3.atCenterOf(at.pos())) < 64, "warehouse is outside normal menu reach");
                require(player.level().getBlockEntity(at.pos()) instanceof MenuProvider, "real barrel menu provider missing");
                player.openMenu((MenuProvider)player.level().getBlockEntity(at.pos()));
                AbstractContainerMenu menu = player.containerMenu;
                require(!(menu instanceof CarrierMenu) && menu != player.inventoryMenu && menu.stillValid(player), "normal barrel menu refused");
                require(menu.slots.get(move.slot()).container == target
                    && menu.slots.get(move.slot()).getContainerSlot() == move.slot(), "normal barrel slot mapping changed");
                int sourceSlot = menuPlayerSlot(menu, player, inventorySlot);
                click(menu, sourceSlot, ContainerInput.PICKUP, player);
                try {
                    require(ItemStack.matches(source, menu.getCarried()) && player.getInventory().getItem(inventorySlot).isEmpty(),
                        "normal warehouse source pickup refused");
                    click(menu, move.slot(), ContainerInput.PICKUP, player);
                } finally {
                    if (!menu.getCarried().isEmpty()) click(menu, sourceSlot, ContainerInput.PICKUP, player);
                }
                require(menu.getCarried().isEmpty()
                    && ItemStack.matches(transfer.copyWithCount(move.before().getCount() + move.count()), target.getItem(move.slot()))
                    && ItemStack.matches(source.copyWithCount(source.getCount() - move.count()), player.getInventory().getItem(inventorySlot)),
                    "normal warehouse transfer count/components mismatch; real items retained");
                moved += move.count();
                data.add(counter, move.count());
            }
            return moved;
        }
        void prepareRestart() {
            ServerPlayer player = player();
            require(stableRestartCheckpoint(data.stage), "restart checkpoint requires a stable stopped stage");
            require(ship.mode == CarrierPolicy.Mode.IDLE && (ship.destination == null || ship.navigationPaused), "stop normally before preparing restart");
            require(ship.supplies.getItem(CarrierSupplies.FLIGHT).isEmpty() || !CarrierSupplies.canCharge(ship.energy), "flight supply is still charging; wait or remove normally");
            require(ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty() || !CarrierSupplies.canCharge(ship.weaponEnergy), "laser supply is still charging; wait or remove normally");
            checkDrones(true); verifyConservation(player, data.passed.contains("M05-M06"));
            String checkpointResume = data.stage.equals("PAUSED") ? data.resume : data.stage;
            require(!checkpointResume.isBlank() && !checkpointResume.equals("PAUSED"), "paused checkpoint has no resumable prior stage");
            data.resume = checkpointResume; data.restart = Optional.of(snapshot(player)); data.boot = boot;
            data.restartEvidence = Optional.of(evidence());
            data.restartBlocks.clear(); begin("SNAPSHOT_RESTART");
        }
        void snapshotRestart(ServerPlayer player) {
            for (int n = 0; n < WORLD_BUDGET && data.cursor < BOX_VOLUME; n++, data.cursor++) {
                BlockPos p = shellPos(data.cursor); require(loaded(level, p), "restart snapshot chunk unloaded");
                data.restartBlocks.add(level.getBlockState(p));
            }
            if (data.cursor < BOX_VOLUME) return;
            require(sameSnapshot(data.restart.orElseThrow(), snapshot(player)), "inventory/cursor changed during checkpoint");
            require(data.restartEvidence.equals(Optional.of(evidence())), "acceptance evidence changed during checkpoint");
            data.mark("RESTART_PREPARED", "save and quit normally, restart in cabin, then restart_check; baseline READY/PASS retained");
            active = null; say(player, "PREPARED / " + data.message);
        }
        void checkRestart() {
            ServerPlayer player = player();
            require(data.stage.equals("RESTART_PREPARED") && restartIsEvidence(data.boot, boot), "actual different server boot required");
            require(ship.mode == CarrierPolicy.Mode.IDLE && (ship.destination == null || ship.navigationPaused), "work unexpectedly resumed after restart");
            require(sameSnapshot(data.restart.orElseThrow(), snapshot(player)), "saved cargo/player/supply/storage/progress/pending mismatch");
            require(data.restartEvidence.equals(Optional.of(evidence())), "saved unique-break/count/pass/generation evidence mismatch");
            checkDrones(true); begin("CHECK_RESTART");
        }
        void verifyRestartBlocks(ServerPlayer player) {
            for (int n = 0; n < WORLD_BUDGET && data.cursor < data.restartBlocks.size(); n++, data.cursor++) {
                BlockPos p = shellPos(data.cursor); require(loaded(level, p) && level.getBlockState(p).equals(data.restartBlocks.get(data.cursor)), "fixture or six-face neighbor changed across restart: " + p);
            }
            if (data.cursor < data.restartBlocks.size()) return;
            data.boot = boot;
            pass("M08_RESTART", "different server boot; exact inventories, pending drops, progress, unique-break ledger, counters, generation, full fixture and six-face neighbors retained; no auto resume");
            data.mark(data.resume, "restart verified; resume remains explicit"); active = null;
        }
        CarrierAcceptanceData.Snapshot snapshot(ServerPlayer player) {
            return new CarrierAcceptanceData.Snapshot(copy(ship.cargo), copy(player.getInventory()), copy(ship.supplies), storage(),
                Optional.ofNullable(ship.progress), Optional.ofNullable(ship.pending));
        }
        CarrierAcceptanceData.Evidence evidence() {
            return new CarrierAcceptanceData.Evidence(data.generation, data.minedWords, data.counts, data.passed, data.resume);
        }

        void startTravel() {
            ServerPlayer player = player();
            require(data.passed.contains("M01-M03") && ship.mode == CarrierPolicy.Mode.IDLE && ship.preview == null
                && (ship.destination == null || ship.navigationPaused), "finish/stop work before travel; no mission replacement");
            checkDrones(true);
            BlockPos start = carrier.blockPosition();
            data.counts.put("travel_x", start.getX()); data.counts.put("travel_y", start.getY()); data.counts.put("travel_z", start.getZ());
            pausedTravel = null;
            traveled = 0;
            travelEnergySpent = 0;
            previousEnergy = ship.energy;
            waitForDeparture(player, start.offset(TRAVEL_DISTANCE, 0, 0), "TRAVEL_OUT", false);
        }
        void waitForDeparture(ServerPlayer player, BlockPos target, String stage, boolean resume) {
            refuel(player, false);
            departureTarget = target; departureStage = stage;
            departureIntent = ship.destination; departurePosition = carrier.position();
            if (!resume) previousPosition = carrier.position();
            begin(stage.startsWith("COMBAT") ? "COMBAT_TRAVEL_FUEL" : "TRAVEL_FUEL");
        }
        void departure(ServerPlayer player) {
            require(ship.mode == CarrierPolicy.Mode.IDLE && ship.preview == null
                && java.util.Objects.equals(ship.destination, departureIntent)
                && (ship.destination == null || ship.navigationPaused)
                && CarrierAcceptanceDeparture.unchanged(departurePosition, carrier.position()),
                "voyage changed during fuel wait; unrelated commands are not replaced");
            refuel(player, false);
            var state = CarrierAcceptanceDeparture.state(elapsed(), ship.energy);
            require(state != CarrierAcceptanceDeparture.State.TIMEOUT, "ordinary flight-cell charging timed out; journey not restarted");
            if (state != CarrierAcceptanceDeparture.State.READY) return;
            command(player, CarrierCommandPayload.Action.MOVE, NONE, departureTarget);
            require(ship.destination != null && ship.destination.pos().equals(departureTarget) && !ship.navigationPaused,
                "ordinary departure MOVE refused");
            data.counts.put("travel_fuel", ship.energy);
            previousPosition = carrier.position(); previousEnergy = ship.energy; next(departureStage);
        }
        void resumeTravel() {
            ServerPlayer player = player();
            require(level.getEntity(data.plan().ship()) == carrier && !ship.destroyed && carrier.controls(player)
                && ship.mode == CarrierPolicy.Mode.IDLE && ship.preview == null
                && ship.destination != null && ship.navigationPaused && ship.destination.pos().equals(ownedDestination)
                && CarrierAcceptanceDeparture.unchanged(previousPosition, carrier.position())
                && List.of("TRAVEL_OUT", "TRAVEL_BACK").contains(data.resume),
                "paused journey changed; unobserved travel cannot count as verified movement");
            require(player.containerMenu.getCarried().isEmpty(), "empty player cursor required");
            waitForDeparture(player, ownedDestination, data.resume, true);
            pausedTravel = null;
        }
        void measureMovement() {
            double squared = previousPosition.distanceToSqr(carrier.position());
            require(continuousStep(squared), "discontinuous/teleported carrier movement");
            traveled += Math.sqrt(squared); previousPosition = carrier.position();
            if (previousEnergy >= 0) travelEnergySpent += observedEnergySpend(previousEnergy, ship.energy);
            previousEnergy = ship.energy;
        }
        void travel(ServerPlayer player) {
            require(elapsed() < 7000, "bounded 544-block travel timeout");
            measureMovement(); refuel(player, false);
            if (ship.navigationPaused) {
                data.resume = data.stage;
                data.mark("TRAVEL_PAUSED", "normal navigation pause=" + ship.stop
                    + "; same-boot travel_resume preserves this measured journey only while position/destination are unchanged"
                    + "; manual MOVE or reload invalidates this interrupted measurement; no teleport");
                pausedTravel = this; active = null; say(player, data.message); return;
            }
            if (ship.destination != null) return;
            BlockPos start = new BlockPos(data.count("travel_x"), data.count("travel_y"), data.count("travel_z"));
            BlockPos expected = data.stage.equals("TRAVEL_OUT") ? start.offset(TRAVEL_DISTANCE, 0, 0) : start;
            require(carrier.position().distanceToSqr(Vec3.atCenterOf(expected)) < 1 && traveled >= 512, "not a continuous 512+ arrival");
            require(travelEnergySpent > 0, "arrival had no observed flight-energy debit");
            say(player, "MEASURE NAVIGATION / distance=" + traveled + " / fuelNow=" + ship.energy + " / " + carrier.flightMetrics());
            if (data.stage.equals("TRAVEL_OUT")) {
                pass("N01-N02_OUT", "544-block actual movement with owner in cabin, continuous <=1 block/tick; no teleports");
                command(player, CarrierCommandPayload.Action.MOVE, NONE, start);
                require(ship.destination != null && !ship.navigationPaused, "normal return MOVE refused");
                traveled = 0; travelEnergySpent = 0; previousEnergy = ship.energy;
                previousPosition = carrier.position(); next("TRAVEL_BACK");
            } else {
                pass("N01-N02", "544-block outbound + 544-block return, ordinary MOVE and real flight energy consumption");
                data.mark("TRAVEL_DONE", "N03 ticket/performance readouts logged; obstacle/fuel/reload fault cases remain separate"); active = null;
            }
        }

        void startCombat() {
            ServerPlayer player = player();
            require(data.passed.contains("M01-M03") && ship.mode == CarrierPolicy.Mode.IDLE && ship.destination == null && ship.preview == null,
                "completed mining, stationary carrier and no current work required");
            require(data.targets.isEmpty(), "combat fixture retained; cleanup before another combat generation");
            require(carrier.position().distanceToSqr(Vec3.atCenterOf(destination())) < 1, "return normally to the deep fixture before combat");
            checkDrones(true);
            traveled = 0;
            travelEnergySpent = 0;
            previousEnergy = ship.energy;
            waitForDeparture(player, combatDestination(), "COMBAT_TRAVEL_OUT", false);
        }
        BlockPos combatArena() { return data.plan().arena().offset(TRAVEL_DISTANCE, 0, 0); }
        BlockPos combatDestination() { return destination().offset(TRAVEL_DISTANCE, 0, 0); }
        void combatIsolation() {
            for (var identity : data.plan().drones()) {
                Entity entity = level.getEntity(identity.drone());
                require(entity == null || entity.position().distanceToSqr(Vec3.atCenterOf(combatArena())) > 256 * 256,
                    "original small drone within combat isolation radius; NoAI hostiles are still detectable");
            }
            Vec3 at = Vec3.atCenterOf(combatArena());
            require(level.getEntitiesOfClass(DroneEntity.class, new AABB(at, at).inflate(256)).isEmpty(), "other small drone could contaminate combat");
        }
        void combatTravel(ServerPlayer player) {
            require(elapsed() < 7000, "isolated combat voyage timed out");
            measureMovement(); refuel(player, false);
            require(!ship.navigationPaused, "combat voyage paused safely: " + ship.stop);
            if (ship.destination != null) return;
            boolean outbound = data.stage.equals("COMBAT_TRAVEL_OUT");
            require(carrier.position().distanceToSqr(Vec3.atCenterOf(outbound ? combatDestination() : destination())) < 1 && traveled >= 512,
                "combat isolation voyage did not arrive continuously");
            require(travelEnergySpent > 0, "combat isolation voyage had no observed flight-energy debit");
            if (outbound) { combatIsolation(); data.cursor = 0; next("COMBAT_PREFLIGHT"); }
            else {
                checkDrones(true);
                pass("COMBAT_ISOLATION_RETURN", "normal 544-block outward/return flight; original four identities rechecked; no role/AI/home changes");
                data.mark("COMBAT_RETURNED", "original four identities confirmed after normal return"); active = null;
            }
        }
        BlockPos combatTerrainPosition(int index) {
            BlockPos a = combatArena();
            if (index < 16384) return a.offset(index % 32 - 8, LAYERS - 6 + index / 1024, index / 32 % 32 - 8);
            int column = index - 16384, x = a.getX() + column % 32 - 8, z = a.getZ() + column / 32 - 8;
            require(loaded(level, new BlockPos(x, 0, z)), "combat surface sample chunk unloaded");
            return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
        }
        void combatTerrain(ServerPlayer player, boolean verify) {
            combatIsolation();
            if (!verify) {
                for (int n = 0; n < WORLD_BUDGET && data.cursor < 17408; n++, data.cursor++) {
                    BlockPos p = combatTerrainPosition(data.cursor); require(loaded(level, p), "combat terrain unloaded");
                    combatTerrain.put(p, level.getBlockState(p));
                }
                if (data.cursor == 17408) {
                    if (ship.weaponEnergy < 10000) insertSupply(player, CarrierSupplies.WEAPON, "laser_cell", 16);
                    next("COMBAT_FUEL");
                }
                return;
            }
            List<BlockPos> positions = new ArrayList<>(combatTerrain.keySet());
            for (int n = 0; n < WORLD_BUDGET && data.cursor < positions.size(); n++, data.cursor++) {
                BlockPos p = positions.get(data.cursor);
                require(loaded(level, p) && level.getBlockState(p).equals(combatTerrain.get(p)), "combat changed terrain: " + p);
            }
            if (data.cursor < positions.size()) return;
            pass("A06", "16384 combat-volume states and 1024 real surface-column states unchanged; generated shield removed separately");
            data.mark("COMBAT_DONE", "remote scene retained for ordinary exit/UI filming; combat_return is explicit; A03 >16 fairness/visual quality unverified");
            active = null; say(player, data.message);
        }
        void returnCombat() {
            ServerPlayer player = player();
            require(data.stage.equals("COMBAT_DONE") && ship.mode == CarrierPolicy.Mode.IDLE && (ship.destination == null || ship.navigationPaused), "finish filming and normally board before combat_return");
            for (UUID id : data.targets) {
                Entity e = level.getEntity(id);
                if (e != null) { require(e.entityTags().contains(data.plan().tag()), "changed combat entity retained"); e.discard(); }
            }
            data.counts.put("combat_entities_cleaned", 1);
            command(player, CarrierCommandPayload.Action.MOVE, NONE, destination());
            require(ship.destination != null && !ship.navigationPaused, "ordinary combat return refused");
            previousPosition = carrier.position(); previousEnergy = ship.energy;
            traveled = 0; travelEnergySpent = 0; begin("COMBAT_TRAVEL_BACK");
        }
        void setupCombat(ServerPlayer player) {
            combatIsolation();
            BlockPos a = combatArena();
            AABB area = new AABB(a.getX() - 90, level.getMinY(), a.getZ() - 90, a.getX() + 106, carrier.getY() + 32, a.getZ() + 106);
            require(level.getEntitiesOfClass(Mob.class, area, m -> m.isAlive() && !(m instanceof DroneEntity)).isEmpty(),
                "existing mobs in combat area; no removal or commandeering of unknown targets");
            int[][] offsets = {{2, 2}, {13, 2}, {13, 13}};
            for (int i = 0; i < 3; i++) {
                Mob target = EntityTypes.HUSK.create(level, EntitySpawnReason.TRIGGERED);
                require(target != null && target.getAttribute(Attributes.MAX_HEALTH) != null, "hostile fixture unavailable");
                target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(2000);
                target.setHealth(i == 0 ? 40 : 2000);
                spawnFixture(target, Vec3.atCenterOf(a.offset(offsets[i][0], LAYERS - 2, offsets[i][1])));
            }
            Mob friend = EntityTypes.VILLAGER.create(level, EntitySpawnReason.TRIGGERED);
            require(friend != null, "friendly fixture unavailable");
            Vec3 target = living(1).getBoundingBox().getCenter();
            spawnFixture(friend, carrier.beamOrigin().lerp(target, .65).add(0, -.9, 0));
            for (BlockPos p : shield()) {
                require(loaded(level, p) && level.getBlockState(p).isAir(), "shield would replace existing terrain");
                data.add("shield_placed", 1);
                require(level.setBlock(p, Blocks.STONE.defaultBlockState(), FLAGS), "shield placement failed");
            }
            combatCycle = 0; health.clear();
            for (UUID id : data.targets) if (level.getEntity(id) instanceof LivingEntity l) health.put(id, l.getHealth());
            refuel(player, true); combatPreview(player); begin("ARM_COMBAT");
        }
        void spawnFixture(Mob mob, Vec3 at) {
            require(data.targets.size() < 4, "at most three hostiles and one friendly fixture");
            data.targets.add(mob.getUUID()); data.setDirty();
            mob.addTag(data.plan().tag()); mob.setPersistenceRequired(); mob.setNoAi(true); mob.setNoGravity(true); mob.setSilent(true);
            mob.setPos(at); require(level.addFreshEntity(mob), "fixture spawn refused");
        }
        LivingEntity living(int index) {
            Entity e = level.getEntity(data.targets.get(index));
            require(e instanceof LivingEntity && e.entityTags().contains(data.plan().tag()), "owned combat fixture unavailable: " + index);
            return (LivingEntity)e;
        }
        float combatHealth(int index) {
            Entity entity = level.getEntity(data.targets.get(index));
            if (entity == null) return 0;
            require(entity instanceof LivingEntity && entity.entityTags().contains(data.plan().tag()),
                "owned combat fixture identity changed: " + index);
            return ((LivingEntity)entity).getHealth();
        }
        List<BlockPos> shield() {
            Vec3 enemy = Vec3.atCenterOf(combatArena().offset(13, LAYERS - 2, 13)).add(0, .8, 0);
            Vec3 origin = Vec3.atCenterOf(combatDestination()).add(0, -.1, 0);
            BlockPos center = BlockPos.containing(origin.lerp(enemy, .62));
            List<BlockPos> result = new ArrayList<>();
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) result.add(center.offset(dx, 0, dz));
            return result;
        }
        void combatPreview(ServerPlayer player) {
            command(player, CarrierCommandPayload.Action.PREVIEW_COMBAT, NONE, combatArena());
            require(ship.preview != null && ship.previewMode == CarrierPolicy.Mode.COMBAT, "normal combat preview refused");
            combatGeneration = ship.preview.generation();
            observedPhase = "";
            combatEvidence = new CarrierAcceptanceCombatEvidence();
        }
        void combat(ServerPlayer player) {
            combatIsolation();
            require(elapsed() < 1800, "bounded combat timeout");
            String phase = carrier.workPhase().name();
            if (!phase.equals(observedPhase)) {
                say(player, "MEASURE COMBAT / cycle=" + combatCycle + " / " + phase + " / tick=" + server.getTickCount()
                    + " / duration=" + carrier.phaseDuration() + " / beams=" + carrier.beamAims().size() + " / weapon=" + ship.weaponEnergy);
                combatEvidence.observePhase(phase);
                observedPhase = phase;
            }
            if (carrier.beamActive() && carrier.workPhase() == CarrierPolicy.WorkPhase.FIRE) {
                var targets = new java.util.ArrayList<Integer>();
                for (Vec3 aim : carrier.beamAims()) {
                    require(!living(3).getBoundingBox().inflate(.25).contains(aim), "beam endpoint targeted friendly fixture");
                    for (int index = 0; index < 3; index++) {
                        Entity target = level.getEntity(data.targets.get(index));
                        if (target instanceof LivingEntity living && living.getBoundingBox().inflate(.25).contains(aim)) targets.add(index);
                    }
                }
                combatEvidence.observeBeamTargets(targets);
                combatEvidence.observeHitTargets(ship.combatBeamTargets.stream()
                    .map(data.targets::indexOf).filter(index -> index >= 0).toList());
            }
            if (ship.mode == CarrierPolicy.Mode.COMBAT) return;
            require(ship.stop == CarrierPolicy.Stop.COMPLETE && combatEvidence.complete(combatCycle == 0 ? new int[] {0} : new int[] {1, 2}),
                "combat did not complete all real phases: " + ship.stop);
            require(living(3).getHealth() == health.get(data.targets.get(3)), "friendly damaged");
            if (combatCycle == 0) {
                Entity first = level.getEntity(data.targets.getFirst());
                require(first == null || first instanceof LivingEntity dead && !dead.isAlive(), "exposed low-health hostile was not defeated");
                data.counts.put("combat_first_dead", 1);
                require(living(1).getHealth() == health.get(data.targets.get(1)), "beam crossed friendly");
                require(living(2).getHealth() == health.get(data.targets.get(2)), "beam damaged shielded hostile");
                for (BlockPos p : shield()) require(level.getBlockState(p).is(Blocks.STONE), "combat changed shield terrain");
                for (BlockPos p : shield()) require(level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS), "owned shield removal failed");
                data.counts.put("shield_placed", 0);
                living(3).setPos(Vec3.atCenterOf(combatArena().offset(2, LAYERS - 2, 13)));
                pass("A04-A05_BLOCKED", "exposed enemy defeated; shielded and friendly-crossing enemies untouched; friendly unharmed");
                combatCycle++; combatPreview(player); next("ARM_COMBAT");
            } else {
                float second = combatHealth(1), third = combatHealth(2);
                require(second < health.get(data.targets.get(1)) && third < health.get(data.targets.get(2)),
                    "unblocked targets not both damaged");
                say(player, "MEASURE DAMAGE / targets=" + (health.get(data.targets.get(1)) - second) + ","
                    + (health.get(data.targets.get(2)) - third) + " / death is accepted only with observed FIRE endpoints");
                pass("A01-A02-A04-A05", "two normal combat cycles; scan/charge/fire/cooldown; broad-area damage evidence; friendly unharmed");
                data.cursor = 0; shellIndex = 0; next("VERIFY_COMBAT");
            }
        }

        void verifyConservation(ServerPlayer player, boolean extracted) {
            Map<String, Integer> actual = totals(copy(ship.cargo));
            subtract(actual, totals(data.initial.orElseThrow().cargo()));
            merge(actual, totals(copy(player.getInventory())));
            subtract(actual, totals(data.initial.orElseThrow().player()));
            if (data.count("barrels") == 10) merge(actual, totals(storage()));
            Map<String, Integer> expected = new HashMap<>();
            data.counts.forEach((key, value) -> { if (key.startsWith("loot:")) expected.put(key.substring(5), value); });
            actual.entrySet().removeIf(e -> e.getValue() == 0);
            require(actual.equals(expected), "actual cargo+player+warehouse != committed vanilla drops: actual=" + actual + " expected=" + expected);
            if (extracted) require(totals(storage()).equals(expected), "warehouse does not contain all committed drops");
        }
        Map<String, Integer> totals(List<ItemStack> stacks) {
            Map<String, Integer> totals = new HashMap<>();
            for (ItemStack stack : stacks) if (!stack.isEmpty() && !isAnyMarker(stack)) totals.merge(itemId(stack), stack.getCount(), Math::addExact);
            return totals;
        }
        int itemCount(List<ItemStack> stacks) {
            int count = 0;
            for (ItemStack stack : stacks) count = Math.addExact(count, stack.getCount());
            return count;
        }
        List<ItemStack> storage() {
            List<ItemStack> stacks = new ArrayList<>();
            for (int i = 0; i < data.count("barrels"); i++) stacks.addAll(copy(barrel(i)));
            return stacks;
        }
        Container barrel(int index) {
            CarrierAnchor at = data.plan().barrels().get(index); ServerLevel world = at.level(server);
            require(world != null && loaded(world, at.pos()) && world.getBlockState(at.pos()).is(Blocks.BARREL)
                && world.getBlockEntity(at.pos()) instanceof Container, "owned warehouse missing/unloaded: " + at.pos());
            Container barrel = (Container)world.getBlockEntity(at.pos());
            require(barrel.getItem(26).getCount() == 1 && isMarker(barrel.getItem(26), "warehouse"), "warehouse marker changed; never overwrite user contents");
            return barrel;
        }
        CarrierMenu carrierMenu(ServerPlayer player) {
            require(player.containerMenu.getCarried().isEmpty(), "nonempty cursor");
            if (!(player.containerMenu instanceof CarrierMenu m) || !m.shipId.equals(data.plan().ship())) CarrierMenu.open(player, data.plan().ship());
            require(player.containerMenu instanceof CarrierMenu && player.containerMenu.stillValid(player), "normal carrier menu unavailable");
            return (CarrierMenu)player.containerMenu;
        }
        boolean page(ServerPlayer player, int requested) {
            CarrierMenu menu = carrierMenu(player);
            if (menu.cargoPage() == requested) return true;
            CarrierCommands.handle(new CarrierCommandPayload(menu.containerId, menu.shipId, CarrierCommandPayload.Action.CARGO_PAGE,
                NONE, requested, menu.getStateId(), menu.cargoPage(), NONE), player);
            return player.containerMenu instanceof CarrierMenu current && current.cargoPage() == requested;
        }
        void command(ServerPlayer player, CarrierCommandPayload.Action action, UUID generation, BlockPos target) {
            CarrierMenu menu = carrierMenu(player);
            CarrierCommands.handle(new CarrierCommandPayload(menu.containerId, menu.shipId, action, generation,
                target.getX(), target.getY(), target.getZ(), NONE), player);
            if (action == CarrierCommandPayload.Action.MOVE && ship.destination != null && ship.destination.pos().equals(target)) ownedDestination = target;
        }
        boolean takeCargo(ServerPlayer player, int slot, boolean removeFiller) {
            if (!page(player, slot / 54)) return false;
            CarrierMenu menu = (CarrierMenu)player.containerMenu;
            ItemStack expected = ship.cargo.getItem(slot).copy();
            require(!expected.isEmpty() && (!removeFiller || isMarker(expected, "filler")), "refuse removing non-fixture cargo");
            int scratch = emptyPlayerSlot(player); require(scratch >= 0, "no empty player scratch slot");
            click(menu, slot % 54, ContainerInput.PICKUP, player);
            click(menu, menuPlayerSlot(menu, player, scratch), ContainerInput.PICKUP, player);
            require(ship.cargo.getItem(slot).isEmpty() && ItemStack.matches(expected, player.getInventory().getItem(scratch)), "normal cargo->player transfer mismatch");
            if (removeFiller) {
                // Only material created by this run, after its ordinary real-player extraction, is disposable.
                player.getInventory().setItem(scratch, ItemStack.EMPTY); data.add("filler_removed", expected.getCount());
                require(same(data.initial.orElseThrow().player(), copy(player.getInventory())), "filler cleanup changed original player inventory");
            }
            return true;
        }
        void refuel(ServerPlayer player, boolean weapon) {
            if (weapon) {
                if (ship.weaponEnergy < 1000 && ship.supplies.getItem(CarrierSupplies.WEAPON).isEmpty()) insertSupply(player, CarrierSupplies.WEAPON, "laser_cell", 1);
            } else if (ship.energy < 2000 && ship.supplies.getItem(CarrierSupplies.FLIGHT).isEmpty()) insertSupply(player, CarrierSupplies.FLIGHT, "power_cell", 16);
        }
        void insertSupply(ServerPlayer player, int kind, String id, int count) {
            if (!ship.supplies.getItem(kind).isEmpty()) return;
            require(data.count("supply:" + id) + count <= (id.equals("laser_cell") ? 256 : 64), "bounded fixture supply budget exceeded");
            int scratch = emptyPlayerSlot(player); require(scratch >= 0, "no empty player supply slot");
            var item = BuiltInRegistries.ITEM.getValue(CarrierModule.id(id)); require(item != Items.AIR, "supply item not registered");
            CarrierMenu menu = carrierMenu(player);
            player.getInventory().setItem(scratch, marker(item, count, "supply:" + id)); data.add("supply:" + id, count);
            click(menu, menuPlayerSlot(menu, player, scratch), ContainerInput.PICKUP, player);
            click(menu, CarrierMenu.SUPPLY_START + kind, ContainerInput.PICKUP, player);
            require(player.getInventory().getItem(scratch).isEmpty() && menu.getCarried().isEmpty()
                && isMarker(ship.supplies.getItem(kind), "supply:" + id), "normal typed supply insertion refused");
        }
        ItemStack marker(net.minecraft.world.item.Item item, int count, String purpose) {
            ItemStack stack = new ItemStack(item, count);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal(data.plan().tag() + "/" + purpose)); return stack;
        }
        boolean isMarker(ItemStack stack, String purpose) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            return !stack.isEmpty() && name != null && name.getString().equals(data.plan().tag() + "/" + purpose);
        }
        boolean isAnyMarker(ItemStack stack) {
            Component name = stack.get(DataComponents.CUSTOM_NAME);
            return name != null && name.getString().startsWith(data.plan().tag() + "/");
        }
        void stopOwnOperation() {
            if (ship.mode == CarrierPolicy.Mode.MINING && ship.progress != null && ship.progress.operation().generation().equals(data.generation)
                || ship.mode == CarrierPolicy.Mode.COMBAT && ship.activeCombat != null && ship.activeCombat.generation().equals(combatGeneration)
                || ownedDestination != null && ship.destination != null && ship.destination.pos().equals(ownedDestination)) ship.stop(CarrierPolicy.Stop.EMERGENCY);
        }
        void pause(String reason) {
            stopOwnOperation(); data.resume = data.stage; data.mark("PAUSED", reason); active = null;
            say(server.getPlayerList().getPlayer(data.plan().owner()), "PAUSED / " + reason + " / fixture and real items retained");
        }
        void fail(String reason) {
            stopOwnOperation(); data.resume = data.stage; data.mark("FAILED", reason); active = null;
            say(server.getPlayerList().getPlayer(data.plan().owner()), "FAIL / " + reason + " / no inventory rollback or entity replacement");
        }
        void pass(String id, String detail) {
            if (!data.passed.contains(id)) data.passed.add(id);
            data.setDirty(); say(server.getPlayerList().getPlayer(data.plan().owner()), "PASS " + id + " / " + detail + " / run=" + data.plan().run());
        }
        void cleanupBlocks(ServerPlayer player) {
            require(ship.mode == CarrierPolicy.Mode.IDLE, "unknown active operation; cleanup refused");
            for (int n = 0; n < WORLD_BUDGET && data.cursor < data.placed; n++, data.cursor++) {
                BlockPos p = pos(data.cursor); require(loaded(level, p), "cleanup waits for loaded fixture chunk");
                BlockState state = level.getBlockState(p);
                if (state.isAir()) continue;
                require(state.equals(expected(p)) && level.getBlockEntity(p) == null
                    && !CarrierSavedData.get(server).playerBuilt(level.dimension().identifier().toString(), p.asLong()),
                    "changed/player-replaced fixture block retained: " + p);
                require(level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS), "fixture cleanup failed");
            }
            if (data.cursor < data.placed) return;
            for (UUID id : data.targets) {
                Entity entity = level.getEntity(id);
                if (entity == null) continue;
                require(entity.entityTags().contains(data.plan().tag()) && (entity.getType() == EntityTypes.HUSK || entity.getType() == EntityTypes.VILLAGER), "changed combat entity retained");
                entity.discard();
            }
            if (data.count("shield_placed") > 0) for (BlockPos p : shield()) {
                require(loaded(level, p) && level.getBlockState(p).is(Blocks.STONE)
                    && !CarrierSavedData.get(server).playerBuilt(level.dimension().identifier().toString(), p.asLong()), "changed/unloaded shield retained");
                level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
            }
            data.counts.put("shield_placed", 0);
            int nonempty = 0;
            for (int i = 0; i < data.count("barrels"); i++) {
                if (data.count("barrel_removed:" + i) > 0) continue;
                Container b = barrel(i); boolean empty = true;
                for (int j = 0; j < 26; j++) empty &= b.getItem(j).isEmpty();
                if (!empty) { nonempty++; continue; }
                CarrierAnchor at = data.plan().barrels().get(i);
                require(at.level(server).setBlock(at.pos(), Blocks.AIR.defaultBlockState(), FLAGS), "empty owned barrel cleanup failed");
                data.counts.put("barrel_removed:" + i, 1);
            }
            if (nonempty > 0) {
                data.mark("CLEANUP_WAIT", "loot barrels retained=" + nonempty + "; empty through ordinary menus; never delete mined loot");
            } else data.mark("CLEANED", "only generated unchanged blocks/filler/hostiles removed; original carrier + four drones and baseline READY retained");
            active = null; say(player, data.message);
        }
    }

    static boolean baselineCargoRetained(List<ItemStack> initial, Container cargo) {
        if (initial.size() > cargo.getContainerSize()) return false;
        for (int slot = 0; slot < initial.size(); slot++) {
            ItemStack original = initial.get(slot), now = cargo.getItem(slot);
            if (!original.isEmpty() && (!ItemStack.isSameItemSameComponents(original, now) || now.getCount() < original.getCount())) return false;
        }
        return true;
    }
    static int extractionCount(ItemStack current, ItemStack original, int capacity) {
        require(capacity > 0 && current.getCount() > original.getCount()
            && (original.isEmpty() || ItemStack.isSameItemSameComponents(original, current)), "no safe cargo extraction");
        int extra = current.getCount() - original.getCount();
        return original.isEmpty() && capacity >= extra ? extra : 1;
    }
    static int warehouseTargets(int usedClicks) {
        if (usedClicks < 0 || usedClicks > CLICK_BUDGET) throw new IllegalArgumentException("invalid used click budget");
        return (CLICK_BUDGET - usedClicks) / 3;
    }

    private static BlockPos fixtureArena(ServerLevel level, CarrierEntity carrier) {
        int carrierChunkX = carrier.blockPosition().getX() >> 4;
        int carrierChunkZ = carrier.blockPosition().getZ() >> 4;
        for (ChunkCandidate candidate : fixtureChunkCandidates(carrierChunkX, carrierChunkZ)) {
            int x = candidate.x() << 4, z = candidate.z() << 4;
            int ground = level.getMinY();
            boolean rimLoaded = true;
            for (int dx = -1; dx <= 16 && rimLoaded; dx++) for (int dz = -1; dz <= 16; dz++) {
                if (!loaded(level, new BlockPos(x + dx, 0, z + dz))) { rimLoaded = false; break; }
                ground = Math.max(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz));
            }
            if (!rimLoaded) continue;
            BlockPos arena = new BlockPos(x, ground + 4, z);
            if (arena.getY() + LAYERS + 24 >= level.getMaxY() || !fixtureAir(level, arena)) continue;
            AABB volume = new AABB(Vec3.atLowerCornerOf(arena), Vec3.atLowerCornerOf(arena.offset(WIDTH, LAYERS + 1, WIDTH)));
            if (level.getEntities((Entity)null, volume, entity -> !entity.isRemoved()).isEmpty()) return arena;
        }
        return null;
    }

    private static boolean fixtureAir(ServerLevel level, BlockPos arena) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = 0; y <= LAYERS; y++) for (int z = 0; z < WIDTH; z++) for (int x = 0; x < WIDTH; x++) {
            cursor.set(arena.getX() + x, arena.getY() + y, arena.getZ() + z);
            if (!level.getBlockState(cursor).isAir()) return false;
        }
        return true;
    }
    record WarehouseMove(int barrel, int slot, int count, ItemStack before) {}
    static List<WarehouseMove> warehousePlan(List<? extends Container> barrels, ItemStack transfer, int targetLimit) {
        if (targetLimit < 0) throw new IllegalArgumentException("negative warehouse target limit");
        List<WarehouseMove> result = new ArrayList<>();
        int left = transfer.getCount();
        // Exhaust every matching partial stack before allocating any empty slot; slot 26 is the ownership marker.
        for (int pass = 0; pass < 2; pass++) {
            for (int index = 0; index < barrels.size(); index++) {
                Container barrel = barrels.get(index);
                for (int slot = 0; slot < 26; slot++) {
                    if (left <= 0 || result.size() >= targetLimit) return List.copyOf(result);
                    ItemStack current = barrel.getItem(slot);
                    if (pass == 0 ? current.isEmpty() || !ItemStack.isSameItemSameComponents(current, transfer) : !current.isEmpty()) continue;
                    int count = Math.min(left, barrel.getMaxStackSize(transfer) - current.getCount());
                    if (count <= 0) continue;
                    result.add(new WarehouseMove(index, slot, count, current.copy()));
                    left -= count;
                }
            }
        }
        return List.copyOf(result);
    }
    private static void click(AbstractContainerMenu menu, int slot, ContainerInput input, ServerPlayer player) {
        require(player.containerMenu == menu && menu.stillValid(player), "current normal menu changed");
        menu.clicked(slot, 0, input, player);
    }
    private static int menuPlayerSlot(AbstractContainerMenu menu, ServerPlayer player, int inventorySlot) {
        for (int slot = 0; slot < menu.slots.size(); slot++)
            if (menu.slots.get(slot).container == player.getInventory() && menu.slots.get(slot).getContainerSlot() == inventorySlot) return slot;
        throw new IllegalStateException("real player menu slot unavailable");
    }
    private static int emptyPlayerSlot(ServerPlayer player) {
        for (int slot = 0; slot < 36; slot++) if (player.getInventory().getItem(slot).isEmpty()) return slot;
        return -1;
    }
    private static List<ItemStack> copy(Container container) {
        List<ItemStack> result = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) result.add(container.getItem(slot).copy());
        return result;
    }
    private static boolean same(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }
    private static boolean sameSnapshot(CarrierAcceptanceData.Snapshot a, CarrierAcceptanceData.Snapshot b) {
        return same(a.cargo(), b.cargo()) && same(a.player(), b.player()) && same(a.supplies(), b.supplies())
            && same(a.storage(), b.storage()) && a.progress().equals(b.progress())
            && (a.pending().isEmpty() && b.pending().isEmpty() || a.pending().isPresent() && b.pending().isPresent()
                && a.pending().get().pos().equals(b.pending().get().pos()) && a.pending().get().state().equals(b.pending().get().state())
                && same(a.pending().get().drops(), b.pending().get().drops()));
    }
    private static boolean loaded(ServerLevel world, BlockPos p) { return world.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4) != null; }
    private static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static void merge(Map<String, Integer> into, Map<String, Integer> values) { values.forEach((k, v) -> into.merge(k, v, Math::addExact)); }
    private static void subtract(Map<String, Integer> into, Map<String, Integer> values) { values.forEach((k, v) -> into.merge(k, -v, Math::addExact)); }
    private static BlockState materialState(int id) {
        return switch (id) {
            case 0 -> Blocks.BEDROCK.defaultBlockState(); case 1 -> Blocks.STONE.defaultBlockState();
            case 2 -> Blocks.DEEPSLATE.defaultBlockState(); case 3 -> Blocks.COAL_ORE.defaultBlockState();
            case 4 -> Blocks.IRON_ORE.defaultBlockState(); case 5 -> Blocks.GOLD_ORE.defaultBlockState();
            case 6 -> Blocks.DIAMOND_ORE.defaultBlockState(); case 7 -> Blocks.EMERALD_ORE.defaultBlockState();
            default -> throw new IllegalArgumentException("fixture material");
        };
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void say(ServerPlayer player, String message) {
        MorrowgearDrone.LOGGER.info("{}{}", PREFIX, message);
        if (player != null) player.sendSystemMessage(Component.literal(PREFIX + message));
    }
}
