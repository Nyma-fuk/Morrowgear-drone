package jp.morrowgear.drone.carrier;

import java.util.List;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class CarrierWork {
    static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final TagKey<EntityType<?>> FRIENDLY = TagKey.create(Registries.ENTITY_TYPE, CarrierModule.id("compat_friendly"));
    private CarrierWork() {}

    static void tick(CarrierEntity carrier, CarrierShip ship, ServerLevel level, ServerPlayer owner) {
        CarrierPolicy.Mode mode = ship.mode;
        CarrierPolicy.Operation op = ship.mode == CarrierPolicy.Mode.COMBAT ? ship.activeCombat
            : ship.progress == null ? null : ship.progress.operation();
        if (op == null) { ship.stop(CarrierPolicy.Stop.EMERGENCY); return; }
        if (!CarrierPolicy.aligned(op, level.dimension().identifier().toString(), carrier.getX(), carrier.getY(), carrier.getZ())) {
            ship.stop(CarrierPolicy.Stop.OBSTRUCTED); return;
        }
        if (level.getChunkSource().getChunkNow(op.chunkX(), op.chunkZ()) == null) {
            ship.stop(CarrierPolicy.Stop.UNLOADED); return;
        }
        if (mode == CarrierPolicy.Mode.COMBAT) { tickCombat(carrier, ship, level, owner, op); return; }
        AABB area = new AABB(op.chunkX() * 16, op.minY(), op.chunkZ() * 16,
            op.chunkX() * 16 + 16, carrier.getY(), op.chunkZ() * 16 + 16);
        List<LivingEntity> entities = new java.util.ArrayList<>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class), area,
            LivingEntity::isAlive, entities, 129);
        if (entities.size() > 128 || entities.stream().anyMatch(e -> !hostile(e, owner))) {
            ship.stop(CarrierPolicy.Stop.FRIENDLY_IN_AREA); return;
        }
        if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
        if (!op.generation().equals(ship.miningScanGeneration)) {
            ship.miningScanGeneration = op.generation();
            ship.miningScanTicks = 0;
        }
        // The renderer projects this centre endpoint into the selected 16x16 footprint,
        // with each ray starting directly above its own column, never at the aperture.
        if (ship.workPhase() != CarrierPolicy.WorkPhase.FIRE) {
            var samples = new java.util.ArrayList<Vec3>();
            for (int column : CarrierPolicy.miningScanColumns(ship.miningScanTicks)) {
                int x = op.x(column), z = op.z(column);
                double y = Math.max(op.minY(), Math.min(op.maxY() + 1,
                    level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)));
                Vec3 aim = new Vec3(x + .5, y, z + .5);
                var hit = level.clip(new ClipContext(carrier.beamOrigin(), aim, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, carrier));
                samples.add(hit.getType() == HitResult.Type.MISS ? aim : hit.getLocation());
            }
            carrier.recordBeams(samples);
        } else {
            carrier.recordBeams(List.of(new Vec3(op.chunkX() * 16 + 8,
                op.y(Math.min(ship.progress.cursor(), op.volume() - 1)) + 1, op.chunkZ() * 16 + 8)));
        }
        if (ship.miningScanTicks < CarrierPolicy.SCAN_TICKS) {
            ship.miningScanTicks++;
            return;
        }
        if (!ship.miningStartupCharged) {
            if (!workPower(carrier, ship, CarrierPowerPolicy.MINING_STARTUP_ENERGY)) return;
            CarrierPowerPolicy.consumeWork(ship, CarrierPowerPolicy.MINING_STARTUP_ENERGY);
            ship.miningStartupCharged = true;
            ship.dirty.run();
        }
        if (ship.miningScanTicks < CarrierPolicy.SCAN_TICKS + CarrierPolicy.MINING_CHARGE_TICKS) {
            ship.miningScanTicks++;
            return;
        }
        if (carrier.tickCount % 10 == 0) {
            int hits = 0;
            for (LivingEntity target : entities) {
                if (!continues(ship, op, mode)) return;
                if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
                if (!target.isAlive()) continue;
                if (!hostile(target, owner)) { ship.stop(CarrierPolicy.Stop.FRIENDLY_IN_AREA); return; }
                if (hits >= 8 || !CarrierPowerPolicy.workAvailable(ship, 10)) break;
                Vec3 aim = target.getEyePosition();
                Vec3 from = new Vec3(aim.x, carrier.beamOrigin().y, aim.z);
                if (level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, carrier)).getType() != HitResult.Type.MISS) continue;
                if (target.hurtServer(level, level.damageSources().playerAttack(owner), 8)) {
                    hits++;
                    CarrierPowerPolicy.consumeWork(ship, 10);
                }
            }
        }
        if (!continues(ship, op, mode)) return;
        long deadline = System.nanoTime() + CarrierPolicy.WORK_NANOS;
        int broken = 0;
        for (int scanned = 0; scanned < CarrierPolicy.SCAN_PER_TICK && broken < CarrierPolicy.BREAK_PER_TICK
            && System.nanoTime() < deadline; scanned++) {
            if (!continues(ship, op, CarrierPolicy.Mode.MINING)) return;
            if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
            var progress = ship.progress;
            if (progress.complete()) { ship.stop(CarrierPolicy.Stop.COMPLETE); return; }
            BlockPos pos = new BlockPos(op.x(progress.cursor()), op.y(progress.cursor()), op.z(progress.cursor()));
            var state = level.getBlockState(pos);
            if (progress.sealed() || state.isAir()) { ship.progress = progress.advance(); ship.dirty.run(); continue; }
            if (state.is(Blocks.BEDROCK)) { ship.progress = progress.seal(); ship.dirty.run(); continue; }
            Vec3 from = miningOrigin(progress, carrier.beamOrigin().y);
            var hit = level.clip(new ClipContext(from, Vec3.atCenterOf(pos), ClipContext.Block.COLLIDER,
                ClipContext.Fluid.ANY, carrier));
            pos = miningTarget(progress, hit);
            if (pos == null) {
                ship.stop(CarrierPolicy.Stop.OBSTRUCTED); return;
            }
            state = level.getBlockState(pos);
            boolean drainingFluid = CarrierProtection.drainableFluid(state);
            boolean permitted = CarrierProtection.permitted(owner, level, pos, state);
            if (!miningSnapshot(ship, progress)) return;
            if (!permitted) { ship.stop(CarrierPolicy.Stop.PROTECTED); return; }
            if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
            if (!sameUnclaimedBlock(owner, level, pos, state)) { ship.stop(CarrierPolicy.Stop.PROTECTED); return; }
            ItemStack tool = new ItemStack(Items.NETHERITE_PICKAXE);
            if (ship.pending == null || !ship.pending.pos().equals(pos) || !ship.pending.state().equals(state)) {
                List<ItemStack> drops = Block.getDrops(state, level, pos, null, owner, tool);
                if (!miningSnapshot(ship, progress)) return;
                if (drops.size() > 256) { ship.stop(CarrierPolicy.Stop.PROTECTED); return; }
                ship.pending = new CarrierShip.Pending(pos, state, drops);
                ship.dirty.run();
            }
            var pending = ship.pending;
            var plan = ship.cargo.plan(pending.drops());
            if (plan.isEmpty()) { ship.stop(CarrierPolicy.Stop.FULL); return; }
            boolean sustained = CarrierPowerPolicy.sustainedMiningSlot(broken);
            if (!sustained && !CarrierPowerPolicy.workAvailable(ship, CarrierPowerPolicy.MINING_BURST_ENERGY_PER_BLOCK)) break;
            if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
            if (!sameUnclaimedBlock(owner, level, pos, state)) { ship.stop(CarrierPolicy.Stop.PROTECTED); return; }
            if (!ship.cargo.canCommit(plan.get())) { ship.stop(CarrierPolicy.Stop.FULL); return; }
            // One server-thread transaction, not a disk-atomic transaction across chunk and saved data.
            // Suppress neighbour/shape updates so plants, leaves and falling terrain cannot break outside this transaction.
            if (!level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS)) {
                ship.stop(CarrierPolicy.Stop.OBSTRUCTED); return;
            }
            ship.cargo.commit(plan.get());
            if (!drainingFluid) carrier.recordCapture(pos, pending.drops());
            if (!sustained) CarrierPowerPolicy.consumeWork(ship, CarrierPowerPolicy.MINING_BURST_ENERGY_PER_BLOCK);
            if (ship.pending == pending) ship.pending = null;
            if (miningSnapshot(ship, progress)) ship.progress = afterRemoval(progress, pos);
            ship.dirty.run();
            broken++;
            if (!drainingFluid) state.spawnAfterBreak(level, pos, tool, true);
            PlayerBlockBreakEvents.AFTER.invoker().afterBlockBreak(level, owner, pos, state, null);
        }
    }

    private static void tickCombat(CarrierEntity carrier, CarrierShip ship, ServerLevel level, ServerPlayer owner,
                                    CarrierPolicy.Operation op) {
        if (!carrier.hasOperationalOwner(owner)) { ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); return; }
        var phase = CarrierPolicy.combatPhase(ship.combatRemaining);
        if (phase == CarrierPolicy.WorkPhase.COOLDOWN) {
            ship.combatPrimaryTarget = null;
            ship.combatBeamTargets = List.of();
            carrier.clearBeams();
        }
        Vec3 from = carrier.beamOrigin();
        var living = new java.util.ArrayList<LivingEntity>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
            CarrierCombatPolicy.searchBounds(op.chunkX() * 16 + 8, op.chunkZ() * 16 + 8, op.minY(), from.y),
            LivingEntity::isAlive, living, 129);
        if (living.size() > 128) { ship.stop(CarrierPolicy.Stop.TARGET_LIMIT); return; }
        var visible = living.stream().filter(e -> safeCombatAim(carrier, level, owner, op, e) != null)
            .sorted(java.util.Comparator.comparing(LivingEntity::getUUID)).toList();
        if (phase == CarrierPolicy.WorkPhase.SCAN) {
            if (CarrierPolicy.phaseTick(ship.combatRemaining) == 0) ship.combatScanAims = List.of();
            ship.combatPrimaryTarget = null;
            ship.combatBeamTargets = visible.stream().limit(CarrierPolicy.COMBAT_TARGETS)
                .map(LivingEntity::getUUID).toList();
            ship.combatScanAims = visible.stream().limit(CarrierPolicy.COMBAT_TARGETS)
                .map(LivingEntity::getEyePosition).toList();
            carrier.recordBeams(ship.combatScanAims);
        } else if (phase == CarrierPolicy.WorkPhase.CHARGE) {
            LivingEntity primary = primaryTarget(carrier, ship, visible);
            ship.combatBeamTargets = List.of();
            if (primary == null) carrier.clearBeams();
            else carrier.recordBeams(CarrierCombatPolicy.converge(ship.combatScanAims, primary.getEyePosition(),
                CarrierPolicy.phaseTick(ship.combatRemaining), CarrierPolicy.COMBAT_CHARGE_TICKS));
            if (!workPower(carrier, ship, CarrierCombatPolicy.CHARGE_ENERGY)) return;
            CarrierPowerPolicy.consumeWork(ship, CarrierCombatPolicy.CHARGE_ENERGY);
        } else if (phase == CarrierPolicy.WorkPhase.FIRE) {
            ship.combatScanAims = List.of();
            if (!workPower(carrier, ship, CarrierCombatPolicy.HIT_ENERGY)) return;
            LivingEntity primary = primaryTarget(carrier, ship, visible);
            if (primary == null) {
                ship.combatBeamTargets = List.of();
                carrier.clearBeams();
            } else {
                Vec3 center = primary.getEyePosition();
                var impactEntities = new java.util.ArrayList<LivingEntity>();
                AABB impactBounds = AABB.ofSize(center, CarrierCombatPolicy.IMPACT_RADIUS * 2,
                    CarrierCombatPolicy.IMPACT_RADIUS * 2, CarrierCombatPolicy.IMPACT_RADIUS * 2);
                level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    impactBounds, LivingEntity::isAlive, impactEntities, 129);
                if (impactEntities.size() > 128) { ship.stop(CarrierPolicy.Stop.TARGET_LIMIT); return; }
                var beamEntities = new java.util.ArrayList<LivingEntity>();
                level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
                    new AABB(from, center).inflate(CarrierCombatPolicy.IMPACT_RADIUS),
                    LivingEntity::isAlive, beamEntities, 129);
                if (beamEntities.size() > 128) { ship.stop(CarrierPolicy.Stop.TARGET_LIMIT); return; }
                boolean friendlyInImpact = impactEntities.stream().anyMatch(e -> !hostile(e, owner)
                    && CarrierCombatPolicy.intersectsImpactArea(e.getBoundingBox(), center));
                boolean friendlyInBeam = beamEntities.stream().anyMatch(e -> !hostile(e, owner)
                    && CarrierCombatPolicy.intersectsAreaBeam(from, center, e.getBoundingBox()));
                var areaTargets = impactEntities.stream().filter(e -> hostile(e, owner)
                    && CarrierCombatPolicy.inImpactArea(e.getEyePosition(), center)
                    && safeCombatAim(carrier, level, owner, op, e) != null)
                    .sorted(java.util.Comparator.comparing(LivingEntity::getUUID)).toList();
                var endpoints = new java.util.ArrayList<Vec3>();
                endpoints.add(center);
                if (friendlyInImpact || friendlyInBeam) {
                    ship.combatBeamTargets = List.of();
                    carrier.clearBeams();
                } else if (CarrierPolicy.phaseTick(ship.combatRemaining) % CarrierPolicy.COMBAT_PULSE_TICKS == 0) {
                    var indices = CarrierCombatPolicy.rotation(areaTargets.size(), ship.combatCursor);
                    ship.combatCursor = areaTargets.isEmpty() ? 0
                        : (Math.floorMod(ship.combatCursor, areaTargets.size()) + indices.size()) % areaTargets.size();
                    var succeeded = new java.util.ArrayList<java.util.UUID>();
                    for (int index : indices) {
                        if (!continues(ship, op, CarrierPolicy.Mode.COMBAT)) { carrier.clearBeams(); return; }
                        if (!carrier.hasOperationalOwner(owner)) {
                            ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); carrier.clearBeams(); return;
                        }
                        if (!CarrierPowerPolicy.workAvailable(ship, CarrierCombatPolicy.HIT_ENERGY)) {
                            waitForReactor(carrier, ship); return;
                        }
                        if (impactEntities.stream().anyMatch(e -> e.isAlive() && !hostile(e, owner)
                            && CarrierCombatPolicy.intersectsImpactArea(e.getBoundingBox(), center))
                            || beamEntities.stream().anyMatch(e -> e.isAlive() && !hostile(e, owner)
                                && CarrierCombatPolicy.intersectsAreaBeam(from, center, e.getBoundingBox()))) {
                            carrier.clearBeams(); return;
                        }
                        LivingEntity target = areaTargets.get(index);
                        Vec3 impact = target.getEyePosition();
                        if (!CarrierCombatPolicy.inImpactArea(impact, center)
                            || safeCombatAim(carrier, level, owner, op, target) == null) continue;
                        if (target.hurtServer(level, level.damageSources().playerAttack(owner), CarrierCombatPolicy.DAMAGE)) {
                            if (!continues(ship, op, CarrierPolicy.Mode.COMBAT)) { carrier.clearBeams(); return; }
                            if (!carrier.hasOperationalOwner(owner)) {
                                ship.stop(CarrierPolicy.Stop.OWNER_ABSENT); carrier.clearBeams(); return;
                            }
                            if (!CarrierPowerPolicy.workAvailable(ship, CarrierCombatPolicy.HIT_ENERGY)) {
                                waitForReactor(carrier, ship); return;
                            }
                            if (impactEntities.stream().anyMatch(e -> e.isAlive() && !hostile(e, owner)
                                && CarrierCombatPolicy.intersectsImpactArea(e.getBoundingBox(), center))
                                || beamEntities.stream().anyMatch(e -> e.isAlive() && !hostile(e, owner)
                                    && CarrierCombatPolicy.intersectsAreaBeam(from, center, e.getBoundingBox()))) {
                                carrier.clearBeams(); return;
                            }
                            if (target.isAlive() && safeCombatAim(carrier, level, owner, op, target) == null) {
                                carrier.clearBeams(); return;
                            }
                            succeeded.add(target.getUUID());
                            if (!target.getUUID().equals(ship.combatPrimaryTarget)
                                && endpoints.size() < CarrierPolicy.COMBAT_TARGETS) endpoints.add(impact);
                        }
                    }
                    if (!continues(ship, op, CarrierPolicy.Mode.COMBAT)) { carrier.clearBeams(); return; }
                    if (!succeeded.isEmpty()) {
                        if (!CarrierPowerPolicy.consumeWork(ship, CarrierCombatPolicy.HIT_ENERGY)) {
                            waitForReactor(carrier, ship); return;
                        }
                    }
                    ship.combatBeamTargets = List.copyOf(succeeded);
                    carrier.recordBeams(endpoints);
                } else {
                    ship.combatBeamTargets = List.of();
                    carrier.recordBeams(endpoints);
                }
            }
        }
        if (continues(ship, op, CarrierPolicy.Mode.COMBAT) && --ship.combatRemaining <= 0) ship.stop(CarrierPolicy.Stop.COMPLETE);
    }

    private static LivingEntity primaryTarget(CarrierEntity carrier, CarrierShip ship, List<LivingEntity> visible) {
        for (LivingEntity target : visible)
            if (target.getUUID().equals(ship.combatPrimaryTarget)) return target;
        var threats = visible.stream().map(target -> new CarrierCombatPolicy.Threat<>(target.getUUID(),
            target.getMaxHealth(), carrier.distanceToSqr(target))).toList();
        ship.combatPrimaryTarget = CarrierCombatPolicy.retainOrSelectPrimary(ship.combatPrimaryTarget, threats).orElse(null);
        if (ship.combatPrimaryTarget == null) return null;
        for (LivingEntity target : visible)
            if (target.getUUID().equals(ship.combatPrimaryTarget)) return target;
        return null;
    }

    private static Vec3 safeCombatAim(CarrierEntity carrier, ServerLevel level, ServerPlayer owner,
                                      CarrierPolicy.Operation op, LivingEntity target) {
        Vec3 from = carrier.beamOrigin(), aim = target.getEyePosition();
        if (target.level() != level || !hostile(target, owner)
            || !CarrierCombatPolicy.inCylinder(aim, op.chunkX() * 16 + 8, op.chunkZ() * 16 + 8, op.minY(), from.y)) return null;
        var nearby = new java.util.ArrayList<LivingEntity>();
        level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(LivingEntity.class),
            new AABB(from, aim).inflate(CarrierPolicy.MAX_BEAM_RADIUS), LivingEntity::isAlive, nearby, 129);
        if (nearby.size() > 128 || nearby.stream().anyMatch(e -> e != target && !hostile(e, owner)
            && CarrierCombatPolicy.intersectsFriendly(from, aim, e.getBoundingBox()))) return null;
        if (!loadedRay(level, from, aim) || !level.mayInteract(owner, target.blockPosition())
            || !owner.mayInteract(level, target.blockPosition())
            || level.clip(new ClipContext(from, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, carrier))
                .getType() != HitResult.Type.MISS) return null;
        return aim;
    }

    private static boolean loadedRay(ServerLevel level, Vec3 from, Vec3 to) {
        for (int x = ((int) Math.floor(Math.min(from.x, to.x))) >> 4; x <= ((int) Math.floor(Math.max(from.x, to.x))) >> 4; x++)
            for (int z = ((int) Math.floor(Math.min(from.z, to.z))) >> 4; z <= ((int) Math.floor(Math.max(from.z, to.z))) >> 4; z++)
                if (level.getChunkSource().getChunkNow(x, z) == null) return false;
        return true;
    }

    private static boolean workPower(CarrierEntity carrier, CarrierShip ship, int cost) {
        if (CarrierPowerPolicy.workAvailable(ship, cost)) {
            if (ship.stop == CarrierPolicy.Stop.NO_POWER) {
                ship.stop = CarrierPolicy.Stop.RUNNING;
                ship.dirty.run();
            }
            return true;
        }
        waitForReactor(carrier, ship);
        return false;
    }

    private static void waitForReactor(CarrierEntity carrier, CarrierShip ship) {
        carrier.clearBeams();
        if (ship.stop != CarrierPolicy.Stop.NO_POWER) {
            ship.stop = CarrierPolicy.Stop.NO_POWER;
            ship.dirty.run();
        }
    }

    static boolean continues(CarrierShip ship, CarrierPolicy.Operation op, CarrierPolicy.Mode mode) {
        return !ship.destroyed && ship.mode == mode && mode != CarrierPolicy.Mode.IDLE && (mode == CarrierPolicy.Mode.MINING
            ? ship.progress != null && ship.progress.operation().equals(op) : op.equals(ship.activeCombat));
    }

    static boolean miningSnapshot(CarrierShip ship, CarrierPolicy.Progress progress) {
        return continues(ship, progress.operation(), CarrierPolicy.Mode.MINING) && ship.progress == progress;
    }

    private static boolean sameUnclaimedBlock(ServerPlayer owner, ServerLevel level, BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
        return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null
            && owner.mayBuild() && !owner.isSpectator() && level.mayInteract(owner, pos) && owner.mayInteract(level, pos)
            && state.equals(level.getBlockState(pos)) && level.getBlockEntity(pos) == null
            && !CarrierSavedData.get(level.getServer()).playerBuilt(level.dimension().identifier().toString(), pos.asLong());
    }

    static Vec3 miningOrigin(CarrierPolicy.Progress progress, double altitude) {
        var op = progress.operation();
        return new Vec3(op.x(progress.cursor()) + .5, altitude, op.z(progress.cursor()) + .5);
    }

    /** A column never borrows another column's visible block or pierces a new obstruction. */
    static BlockPos miningTarget(CarrierPolicy.Progress progress, BlockHitResult hit) {
        if (progress.complete() || progress.sealed()) return null;
        var op = progress.operation();
        BlockPos target = new BlockPos(op.x(progress.cursor()), op.y(progress.cursor()), op.z(progress.cursor()));
        if (hit.getType() == HitResult.Type.MISS) return target;
        BlockPos visible = hit.getBlockPos();
        if (!visible.equals(target)) return null;
        return visible.immutable();
    }

    static CarrierPolicy.Progress afterRemoval(CarrierPolicy.Progress progress, BlockPos removed) {
        var op = progress.operation();
        // Only the transaction for this exact raster cell may advance its cursor.
        return removed.getX() == op.x(progress.cursor()) && removed.getY() == op.y(progress.cursor())
            && removed.getZ() == op.z(progress.cursor()) ? progress.advance() : progress;
    }

    private static boolean hostile(LivingEntity entity, ServerPlayer owner) {
        return entity.isAlive() && entity instanceof Enemy && !(entity instanceof Player)
            && !entity.getType().builtInRegistryHolder().is(FRIENDLY) && !entity.isAlliedTo(owner) && !owner.isAlliedTo(entity);
    }
}
