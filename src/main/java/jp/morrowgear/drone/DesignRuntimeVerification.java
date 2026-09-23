package jp.morrowgear.drone;

import java.util.*;
import java.util.function.Consumer;
import jp.morrowgear.drone.block.DockBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded observation of existing aircraft. Only the detached floor and one target are created. */
final class DesignRuntimeVerification {
    private static final Logger LOG = LoggerFactory.getLogger("morrowgear-design-verification");
    private static final int COMBAT_TICKS = 800;
    private static final int ROUTE_TICKS = 3600;
    private static final int RETURN_TICKS = 1200;
    private static final int PROJECTILE_SETTLE_TICKS = 220;
    private static final int STATUS_INTERVAL_TICKS = 300;
    private static final int ROUTE_STALL_TICKS = 200;
    private final ServerPlayer player;
    private final ServerLevel level;
    private final List<DroneEntity> fleet;
    private final List<BlockPos> docks;
    private final Consumer<String> say;
    private final Map<UUID, StoredDroneState> saved = new LinkedHashMap<>();
    private final Map<BlockPos, net.minecraft.nbt.CompoundTag> savedDocks = new LinkedHashMap<>();
    private final Map<UUID, MotionSample> motion = new LinkedHashMap<>();
    private final Map<UUID, Vec3> missiles = new LinkedHashMap<>();
    private final Set<UUID> seenMissiles = new HashSet<>();
    private final Map<UUID, Long> impactTicks = new LinkedHashMap<>();
    private final Set<BlockPos> floor = new LinkedHashSet<>();
    private final Set<UUID> landed = new HashSet<>();
    private final EnumSet<CombatState> combatStates = EnumSet.noneOf(CombatState.class);
    private String kind = "idle";
    private String phase = "idle";
    private String result = "NOT RUN";
    private String mission;
    private long started, phaseStarted, wallStarted, lastShot = Long.MIN_VALUE;
    private long lastMissileTick;
    private boolean running, returning, joined, left, rejoined;
    private int shots, damageEvents, chargeTicks, fireTicks, maxCharge, removedMissiles;
    private int consecutiveFireTicks, longestFireRun;
    private CombatState previousCombatState = CombatState.IDLE;
    private float lastHealth, damage;
    private UUID targetUuid;
    private int targetId = -1;
    private BlockPos pad;
    private DroneEntity security;
    private List<BlockPos> route = List.of();
    private BlockPos routeOrigin;

    DesignRuntimeVerification(ServerPlayer player, List<DroneEntity> fleet, List<BlockPos> docks, Consumer<String> say) {
        this.player = player;
        this.level = (ServerLevel)player.level();
        this.fleet = List.copyOf(fleet);
        this.docks = List.copyOf(docks);
        this.say = say;
    }

    boolean running() { return running; }

    boolean start(String requested) {
        kind = requested;
        started = phaseStarted = lastMissileTick = level.getGameTime();
        wallStarted = System.nanoTime();
        mission = "DESIGN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            require(player.isCreative() && !player.isRemoved(), "creative owner required");
            require(fleet.size() == 6 && docks.size() == 6, "six-role fixture required");
            for (int i = 0; i < fleet.size(); i++) {
                DroneEntity d = fleet.get(i);
                require(d.isAlive() && !d.isRemoved() && d.level() == level && d.isOwnedBy(player.getUUID())
                    && d.hasDock() && d.dockPos().equals(docks.get(i)), "fixture changed: " + d.unitId());
                require(d.isDocked() && (d.mode() == DroneMode.STANDBY || d.mode() == DroneMode.DOCK)
                    && !d.hasWaypoint() && !d.hasFieldOperation() && !d.hasSecurityPatrol()
                    && !d.hasCargoSource() && !d.hasCargoTarget() && !d.combatActive()
                    && !d.emergencyInterceptActive() && !d.serviceReturnActive(), "unit busy; dock/clear orders first: " + d.unitId());
                require(d.getHealth() >= d.getMaxHealth() * .95 && d.lowestSubsystemCondition() >= DroneSubsystemPolicy.MAX * .95
                    && d.batteryPercent() >= 25, "service aircraft before verification: " + d.unitId());
                require(level.getBlockEntity(docks.get(i)) instanceof DockBlockEntity dock
                    && dock.isOwnedBy(player.getUUID()), "owned Dock missing");
                require(d.distanceToSqr(player) <= 128 * 128, "stay near the fixture");
            }
            security = fleet.stream().filter(d -> d.role() == DroneRole.SECURITY).findFirst().orElseThrow();
            if (combat()) {
                require(security.unitId().equalsIgnoreCase("MG-DRN-FD8C54"), "combat is restricted to existing Security FD8C54");
                require(level.getDifficulty() != Difficulty.PEACEFUL, "Husk observation requires non-Peaceful difficulty; no difficulty changes made");
                require(level.getEntitiesOfClass(DroneEntity.class, player.getBoundingBox().inflate(512),
                    d -> d != security && d.role() == DroneRole.SECURITY).isEmpty(), "another Security in dispatch range; refuse isolated combat");
                pad = new BlockPos(6000, 120, 6077);
                checkPadAir();
            } else if (kind.equals("route")) {
                route = List.of(new BlockPos(5964, 0, 5977), new BlockPos(6036, 0, 5977),
                    new BlockPos(6036, 0, 6049), new BlockPos(5964, 0, 6049));
                for (BlockPos p : route) require(level.hasChunkAt(p), "route chunks must be loaded");
            }
            for (DroneEntity d : subjects()) {
                saved.put(d.getUUID(), Objects.requireNonNull(StoredDroneState.read(d.createStoredUnit())));
                motion.put(d.getUUID(), new MotionSample(d));
            }
            running = true;
            result = "OBSERVING";
            for (DroneEntity d : subjects()) d.setPowerForVerification(1000, 1000);
            if (kind.equals("service")) {
                for (DroneEntity d : fleet) {
                    DockBlockEntity dock = (DockBlockEntity)level.getBlockEntity(d.dockPos());
                    savedDocks.put(d.dockPos(), dock.saveWithoutMetadata(level.registryAccess()));
                    dock.clearContent(); dock.setStoredPowerForVerification(0);
                    dock.setSupplyCreditsForVerification(0, 0, 0);
                    d.setPowerForVerification(0, 0);
                }
                security.upgradePayloadCapacity();
                security.setCombatResourcesForVerification(0, 0, 0);
                phase = "emptyDock";
                say.accept("START service / 6 existing docked aircraft / zero power + empty Dock / supplies after 20 ticks");
            } else if (combat()) {
                buildTarget();
                security.setCombatResourcesForVerification(CombatPolicy.GUN_CAPACITY, CombatPolicy.MISSILE_CAPACITY, 0);
                security.assignSecurityLoadout(kind.equals("laser") ? SecurityLoadout.LASER : SecurityLoadout.MISSILE);
                security.assignSecurityPatrol(pad.above(), 16, mission);
                phase = "combat";
                say.accept("START " + kind + " / FD8C54 / 800 ticks / detailed evidence in log");
                LOG.info("DESIGN combat target={} pad={} / public impact flag observed; fuse counter/cause unexposed; removal is not proof of explosion",
                    targetUuid, pad);
            } else {
                phase = kind.equals("route") ? "ascent" : "takeoff";
                for (int i = 0; i < fleet.size(); i++) {
                    DroneEntity d = fleet.get(i);
                    if (kind.equals("route")) {
                        d.assignGroup(mission);
                        // Waypoints retain altitude; native patrol points intentionally follow the heightmap.
                        d.assignWaypoint(docks.get(i).above(18), mission + "-UP-" + i, 1, 0, docks.get(i), started);
                    } else d.setMode(DroneMode.FOLLOW);
                }
                say.accept("START " + kind + " / existing 6 aircraft / native flight only / no teleport / max run ticks="
                    + (kind.equals("route") ? ROUTE_TICKS : 160) + " + return <=1200");
            }
            return true;
        } catch (RuntimeException error) {
            stop("FAIL start: " + error.getMessage());
            return false;
        }
    }

    private boolean combat() { return kind.equals("laser") || kind.equals("missile"); }
    private List<DroneEntity> subjects() { return combat() ? List.of(security) : fleet; }

    void tick() {
        if (!running) return;
        try {
            if (!returning) require(!player.isRemoved() && player.isCreative() && player.level() == level,
                "owner disconnected, changed dimension or left creative");
            double observationRadius = kind.equals("route") ? 220 : 128;
            if (!returning) require(player.distanceToSqr(Vec3.atCenterOf(docks.get(1))) <= observationRadius * observationRadius,
                "owner left fixture observation area");
            for (DroneEntity d : subjects()) {
                require(!d.isRemoved() && d.isAlive() && d.level() == level, "aircraft unavailable: " + d.unitId());
                require(d.hasDock() && d.dockPos().equals(docks.get(fleet.indexOf(d))), "Dock binding changed: " + d.unitId());
                motion.get(d.getUUID()).sample(d);
                if (!returning) require(d.distanceToSqr(Vec3.atCenterOf(docks.get(0))) < 220 * 220, "aircraft left observation boundary");
            }
            if (combat()) sampleCombat();
            if (returning) { tickReturn(); return; }
            long elapsed = level.getGameTime() - started;
            if (combat()) {
                require(level.getEntity(targetUuid) instanceof LivingEntity target && target.isAlive(), "created target lost/died");
                require(level.getEntity(targetUuid).position().distanceTo(Vec3.atCenterOf(pad.above())) <= 5,
                    "target left protected pad");
                require(security.combatTargetId() < 0 || security.combatTargetId() == targetId, "unexpected combat target; aborting");
                require(level.getEntitiesOfClass(LivingEntity.class, padBounds(), e -> e.getId() != targetId
                    && e != security && e != player).isEmpty(), "unrelated living entity entered combat area");
                if (elapsed >= COMBAT_TICKS) stop(combatEvidence() ? "OBSERVED requested real combat evidence"
                    : "INCOMPLETE combat evidence within 800 ticks");
            } else if (kind.equals("service")) {
                tickService(elapsed);
            } else if (kind.equals("flight")) {
                if (elapsed >= 160) {
                    require(fleet.stream().allMatch(d -> !d.isDocked() && d.getY() > d.dockPos().getY() + 1), "not all six took off");
                    stop("OBSERVED 6/6 actual takeoff");
                }
            } else tickRoute(elapsed);
            if (elapsed % STATUS_INTERVAL_TICKS == 0) status();
        } catch (RuntimeException error) {
            if (returning) {
                result = "FAIL during return: " + error.getMessage();
                if (level.getGameTime() - phaseStarted >= RETURN_TICKS) finish(false);
                else if ((level.getGameTime() - phaseStarted) % STATUS_INTERVAL_TICKS == 0) status();
            } else stop("FAIL " + error.getMessage());
        }
    }

    private void tickService(long elapsed) {
        require(fleet.stream().allMatch(this::atDock), "unpowered contact aircraft fell or launched");
        if (elapsed == 20) {
            require(fleet.stream().allMatch(d -> d.batteryPercent() == 0 && d.weaponPowerPercent() == 0), "free energy at empty Dock");
            for (DroneEntity d : fleet) {
                DockBlockEntity dock = (DockBlockEntity)level.getBlockEntity(d.dockPos());
                dock.setItem(18, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL, 16));
                dock.setItem(19, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_NUGGET, 32));
                dock.setItem(20, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FIREWORK_ROCKET, 16));
            }
            phase = "legacyRejection";
            say.accept("PASS empty Dock holds contact without free resources; checking flight-only fuel and legacy ammunition rejection");
        }
        if (elapsed == 40) {
            require(fleet.stream().allMatch(d -> d.batteryPercent() > 0 && d.weaponPowerPercent() == 0),
                "ordinary fuel incorrectly supplied weapon energy");
            require(security.gunAmmo() == 0 && security.missiles() == 0, "legacy items incorrectly rearmed weapons");
            for (DroneEntity d : fleet) {
                DockBlockEntity dock = (DockBlockEntity)level.getBlockEntity(d.dockPos());
                require(dock.getItem(19).getCount() == 32 && dock.getItem(20).getCount() == 16, "legacy supplies were consumed");
                dock.setItem(19, new net.minecraft.world.item.ItemStack(SupplyItems.AUTOCANNON_MAGAZINE, 8));
                dock.setItem(20, new net.minecraft.world.item.ItemStack(SupplyItems.MICRO_MISSILE_PACK, 16));
                dock.setItem(21, new net.minecraft.world.item.ItemStack(SupplyItems.LASER_CELL, 8));
            }
            phase = "charging";
            say.accept("PASS legacy supplies unchanged; dedicated magazines, missile packs and laser cells inserted");
        }
        if (elapsed == 80) {
            require(fleet.stream().allMatch(d -> d.batteryPercent() > 0 && d.weaponPowerPercent() > 0), "zero-power recharge failed");
            require(security.gunAmmo() > 0 && security.missiles() > 0, "physical ammo did not replenish");
            require(StoredDroneState.read(security.createStoredUnit()).capacityTier() == security.capacityTier()
                && StoredDroneState.read(security.createStoredChassis()).capacityTier() == security.capacityTier(), "capacity lost during item storage");
            say.accept("PASS all 6 zero-power aircraft resumed contact charging; gun + missile restocking; capacity survives both storage paths");
        }
        if (elapsed > 80 && fleet.stream().allMatch(d -> d.batteryPercent() == 100 && d.weaponPowerPercent() == 100)
            && security.gunAmmo() == security.gunCapacity() && security.missiles() == security.missileCapacity()) {
            say.accept("PASS actual full capacity FLT/WPN 100%; GUN=" + security.gunAmmo() + " MSL=" + security.missiles());
            stop("PASS native Dock service from empty to full capacity");
        } else require(elapsed < 3300, "service timeout: FLT=" + security.batteryPercent() + " WPN="
            + security.weaponPowerPercent() + " GUN=" + security.gunAmmo() + " MSL=" + security.missiles());
    }

    private void tickRoute(long elapsed) {
        if (phase.equals("ascent")) {
            if (fleet.stream().allMatch(d -> d.getY() >= d.dockPos().getY() + 15)) {
                Vec3 centroid = Vec3.ZERO;
                for (DroneEntity d : fleet) centroid = centroid.add(d.position());
                routeOrigin = BlockPos.containing(centroid.scale(1.0 / fleet.size()));
                for (int i = 0; i < 4; i++) assignRoute(i, 0);
                // Two aircraft wait airborne under their existing production waypoint orders.
                phase = "route";
                phaseStarted = level.getGameTime();
                say.accept("MILESTONE ascent 6/6; native curved, terrain-following four-point loop started with 4 aircraft");
                route.forEach(p -> LOG.info("DESIGN route point={} surfaceY={}", p,
                    level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ())));
            } else require(elapsed < 600, "ascent timed out");
        } else {
            long routeTime = level.getGameTime() - phaseStarted;
            List<DroneEntity> onRoute = fleet.stream().filter(DroneEntity::hasPatrolRoute).toList();
            if (!onRoute.isEmpty() && onRoute.stream().allMatch(d -> motion.get(d.getUUID()).stationaryTicks >= ROUTE_STALL_TICKS)) {
                for (DroneEntity d : onRoute) LOG.info("DESIGN route stalled unit={} waypoint={} routeIndex={} stage={} cohort={} leader={} speed={} pos={}",
                    d.unitId(), d.waypointPos(), d.patrolRouteIndex(), d.missionStage(), d.cohortId(), d.cohortLeaderId(), d.getDeltaMovement().length(), d.position());
                stop("FAIL native route stationary for 200 ticks; diagnostic in log");
                return;
            }
            int index = fleet.getFirst().patrolRouteIndex();
            if (!joined && routeTime >= 160) {
                assignRoute(4, index); assignRoute(5, index); joined = true;
                say.accept("MILESTONE join orders issued for remaining 2 aircraft; convergence is measured, not assumed");
            }
            MotionSample leader = motion.get(fleet.getFirst().getUUID());
            DroneEntity tail = fleet.get(5);
            if (joined && !left && leader.routeAdvances >= 4) {
                tail.setMode(DroneMode.DOCK); left = true;
                say.accept("MILESTONE native route handoffs>=4; SALVAGE leave/return order issued");
            }
            if (left && !rejoined && atDock(tail)) {
                assignRoute(5, index); rejoined = true;
                say.accept("MILESTONE SALVAGE actual center landing observed; native rejoin order issued");
            }
            if (rejoined && motion.values().stream().allMatch(s -> s.routeAdvances >= 8 && s.ascents > 0 && s.descents > 0))
                stop("OBSERVED >=8 native route handoffs per aircraft, ascent/descent, leave/landing/rejoin orders");
        }
        if (!returning && elapsed >= ROUTE_TICKS) stop("INCOMPLETE route within 3600 ticks; see measured handoffs and motion");
    }

    private void assignRoute(int i, int routeIndex) {
        DroneEntity d = fleet.get(i);
        motion.get(d.getUUID()).routeIndex = -1;
        BlockPos origin = routeOrigin;
        if (motion.get(fleet.getFirst().getUUID()).routeAdvances > 0) {
            BlockPos previous = route.get(Math.floorMod(routeIndex - 1, route.size()));
            origin = new BlockPos(previous.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                previous.getX(), previous.getZ()) + 6, previous.getZ());
        }
        // A shared incoming leg matches ordinary Wing commands, including delayed joins.
        d.assignPatrolRoute(route, mission, 6, i, origin, level.getGameTime(), routeIndex);
        LOG.info("DESIGN route assignment unit={} origin={} index={} waypoint={}", d.unitId(), origin, routeIndex, d.waypointPos());
    }

    private void checkPadAir() {
        // This entire blast margin, including below the future floor, must be untouched air.
        for (BlockPos dock : docks) require(Math.hypot(pad.getX() - dock.getX(), pad.getZ() - dock.getZ()) - 24 >= 35,
            "detached blast margin must be >=35 blocks from every Dock");
        for (BlockPos p : BlockPos.betweenClosed(pad.offset(-24, -12, -24), pad.offset(24, 32, 24))) {
            require(level.hasChunkAt(p), "pad region not loaded: " + p);
            require(level.getWorldBorder().isWithinBounds(p), "pad outside world border");
            require(level.getBlockState(p).isAir() && level.getBlockEntity(p) == null, "pad needs completely empty air: " + p);
        }
        require(level.getEntitiesOfClass(LivingEntity.class, padBounds(), e -> e != player).isEmpty(), "pad region occupied");
        require(level.getEntitiesOfClass(MorrowgearMissileEntity.class, padBounds(), e -> true).isEmpty(), "pad region has projectiles");
    }

    private AABB padBounds() {
        return new AABB(pad.getX() - 24, pad.getY() - 12, pad.getZ() - 24,
            pad.getX() + 25, pad.getY() + 33, pad.getZ() + 25);
    }

    private void buildTarget() {
        for (BlockPos p : BlockPos.betweenClosed(pad.offset(-8, 0, -8), pad.offset(8, 0, 8))) {
            BlockPos immutable = p.immutable();
            require(level.getBlockState(immutable).isAir(), "pad changed before build");
            floor.add(immutable);
            require(level.setBlockAndUpdate(immutable, Blocks.OBSIDIAN.defaultBlockState()), "pad block placement failed");
        }
        Identifier huskId = Identifier.fromNamespaceAndPath("minecraft", "husk");
        var huskType = BuiltInRegistries.ENTITY_TYPE.getValue(huskId);
        require(huskType != null && huskId.equals(BuiltInRegistries.ENTITY_TYPE.getKey(huskType)), "Husk type unavailable");
        var created = huskType.create(level, EntitySpawnReason.TRIGGERED);
        require(created instanceof Mob, "Husk creation failed");
        Mob target = (Mob)created;
        targetUuid = target.getUUID(); targetId = target.getId();
        target.setNoAi(true);
        target.setNoGravity(true);
        target.setPersistenceRequired();
        Objects.requireNonNull(target.getAttribute(Attributes.MAX_HEALTH)).setBaseValue(1024);
        Objects.requireNonNull(target.getAttribute(Attributes.KNOCKBACK_RESISTANCE)).setBaseValue(1);
        target.setHealth(target.getMaxHealth());
        lastHealth = target.getHealth();
        target.setPos(pad.getX() + .5, pad.getY() + 1, pad.getZ() + .5);
        require(level.addFreshEntity(target), "Husk spawn rejected");
        LOG.info("DESIGN isolated obsidian floor 17x17 / Husk HP={} NoAI NoGravity / no existing blocks overwritten", lastHealth);
    }

    private void sampleCombat() {
        long now = level.getGameTime();
        if (!returning) {
            CombatState state = security.combatState();
            if (state != previousCombatState) {
                LOG.info("DESIGN combat transition={} -> {} tick={} target={} FLT={} WPN={} heat={} link={} diagnostic={}",
                    previousCombatState, state, now, security.combatTargetId(), security.batteryPercent(),
                    security.weaponPowerPercent(), security.laserHeat(), security.dataLinkStatus(),
                    security.laserDiagnosticForVerification(level));
                previousCombatState = state;
            }
            if (state == CombatState.REJOIN && level.getEntity(targetUuid) instanceof LivingEntity target
                && target.isAlive() && !security.serviceReturnActive()
                && security.weaponPowerPercent() >= CombatPolicy.AUTO_WEAPON_RESERVE
                && CombatPolicy.hasUsableWeapon(security.securityLoadout(), security.gunAmmo(),
                    security.missiles(), security.laserHeat())) {
                require(!security.dataLinkStatus().startsWith("THEATER RELEASED")
                    && !security.dataLinkStatus().startsWith("ELEMENT RELEASED"),
                    "live isolated combat unexpectedly released: " + security.dataLinkStatus());
            }
            if (combatStates.add(state)) say.accept("MILESTONE actual combat state=" + state + " t=" + (now - started));
            if (state == CombatState.LASER_CHARGE) chargeTicks++;
            if (state == CombatState.LASER_FIRE) fireTicks++;
            consecutiveFireTicks = state == CombatState.LASER_FIRE ? consecutiveFireTicks + 1 : 0;
            longestFireRun = Math.max(longestFireRun, consecutiveFireTicks);
            maxCharge = Math.max(maxCharge, security.combatCharge());
            if (security.combatShotAge() == 0 && lastShot != now) {
                lastShot = now; shots++;
                LOG.info("DESIGN real shot={} tick={} state={} target={} ammo={} missiles={} charge={}",
                    shots, now, state, security.combatTargetId(), security.gunAmmo(), security.missiles(), security.combatCharge());
                if (shots == 1) say.accept("MILESTONE first real shot / " + security.combatWeapon());
            }
        }
        if (targetUuid != null && level.getEntity(targetUuid) instanceof LivingEntity target) {
            float loss = Math.max(0, lastHealth - target.getHealth());
            if (loss > 0) { damageEvents++; damage += loss; LOG.info("DESIGN target damage={} hp={} tick={}", loss, target.getHealth(), now); }
            lastHealth = target.getHealth();
        }
        Map<UUID, Vec3> present = new LinkedHashMap<>();
        for (MorrowgearMissileEntity missile : level.getEntitiesOfClass(MorrowgearMissileEntity.class,
            padBounds().inflate(220), m -> m.targetId() == targetId || seenMissiles.contains(m.getUUID()))) {
            present.put(missile.getUUID(), missile.position());
            lastMissileTick = now;
            if (seenMissiles.add(missile.getUUID())) LOG.info("DESIGN real missile entity={}", missile.getUUID());
            if (missile.impacted() && !impactTicks.containsKey(missile.getUUID())) {
                impactTicks.put(missile.getUUID(), now);
                LOG.info("DESIGN actual missile impact={} tick={} pos={}", missile.getUUID(), now, missile.position());
                if (impactTicks.size() == 1) say.accept("MILESTONE first real missile impact");
            }
            if (now % 10 == 0) LOG.info("DESIGN missile={} pos={} velocity={} target={} age={} (fuse counter unexposed)",
                missile.getUUID(), missile.position(), missile.getDeltaMovement(), missile.targetId(), missile.tickCount);
        }
        for (var entry : missiles.entrySet()) if (!present.containsKey(entry.getKey())) {
            removedMissiles++;
            Long impactTick = impactTicks.get(entry.getKey());
            LOG.info("DESIGN missile disappeared={} lastPos={} tick={} impactToRemovalTicks={} cause=UNOBSERVABLE (not asserted explosion/fuse)",
                entry.getKey(), entry.getValue(), now, impactTick == null ? -1 : now - impactTick);
        }
        missiles.clear(); missiles.putAll(present);
    }

    private boolean combatEvidence() {
        if (kind.equals("laser")) return chargeTicks > 0 && maxCharge == 1000 && longestFireRun >= 40 && shots > 0 && damageEvents > 0;
        return combatStates.contains(CombatState.MISSILE_APPROACH) && shots > 0 && !seenMissiles.isEmpty() && !impactTicks.isEmpty()
            && removedMissiles > 0 && damageEvents > 0;
    }

    void stop(String reason) {
        if (returning) { say.accept("Return already requested; original Dock orders retained"); return; }
        result = reason;
        returning = true;
        phase = "return";
        phaseStarted = level.getGameTime();
        for (var entry : savedDocks.entrySet())
            if (level.getBlockEntity(entry.getKey()) instanceof DockBlockEntity dock)
                dock.restoreServiceSnapshotForVerification(entry.getValue(), level.registryAccess());
        savedDocks.clear();
        // Never kill by type, radius or entity id. Only this run's explicitly created UUID is removed.
        if (targetUuid != null) {
            var target = level.getEntity(targetUuid);
            if (target != null) target.discard();
        }
        for (DroneEntity d : fleet) {
            StoredDroneState snapshot = saved.get(d.getUUID());
            if (snapshot == null || d.isRemoved()) continue;
            try {
                d.clearSecurityPatrol();
                // Teardown only: cancel retained combat/service tasks, never synthesize an observed state.
                d.resetCombatForVerification();
                d.restoreStoredUnit(snapshot);
                d.setMode(DroneMode.DOCK);
            } catch (RuntimeException error) {
                result = "FAIL restoring " + d.unitId() + ": " + error.getMessage();
                d.setMode(DroneMode.DOCK);
            }
        }
        running = !saved.isEmpty() || !floor.isEmpty();
        say.accept(result + " / returning to original Docks");
        LOG.info("DESIGN stop={} / target UUID cleanup / saved loadout, raw resources and group restored / native return ordered"
            + " (return flight consumes normal power; no forced landing)", result);
        logStatus();
    }

    private void tickReturn() {
        for (DroneEntity d : subjects()) if (atDock(d) && landed.add(d.getUUID()))
            LOG.info("DESIGN actual return/contact {} Dock={}", d.unitId(), d.dockPos());
        boolean settled = !combat() || (missiles.isEmpty() && level.getGameTime() - Math.max(phaseStarted, lastMissileTick) >= PROJECTILE_SETTLE_TICKS);
        if (landed.size() == saved.size() && subjects().stream().allMatch(this::atDock) && settled) finish(true);
        else if (level.getGameTime() - phaseStarted >= RETURN_TICKS) finish(false);
    }

    private boolean atDock(DroneEntity d) {
        BlockPos dock = docks.get(fleet.indexOf(d));
        return d.isDocked() && d.hasDock() && d.dockPos().equals(dock)
            && Math.abs(d.getX() - dock.getX() - .5) < .1 && Math.abs(d.getZ() - dock.getZ() - .5) < .1
            && Math.abs(d.getY() - dock.getY() - .316) < .035
            && level.noCollision(d, d.getBoundingBox().deflate(.002));
    }

    private void finish(boolean returned) {
        running = false;
        if (!returned) result += " / FAIL return timeout/unavailable aircraft (Dock order retained)";
        boolean settled = missiles.isEmpty() && level.getGameTime() - Math.max(phaseStarted, lastMissileTick) >= PROJECTILE_SETTLE_TICKS;
        if (settled) {
            for (BlockPos p : floor) if (level.hasChunkAt(p) && level.getBlockState(p).is(Blocks.OBSIDIAN)
                && level.getBlockEntity(p) == null) level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
        } else if (!floor.isEmpty()) say.accept("WARNING created obsidian floor retained while projectiles may remain at " + pad);
        say.accept("END " + kind + " / Dock contacts=" + landed.size() + "/" + saved.size() + " / " + result);
        LOG.info("DESIGN visual smoothness, sound, explosion cause and fuse need separate observation; no blanket PASS");
        logStatus();
    }

    void status() {
        String evidence = combat() ? "shots=" + shots + " impacts=" + impactTicks.size()
            : "handoffs=" + motion.values().stream().mapToInt(s -> s.routeAdvances).min().orElse(0)
                + " dock=" + landed.size() + "/" + saved.size();
        say.accept("STATUS " + kind + " / " + phase + " / t=" + (level.getGameTime() - started) + " / " + evidence
            + (running ? "" : " / stopped"));
        logStatus();
    }

    private void logStatus() {
        double wallSeconds = (System.nanoTime() - wallStarted) / 1_000_000_000.0;
        LOG.info(String.format(Locale.ROOT, "STATUS %s phase=%s running=%s ticks=%d wall=%.1fs result=%s",
            kind, phase, running, level.getGameTime() - started, wallSeconds, result));
        if (combat()) LOG.info("EVIDENCE chargeTicks=" + chargeTicks + " maxCharge=" + maxCharge + " fireTicks=" + fireTicks
            + " longestFireRun=" + longestFireRun
            + " shots=" + shots + " missileEntities=" + seenMissiles.size() + " disappeared=" + removedMissiles
            + " impacts=" + impactTicks.size()
            + " damageEvents=" + damageEvents + " damage=" + damage + " targetHP=" + lastHealth);
        for (DroneEntity d : fleet) {
            MotionSample s = motion.get(d.getUUID());
            if (s != null) LOG.info("DESIGN {} {}", d.unitId(), s.summary());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class MotionSample {
        private Vec3 position, velocity, acceleration = Vec3.ZERO;
        private long tick;
        private int samples, gaps, ascents, descents, routeAdvances, stationaryTicks, routeIndex = -1;
        private double maxVelocity, maxStep, maxDeltaV, maxJerk, minY, maxY;

        MotionSample(DroneEntity d) {
            position = d.position(); velocity = d.getDeltaMovement(); tick = d.level().getGameTime();
            minY = maxY = d.getY();
        }

        void sample(DroneEntity d) {
            long now = d.level().getGameTime();
            Vec3 v = d.getDeltaMovement();
            require(Double.isFinite(d.getX()) && Double.isFinite(d.getY()) && Double.isFinite(d.getZ())
                && Double.isFinite(v.lengthSqr()), "nonfinite flight sample " + d.unitId());
            if (now - tick == 1) {
                Vec3 delta = v.subtract(velocity);
                maxVelocity = Math.max(maxVelocity, v.length());
                maxStep = Math.max(maxStep, d.position().distanceTo(position));
                stationaryTicks = v.lengthSqr() < .0004 && d.position().distanceToSqr(position) < .0004
                    ? stationaryTicks + 1 : 0;
                maxDeltaV = Math.max(maxDeltaV, delta.length());
                maxJerk = Math.max(maxJerk, delta.subtract(acceleration).length());
                if (d.getY() - position.y > .005) ascents++;
                if (d.getY() - position.y < -.005) descents++;
                acceleration = delta; samples++;
            } else { gaps++; stationaryTicks = 0; }
            minY = Math.min(minY, d.getY()); maxY = Math.max(maxY, d.getY());
            if (d.hasPatrolRoute()) {
                int next = d.patrolRouteIndex();
                if (routeIndex >= 0 && next != routeIndex) {
                    if (next == (routeIndex + 1) % d.patrolRouteSize()) routeAdvances++;
                    LOG.info("DESIGN route handoff unit={} from={} to={} pos={} stage={} cohort={}",
                        d.unitId(), routeIndex, next, d.position(), d.missionStage(), d.cohortId());
                }
                routeIndex = next;
            }
            if (now % 20 == 0) LOG.info("DESIGN sample unit={} tick={} pos={} velocity={} mode={} stage={} docked={} waypoint={} routeIndex={} leader={} {}",
                d.unitId(), now, d.position(), v, d.mode(), d.missionStage(), d.isDocked(), d.waypointPos(), d.patrolRouteIndex(), d.cohortLeaderId(), summary());
            position = d.position(); velocity = v; tick = now;
        }

        String summary() {
            return String.format(Locale.ROOT, "samples=%d gaps=%d maxV=%.4f maxStep=%.4f maxDeltaV=%.4f maxJerk=%.4f y=%.2f..%.2f up/down=%d/%d handoffs=%d",
                samples, gaps, maxVelocity, maxStep, maxDeltaV, maxJerk, minY, maxY, ascents, descents, routeAdvances);
        }
    }
}
