package jp.morrowgear.drone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkRegistry.Configuration;
import jp.morrowgear.drone.SupplyNetworkRegistry.Job;
import jp.morrowgear.drone.SupplyNetworkRegistry.Route;
import jp.morrowgear.drone.SupplyNetworkRegistry.Stage;
import jp.morrowgear.drone.SupplyNetworkRegistry.Token;
import jp.morrowgear.drone.block.DockBlockEntity;
import jp.morrowgear.drone.carrier.CarrierCargoContainerBlockEntity;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.Vec3;

/** Physical transfers run only inside the existing Cargo service-arrival path. */
public final class SupplyNetworkRuntime {
	private static boolean registered;
	private static final java.util.Map<String, java.lang.ref.WeakReference<DockBlockEntity>> PENDING_DOCKS
		= new java.util.LinkedHashMap<>();
	private SupplyNetworkRuntime() {}

	public static synchronized void register() {
		if (registered) return;
		registered = true;
		ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
			if (blockEntity instanceof DockBlockEntity dock) ensureDock(level, dock);
		});
		ServerTickEvents.END_LEVEL_TICK.register(level -> tickLevel(level, DroneEntity.SUPPLY_CARGO_HOOKS));
	}

	/** Implement in DroneEntity so private task state and CARGO_ACCESS remain authoritative. */
	public interface CargoHooks {
		Token token(DroneEntity drone);
		boolean taskStackIdle(DroneEntity drone);
		boolean dispatch(DroneEntity drone, Job job);
		boolean redirectRetained(DroneEntity drone, Job job, BlockPos endpoint);
		List<ItemStack> cargo(DroneEntity drone);
		boolean acquireAccess(DroneEntity drone, BlockPos endpoint);
		void releaseAccess(DroneEntity drone);
		void stop(DroneEntity drone, Token token, boolean retainCargo, Status reason);
		void idleAfterRoute(DroneEntity drone);
	}

	public enum ServiceResult { NOT_NETWORK, WAIT, TO_TARGET, COMPLETE, HOLD }

	public static void tickFromDrone(ServerLevel level, DroneEntity drone, CargoHooks hooks) {
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		if (drone.role() == DroneRole.CARGO) {
			java.lang.ref.WeakReference<DroneEntity> previous = data.candidates.remove(drone.getUUID());
			data.candidates.put(drone.getUUID(), new java.lang.ref.WeakReference<>(drone));
			if (previous == null || previous.get() != drone) data.requestEvaluation();
			while (data.candidates.size() > SupplyNetworkPolicy.MAX_CANDIDATES) data.candidates.remove(data.candidates.firstEntry().getKey());
		} else if (data.candidates.remove(drone.getUUID()) != null) data.requestEvaluation();
	}

	private static void tickLevel(ServerLevel level, CargoHooks hooks) {
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		if (level.getGameTime() % 200L == 0L) {
			pruneMissingDocks(level, data);
			retryPendingDocks(level);
		}
		data.candidates.values().removeIf(ref -> ref.get() == null || ref.get().isRemoved() || ref.get().level() != level);
		long tick = level.getGameTime();
		if (!SupplyNetworkSchedulePolicy.shouldEvaluate(tick, data.lastTick, data.evaluationRequested())) return;
		data.consumeEvaluationRequest();
		tick(level, data.candidates.values().stream().map(java.lang.ref.WeakReference::get).filter(Objects::nonNull).toList(), hooks);
	}

	public static Configuration configure(ServerPlayer owner, BlockPos source, BlockPos dock,
		List<Rule> rules, boolean enabled) {
		ServerLevel level = (ServerLevel)owner.level();
		if (source == null || dock == null || source.equals(dock)
			|| source.distSqr(dock) > square(SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)
			|| !level.hasChunkAt(source) || !level.hasChunkAt(dock))
			throw new IllegalArgumentException("Supply endpoints must be loaded and within 256 blocks");
		Container warehouse = source(level, source, owner.getUUID());
		// The tactical map may select any already-loaded source on the route.  The
		// vanilla stillValid check is a player interaction range check (roughly
		// eight blocks), not an automation ownership or route-safety check.
		if (warehouse == null
			|| !(level.getBlockEntity(dock) instanceof DockBlockEntity target) || !target.isOwnedBy(owner.getUUID()))
			throw new IllegalArgumentException("Supply source must be accessible and Dock must be owned");
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		data.registerNode(owner.getUUID(), dimension(level), source.asLong());
		Configuration configured = data.registry().configure(new Route(owner.getUUID(),
			dimension(level), source.asLong(), dock.asLong()), rules, true);
		data.requestEvaluation();
		return configured;
	}

	/** Compatibility entry point. Owned Docks are always members of the shared logistics network. */
	public static Configuration configureDock(ServerPlayer owner, BlockPos dock, boolean enabled) {
		if (owner == null || dock == null || !(owner.level() instanceof ServerLevel level)
			|| !level.hasChunkAt(dock) || !(level.getBlockEntity(dock) instanceof DockBlockEntity target)
			|| !target.isOwnedBy(owner.getUUID()))
			throw new IllegalArgumentException("Dock must be loaded and owned");
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		SupplyNetworkRegistry registry = data.registry();
		pruneMissingDocks(level, data);
		Configuration current = registry.configurations().stream().filter(c -> c.route().owner().equals(owner.getUUID())
			&& c.route().dimension().equals(dimension(level)) && c.route().dock() == dock.asLong()).findFirst().orElse(null);
		Route route = current == null
			? new Route(owner.getUUID(), dimension(level), Route.AUTO_SOURCE, dock.asLong())
			: current.route();
		Configuration configured;
		try {
			configured = registry.configure(route,
				current == null ? SupplyNetworkPolicy.defaults() : current.rules(), true);
		} catch (IllegalStateException exception) {
			if (!"Supply route limit reached".equals(exception.getMessage())) throw exception;
			PENDING_DOCKS.put(pendingDockKey(level, dock),
				new java.lang.ref.WeakReference<>(target));
			MorrowgearDrone.LOGGER.warn("Supply ledger is full; Dock {} will retry after stale-route cleanup",
				dock);
			return null;
		}
		PENDING_DOCKS.remove(pendingDockKey(level, dock));
		data.requestEvaluation();
		return configured;
	}

	/** Idempotent load/placement migration. It performs no world scan and keeps customized thresholds. */
	public static Configuration ensureDock(ServerLevel level, DockBlockEntity dock) {
		if (level == null || dock == null || dock.isRemoved() || dock.ownerId().equals(new UUID(0, 0))) return null;
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		SupplyNetworkRegistry registry = data.registry();
		pruneMissingDocks(level, data);
		String dimension = dimension(level);
		Configuration current = registry.configurations().stream().filter(c -> c.route().owner().equals(dock.ownerId())
			&& c.route().dimension().equals(dimension) && c.route().dock() == dock.getBlockPos().asLong()).findFirst().orElse(null);
		if (current != null && current.enabled()) return current;
		Route route = current == null ? new Route(dock.ownerId(), dimension, Route.AUTO_SOURCE, dock.getBlockPos().asLong())
			: current.route();
		Configuration configured;
		try {
			configured = registry.configure(route,
				current == null ? SupplyNetworkPolicy.defaults() : current.rules(), true);
		} catch (IllegalStateException exception) {
			if (!"Supply route limit reached".equals(exception.getMessage())) throw exception;
			PENDING_DOCKS.put(pendingDockKey(level, dock.getBlockPos()),
				new java.lang.ref.WeakReference<>(dock));
			MorrowgearDrone.LOGGER.warn("Supply ledger is full; Dock {} will retry after stale-route cleanup",
				dock.getBlockPos());
			return null;
		}
		PENDING_DOCKS.remove(pendingDockKey(level, dock.getBlockPos()));
		data.requestEvaluation();
		return configured;
	}

	/** Removes a Dock from the persistent route ledger before its block entity disappears. */
	public static void removeDock(ServerLevel level, UUID owner, BlockPos dock) {
		if (level == null || owner == null || dock == null) return;
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		if (!data.registry().removeDock(new Route(owner, dimension(level), Route.AUTO_SOURCE,
			dock.asLong()))) return;
		data.retainDeliveryHistory(data.registry().configurations());
		data.requestEvaluation();
	}

	/** Cleans legacy entries only when their chunk is already loaded and proves the Dock is gone. */
	private static void pruneMissingDocks(ServerLevel level, SupplyNetworkSavedData data) {
		String dimension = dimension(level);
		boolean changed = false;
		for (Configuration config : List.copyOf(data.registry().configurations())) {
			if (!config.route().dimension().equals(dimension)) continue;
			BlockPos pos = BlockPos.of(config.route().dock());
			var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk == null || chunk.getBlockEntity(pos) instanceof DockBlockEntity) continue;
			changed |= data.registry().removeDock(config.route());
		}
		if (changed) {
			data.retainDeliveryHistory(data.registry().configurations());
			data.requestEvaluation();
		}
	}

	private static void retryPendingDocks(ServerLevel level) {
		for (var entry : List.copyOf(PENDING_DOCKS.entrySet())) {
			DockBlockEntity dock = entry.getValue().get();
			if (dock == null || dock.isRemoved()) {
				PENDING_DOCKS.remove(entry.getKey());
				continue;
			}
			if (dock.getLevel() != level) continue;
			PENDING_DOCKS.remove(entry.getKey());
			ensureDock(level, dock);
		}
	}

	private static String pendingDockKey(ServerLevel level, BlockPos pos) {
		return dimension(level) + ':' + pos.asLong();
	}

	public static boolean selectLogisticsNode(ServerPlayer owner, BlockPos pos) {
		if (owner == null || pos == null || !(owner.level() instanceof ServerLevel level)) return false;
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		if (data.nodes(owner.getUUID(), dimension(level)).contains(pos.asLong())) return true;
		BlockPos origin = owner.blockPosition();
		long dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
		if (Math.abs(pos.getY() - origin.getY()) > SupplyNetworkPolicy.DISCOVERY_VERTICAL
			|| dx * dx + dz * dz > (long)SupplyNetworkPolicy.DISCOVERY_RADIUS * SupplyNetworkPolicy.DISCOVERY_RADIUS
			|| !level.hasChunkAt(pos) || source(level, pos, owner.getUUID()) == null) return false;
		data.registerNode(owner.getUUID(), dimension(level), pos.asLong());
		return true;
	}

	/** Explicit map selection. Removing a node prevents new jobs but never destroys an in-flight manifest. */
	public static boolean setLogisticsNode(ServerPlayer owner, BlockPos pos, boolean registered) {
		if (owner == null || pos == null || !(owner.level() instanceof ServerLevel level)) return false;
		if (registered) return selectLogisticsNode(owner, pos);
		return SupplyNetworkSavedData.get(level).unregisterNode(owner.getUUID(), dimension(level), pos.asLong());
	}

	/** Legacy API: automatic supply is mandatory, so this now repairs the route to enabled. */
	public static boolean disable(ServerPlayer owner, BlockPos dock) {
		return configureDock(owner, dock, true) != null;
	}

	/** Called once per level tick with an already available, bounded loaded-drone list. */
	public static void tick(ServerLevel level, List<DroneEntity> candidates, CargoHooks hooks) {
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		if (data.lastTick == level.getGameTime()) return;
		data.lastTick = level.getGameTime();
		SupplyNetworkRegistry registry = data.registry();
		if (hooks == null) {
			for (Configuration config : registry.configurations()) if (config.enabled())
				registry.status(config.route(), Status.RUNTIME_UNAVAILABLE);
			return;
		}
		reconcile(level, registry, hooks);
		List<DroneEntity> pool = candidates == null ? List.of()
			: candidates.stream().limit(SupplyNetworkPolicy.MAX_CANDIDATES).filter(Objects::nonNull).toList();
		List<Demand> demands = pendingDemands(level, registry);
		java.util.Map<Route, Status> firstFailure = new java.util.LinkedHashMap<>();
		for (Demand demand : demands) {
			Route route = demand.config().route();
			if (registry.kindReserved(route, demand.rule().kind())) continue;
			dispatch(level, registry, demand, pool, hooks);
			if (!registry.kindReserved(route, demand.rule().kind())) firstFailure.putIfAbsent(route, registry.status(route));
		}
		firstFailure.forEach((route, reason) -> { if (!registry.dockReserved(route)) registry.status(route, reason); });
		notifyChanges(level, data);
	}

	private static void dispatch(ServerLevel level, SupplyNetworkRegistry registry, Demand demand,
		List<DroneEntity> pool, CargoHooks hooks) {
		Route configRoute = demand.config().route();
		DockBlockEntity target = dock(level, configRoute);
		if (target == null) return;
		SourceChoice choice = selectSource(level, registry, demand, target);
		if (choice == null) return;
		Route route = new Route(configRoute.owner(), configRoute.dimension(), choice.position().asLong(), configRoute.dock());
		Container source = choice.container();
		ItemStack available = choice.stack();
		int count = choice.movable();
		DroneEntity candidate = pool.stream().filter(d -> eligible(level, d, route, hooks)
			&& registry.job(d.getUUID()).isEmpty() && hooks.token(d) == null)
			.min(Comparator.comparingDouble((DroneEntity d) -> d.position().distanceToSqr(Vec3.atCenterOf(BlockPos.of(route.source()))))
				.thenComparing(DroneEntity::getUUID)).orElse(null);
		if (candidate == null) { registry.status(route, Status.NO_IDLE_CARGO); return; }
		String item = DockSupplyPolicy.itemId(available);
		Job job = registry.reserve(demand.config(), route, candidate.getUUID(), demand.rule().kind(), item,
			count, count(source, item), room(target, available), level.getGameTime()).orElse(null);
		if (job == null) { registry.status(route, Status.CAPACITY_LIMIT); return; }
		if (!hooks.dispatch(candidate, job)) {
			registry.release(job.token());
			registry.status(route, Status.NO_IDLE_CARGO);
		}
	}

	private static SourceChoice selectSource(ServerLevel level, SupplyNetworkRegistry registry, Demand demand,
		DockBlockEntity target) {
		Route route = demand.config().route();
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		java.util.LinkedHashSet<Long> positions = new java.util.LinkedHashSet<>(data.nodes(route.owner(), route.dimension()));
		if (route.source() != Route.AUTO_SOURCE) positions.add(route.source());
		if (positions.isEmpty()) { registry.status(route, Status.SOURCE_LOST); return null; }
		SourceChoice best = null;
		boolean loaded = false, valid = false, hasSupply = false;
		BlockPos dockPos = BlockPos.of(route.dock());
		for (long packed : positions) {
			BlockPos position = BlockPos.of(packed);
			if (position.distSqr(dockPos) > square(SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)) continue;
			if (!level.hasChunkAt(position)) continue;
			loaded = true;
			Container source = source(level, position, route.owner());
			if (source == null) continue;
			valid = true;
			Route delivery = new Route(route.owner(), route.dimension(), packed, route.dock());
			List<SourceStock> stocks = source instanceof CarrierCargoContainerBlockEntity cargo
				? cargo.indexedSupplyStocks().stream().map(stock -> new SourceStock(stock.item(), stock.kind(), stock.count(), stock.sample())).toList()
				: sourceStocks(source);
			for (SourceStock stockEntry : stocks) {
				if (stockEntry.kind() != demand.rule().kind()) continue;
				ItemStack stack = stockEntry.sample();
				String item = stockEntry.item();
				int stock = stockEntry.count();
				int free = SupplyNetworkPolicy.available(stock, registry.reservedSource(delivery, item));
				if (free <= 0) continue;
				hasSupply = true;
				int movable = Math.min(Math.min(demand.missing(), free), Math.min(room(target, stack), stack.getMaxStackSize()));
				if (movable <= 0) continue;
				SourceChoice next = new SourceChoice(position, source, stack, movable,
					DockSupplyPolicy.deliveryPriority(item), position.distSqr(dockPos));
				if (best == null || next.priority() > best.priority()
					|| next.priority() == best.priority() && next.movable() > best.movable()
					|| next.priority() == best.priority() && next.movable() == best.movable()
						&& next.distance() < best.distance()) best = next;
			}
		}
		if (best == null) registry.status(route, !loaded ? Status.WAIT_CHUNK
			: !valid ? Status.SOURCE_LOST : hasSupply ? Status.DOCK_FULL : Status.SOURCE_SHORTAGE);
		return best;
	}

	private static boolean eligible(ServerLevel level, DroneEntity drone, Route route, CargoHooks hooks) {
		ServerPlayer owner = level.getServer().getPlayerList().getPlayer(route.owner());
		if (owner == null || !CarrierDroneServiceAdapter.ownerSupportsExterior(level, owner)) return false;
		if (!drone.isAlive() || drone.level() != level || !drone.isOwnedBy(route.owner())
			|| drone.position().distanceToSqr(Vec3.atCenterOf(BlockPos.of(route.source())))
				> square(SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)) return false;
		return SupplyNetworkPolicy.dispatchable(new SupplyNetworkPolicy.Readiness(
			drone.role() == DroneRole.CARGO, drone.mode() == DroneMode.STANDBY || drone.isDocked(),
			drone.cargoItemCount() == 0 && hooks.cargo(drone).stream().allMatch(ItemStack::isEmpty),
			SupplyNetworkPolicy.protectedGroup(drone.groupId()), drone.hasWaypoint() || drone.hasTrackingTarget()
				|| drone.hasFieldOperation() || drone.hasSecurityPatrol() || drone.hasPatrolRoute()
				|| !drone.cohortId().isBlank() || drone.hasRendezvous(), !hooks.taskStackIdle(drone),
			drone.recoveryLevel() > 0, drone.combatActive() || drone.emergencyInterceptActive(),
			drone.salvageTargetEntityId() >= 0 || drone.salvageState() != SalvageState.IDLE,
			drone.serviceReturnActive(), drone.hasCargoSource() || drone.hasCargoTarget()
				|| drone.cargoState() != CargoState.UNASSIGNED,
			drone.isPowerLost(), drone.isDocked(), drone.batteryPercent()));
	}

	private static void reconcile(ServerLevel level, SupplyNetworkRegistry registry, CargoHooks hooks) {
		List<UUID> present = new ArrayList<>();
		for (Job job : registry.jobs()) {
			if (!(level.getEntity(job.drone()) instanceof DroneEntity drone)) continue;
			present.add(drone.getUUID());
			Token token = hooks.token(drone);
			if (!job.token().equals(token) || !drone.isOwnedBy(job.route().owner())
				|| !dimension(level).equals(job.route().dimension())) {
				registry.cancel(job.token(), Status.ASSIGNMENT_LOST);
				// Never change a different/current player assignment or its inventory.
				if (token == null || !job.token().equals(token)) registry.release(job.token());
				else hold(registry, drone, job, hooks, Status.ACCESS_DENIED);
				continue;
			}
			if (!registry.current(job) || job.stage() == Stage.HELD) {
				hold(registry, drone, job, hooks, job.status());
				// The fallback validator is deliberately stricter than the reason list.
				// Let every retained load attempt recovery so configuration churn or a
				// stale assignment cannot strand physical cargo as UNASSIGNED.
				tryFallback(level, registry, drone, job.token(), hooks);
				continue;
			}
			if (!cargoMatches(job, hooks.cargo(drone))) {
				hold(registry, drone, job, hooks, Status.CARGO_MISMATCH);
				continue;
			}
			if (drone.role() != DroneRole.CARGO || !drone.hasCargoSource() || !drone.hasCargoTarget()
				|| drone.cargoSource().asLong() != job.route().source() || drone.cargoTarget().asLong() != job.deliveryTarget()
				|| drone.cargoPaused() || drone.hasActiveFieldOperation() || drone.hasSecurityPatrol()
				|| drone.hasPatrolRoute() || drone.hasTrackingTarget()
				|| !drone.missionId().equals(drone.unitId() + "-cargo")) {
				hold(registry, drone, job, hooks, Status.ASSIGNMENT_LOST);
				continue;
			}
			Status endpoint = job.returning() ? fallbackStatus(level, job) : endpoints(level, job.route());
			// Once laden, loss of the source does not prevent completing the physical delivery.
			if ((endpoint == Status.SOURCE_LOST || endpoint == Status.WAIT_CHUNK) && job.stage() == Stage.LOADED)
				endpoint = targetStatus(level, job.route());
			if (endpoint != Status.READY && endpoint != Status.WAIT_CHUNK) {
				hold(registry, drone, job, hooks, endpoint);
				tryFallback(level, registry, drone, job.token(), hooks);
				continue;
			}
			registry.heartbeat(job.token(), level.getGameTime(), drone.serviceReturnActive() || drone.isPowerLost());
			if (endpoint == Status.WAIT_CHUNK) registry.status(job.route(), endpoint);
		}
		registry.expireMissing(level.getGameTime(), present);
	}

	/** Call before ordinary loadCargo/unloadCargo, after the Cargo service dwell has elapsed. */
	public static ServiceResult serviceEndpoint(ServerLevel level, DroneEntity drone, BlockPos endpoint,
		boolean loading, CargoHooks hooks) {
		Token token = hooks.token(drone);
		if (token == null) return ServiceResult.NOT_NETWORK;
		SupplyNetworkRegistry registry = SupplyNetworkSavedData.get(level).registry();
		Job job = registry.job(token).orElse(null);
		if (job == null || !authorized(level, drone, job) || !registry.current(job)) {
			if (job != null) registry.cancel(token, Status.RESERVATION_INVALID);
			hooks.releaseAccess(drone);
			hooks.stop(drone, token, !hooks.cargo(drone).isEmpty(), Status.RESERVATION_INVALID);
			return ServiceResult.HOLD;
		}
		if (endpoint.asLong() != (loading ? job.route().source() : job.deliveryTarget())
			|| drone.position().distanceToSqr(Vec3.atCenterOf(endpoint).add(0, 2, 0)) > 9.0
			|| !level.hasChunkAt(endpoint) || drone.serviceReturnActive() || drone.isPowerLost()
			|| drone.combatActive() || drone.recoveryLevel() > 0) return ServiceResult.WAIT;
		if (!hooks.acquireAccess(drone, endpoint)) return ServiceResult.WAIT;
		try {
			if (!cargoMatches(job, hooks.cargo(drone))) {
				hold(registry, drone, job, hooks, Status.CARGO_MISMATCH);
				return ServiceResult.HOLD;
			}
			return loading ? load(level, registry, drone, job, hooks) : unload(level, registry, drone, job, hooks);
		} finally { hooks.releaseAccess(drone); }
	}

	/** Pre-load allowance must be re-evaluated while holding the existing container lease. */
	public static int beforeLoad(SupplyNetworkRegistry registry, Job job, Container source, ItemStack stack) {
		if (!registry.current(job) || job.stage() != Stage.RESERVED || !allowed(job, stack)) return 0;
		int otherReservations = Math.max(0, registry.reservedSource(job.route(), job.item()) - job.requested());
		return Math.min(job.requested(), Math.min(stack.getCount(),
			SupplyNetworkPolicy.available(count(source, job.item()), otherReservations)));
	}

	/** Post-load acknowledgement records quantity only, never a copy of the ItemStack. */
	public static boolean afterLoad(SupplyNetworkRegistry registry, Token token, int moved) {
		return registry.loaded(token, moved);
	}

	private static ServiceResult load(ServerLevel level, SupplyNetworkRegistry registry, DroneEntity drone,
		Job job, CargoHooks hooks) {
		if (job.stage() != Stage.RESERVED) return job.stage() == Stage.LOADED ? ServiceResult.TO_TARGET : ServiceResult.HOLD;
		Container source = source(level, BlockPos.of(job.route().source()), job.route().owner());
		Status target = targetStatus(level, job.route());
		if (target == Status.WAIT_CHUNK) return ServiceResult.WAIT;
		if (source == null || target != Status.READY) {
			hold(registry, drone, job, hooks, source == null ? Status.SOURCE_LOST : target);
			return ServiceResult.HOLD;
		}
		int moved = loadReserved(registry, job, source, hooks.cargo(drone), stack -> room(dock(level, job.route()), stack));
		if (moved > 0) return ServiceResult.TO_TARGET;
		hold(registry, drone, job, hooks, moved < 0 ? Status.CARGO_MISMATCH : Status.RESERVATION_INVALID);
		return ServiceResult.HOLD;
	}

	// Only serviceEndpoint calls this in production, after distance, dwell, loaded-chunk and lease checks.
	static int loadReserved(SupplyNetworkRegistry registry, Job job, Container source, List<ItemStack> cargo,
		java.util.function.ToIntFunction<ItemStack> targetRoom) {
		if (!cargoMatches(job, cargo) || !supportedSourceSize(source)) return -1;
		if (!registry.current(job) || job.stage() != Stage.RESERVED) return 0;
		int otherReservations = Math.max(0, registry.reservedSource(job.route(), job.item()) - job.requested());
		int remaining = Math.min(job.requested(), SupplyNetworkPolicy.available(count(source, job.item()), otherReservations));
		List<ItemStack> loaded = new ArrayList<>();
		for (int slot : sourceSlots(source, job.item())) {
			if (remaining <= 0 || cargo.size() >= 9) break;
			ItemStack stack = source.getItem(slot);
			if (!source.canTakeItem(source, slot, stack) || !allowed(job, stack)) continue;
			int allowance = Math.min(remaining, Math.min(stack.getCount(), targetRoom.applyAsInt(stack)));
			if (allowance <= 0) continue;
			ItemStack removed = source.removeItem(slot, allowance);
			if (removed.isEmpty()) continue;
			cargo.add(removed);
			loaded.add(removed);
			remaining -= removed.getCount();
			source.setChanged();
		}
		int moved = loaded.stream().mapToInt(ItemStack::getCount).sum();
		if (moved <= 0) return 0;
		if (afterLoad(registry, job.token(), moved)) return moved;
		for (ItemStack stack : loaded) {
			cargo.remove(stack);
			returnToContainer(source, stack);
		}
		return -1;
	}

	public static boolean beforeUnload(SupplyNetworkRegistry registry, Job job, ItemStack stack) {
		return registry.current(job) && (job.stage() == Stage.LOADED || job.returning()) && allowed(job, stack);
	}

	public static boolean afterUnload(SupplyNetworkRegistry registry, Token token, int moved) {
		return registry.delivered(token, moved);
	}

	private static ServiceResult unload(ServerLevel level, SupplyNetworkRegistry registry, DroneEntity drone,
		Job job, CargoHooks hooks) {
		Container target = job.stage() == Stage.RETURN_SOURCE
			? source(level, BlockPos.of(job.deliveryTarget()), job.route().owner())
			: deliveryDock(level, job);
		if (target == null) {
			hold(registry, drone, job, hooks, Status.DOCK_LOST);
			tryFallback(level, registry, drone, job.token(), hooks);
			return ServiceResult.HOLD;
		}
		int moved = 0;
		for (ItemStack stack : hooks.cargo(drone)) {
			if (!beforeUnload(registry, job, stack)) continue;
			int before = stack.getCount();
			// insertNetworkSupply shrinks the caller's stack by the accepted amount.
			if (target instanceof DockBlockEntity dock) dock.insertNetworkSupply(stack);
			else returnToContainer(target, stack);
			moved += before - stack.getCount();
		}
		hooks.cargo(drone).removeIf(ItemStack::isEmpty);
		if (!afterUnload(registry, job.token(), moved)) {
			hold(registry, drone, job, hooks, Status.CARGO_MISMATCH);
			return ServiceResult.HOLD;
		}
		if (hooks.cargo(drone).isEmpty()) {
			SupplyNetworkSavedData.get(level).recordDelivery(job.route(), job.kind(), level.getGameTime());
			hooks.stop(drone, job.token(), false, Status.READY);
			// Continue a logistics sortie without an avoidable one-second parked
			// interval.  If another demand exists, the same aircraft receives it in
			// this server tick; otherwise it remains available to the normal fleet
			// dispatcher and does not return to its Dock merely because one trip ended.
			if (!dispatchNext(level, registry, drone, hooks)) hooks.idleAfterRoute(drone);
			return ServiceResult.COMPLETE;
		}
		return ServiceResult.WAIT;
	}

	private static List<Demand> pendingDemands(ServerLevel level, SupplyNetworkRegistry registry) {
		SupplyNetworkSavedData data = SupplyNetworkSavedData.get(level);
		data.retainDeliveryHistory(registry.configurations());
		long now = level.getGameTime();
		List<Demand> demands = new ArrayList<>();
		for (Configuration config : registry.configurations()) {
			if (!config.enabled()) { registry.status(config.route(), Status.DISABLED); continue; }
			Status endpoint = targetStatus(level, config.route());
			if (endpoint != Status.READY) { registry.status(config.route(), endpoint); continue; }
			DockBlockEntity target = dock(level, config.route());
			boolean satisfied = true;
			for (Rule rule : config.rules()) {
				int actual = target.effectiveSupplyCount(rule.kind());
				boolean refilling = registry.updateRefill(config.route(), rule.kind(), rule.minimum(), rule.target(), actual);
				int missing = SupplyNetworkPolicy.demand(rule.minimum(), rule.target(), actual,
					registry.incoming(config.route(), rule.kind()), refilling);
				if (missing > 0) {
					satisfied = false;
					if (SupplyNetworkPolicy.topOffReady(actual, rule.minimum(), now,
						data.lastDelivery(config.route(), rule.kind()))) demands.add(new Demand(config, rule, missing));
				}
			}
			if (satisfied) registry.status(config.route(), Status.READY);
		}
		demands.sort(Comparator.comparingInt((Demand d) -> d.rule().priority()).reversed()
			.thenComparingLong(d -> data.lastDelivery(d.config().route(), d.rule().kind()))
			.thenComparingLong(d -> d.config().route().dock()).thenComparing(d -> d.rule().kind()));
		return demands;
	}

	private static boolean dispatchNext(ServerLevel level, SupplyNetworkRegistry registry,
		DroneEntity drone, CargoHooks hooks) {
		for (Demand demand : pendingDemands(level, registry)) {
			if (!demand.config().route().owner().equals(drone.ownerId())) continue;
			dispatch(level, registry, demand, List.of(drone), hooks);
			if (hooks.token(drone) != null) return true;
		}
		return false;
	}

	public static void cancel(ServerLevel level, DroneEntity drone, CargoHooks hooks, Status reason) {
		Token token = hooks.token(drone);
		if (token == null) return;
		SupplyNetworkRegistry registry = SupplyNetworkSavedData.get(level).registry();
		Job job = registry.job(token).orElse(null);
		if (job != null) hold(registry, drone, job, hooks, reason);
		else { hooks.releaseAccess(drone); hooks.stop(drone, token, !hooks.cargo(drone).isEmpty(), reason); }
	}

	/** Invoke only after the normal death/drop or stored-drone path has secured the actual cargo. */
	public static void cargoSecuredOnRemoval(ServerLevel level, Token token) {
		SupplyNetworkSavedData.get(level).registry().release(token);
	}

	private static void hold(SupplyNetworkRegistry registry, DroneEntity drone, Job job, CargoHooks hooks, Status reason) {
		registry.cancel(job.token(), reason);
		hooks.releaseAccess(drone);
		boolean laden = hooks.cargo(drone).stream().anyMatch(s -> !s.isEmpty());
		hooks.stop(drone, job.token(), laden, reason);
		if (!laden) registry.release(job.token());
	}

	private static boolean authorized(ServerLevel level, DroneEntity drone, Job job) {
		return drone.level() == level && drone.getUUID().equals(job.drone()) && drone.isOwnedBy(job.route().owner())
			&& dimension(level).equals(job.route().dimension()) && drone.role() == DroneRole.CARGO
			&& drone.hasCargoSource() && drone.hasCargoTarget() && !drone.cargoPaused()
			&& drone.cargoSource().asLong() == job.route().source() && drone.cargoTarget().asLong() == job.deliveryTarget()
			&& drone.missionId().equals(drone.unitId() + "-cargo") && !SupplyNetworkPolicy.protectedGroup(drone.groupId());
	}

	private static void tryFallback(ServerLevel level, SupplyNetworkRegistry registry, DroneEntity drone, Token token, CargoHooks hooks) {
		Job held = registry.job(token).orElse(null);
		if (held == null || held.stage() != Stage.HELD || held.remaining() <= 0 || !cargoMatches(held, hooks.cargo(drone))
			|| drone.serviceReturnActive() || drone.isPowerLost() || drone.combatActive() || drone.recoveryLevel() > 0) return;
		ItemStack stack = hooks.cargo(drone).stream().filter(s -> !s.isEmpty()).findFirst().orElse(ItemStack.EMPTY);
		Configuration alternate = registry.configurations().stream().filter(c -> c.enabled()
			&& c.route().owner().equals(held.route().owner()) && c.route().dimension().equals(held.route().dimension())
			&& c.route().dock() != held.route().dock() && !registry.dockReserved(c.route())
			&& drone.position().distanceToSqr(Vec3.atCenterOf(BlockPos.of(c.route().dock()))) <= square(SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)
			&& room(dock(level, c.route()), stack) >= held.remaining())
			.min(Comparator.comparingDouble(c -> drone.position().distanceToSqr(Vec3.atCenterOf(BlockPos.of(c.route().dock())))))
			.orElse(null);
		long endpoint;
		boolean returningSource;
		if (alternate != null) { endpoint = alternate.route().dock(); returningSource = false; }
		else {
			endpoint = held.route().source(); returningSource = true;
			Container source = source(level, BlockPos.of(endpoint), held.route().owner());
			if (source == null || containerRoom(source, stack) < held.remaining()
				|| drone.position().distanceToSqr(Vec3.atCenterOf(BlockPos.of(endpoint))) > square(SupplyNetworkPolicy.MAX_ROUTE_DISTANCE)) return;
		}
		Job redirected = registry.redirectRetained(token, endpoint, returningSource).orElse(null);
		if (redirected != null && !hooks.redirectRetained(drone, redirected, BlockPos.of(endpoint)))
			registry.cancel(token, Status.ASSIGNMENT_LOST);
	}

	private static Status fallbackStatus(ServerLevel level, Job job) {
		BlockPos endpoint = BlockPos.of(job.deliveryTarget());
		if (!level.hasChunkAt(endpoint)) return Status.WAIT_CHUNK;
		if (job.stage() == Stage.RETURN_SOURCE)
			return source(level, endpoint, job.route().owner()) == null ? Status.SOURCE_LOST : Status.READY;
		return deliveryDock(level, job) == null ? Status.DOCK_LOST : Status.READY;
	}

	private static DockBlockEntity deliveryDock(ServerLevel level, Job job) {
		BlockPos endpoint = BlockPos.of(job.deliveryTarget());
		return level.hasChunkAt(endpoint) && level.getBlockEntity(endpoint) instanceof DockBlockEntity dock
			&& dock.isOwnedBy(job.route().owner()) ? dock : null;
	}

	private static int containerRoom(Container target, ItemStack stack) {
		if (target instanceof CarrierCargoContainerBlockEntity cargo) return cargo.indexedRoom(stack);
		int room = 0;
		for (int slot = 0; slot < target.getContainerSize(); slot++) {
			ItemStack present = target.getItem(slot);
			if (target.canPlaceItem(slot, stack) && (present.isEmpty() || ItemStack.isSameItemSameComponents(present, stack)))
				room += Math.max(0, target.getMaxStackSize(stack) - present.getCount());
		}
		return room;
	}

	static void returnToContainer(Container target, ItemStack stack) {
		if (target instanceof CarrierCargoContainerBlockEntity cargo) {
			cargo.indexedInsert(stack);
			return;
		}
		for (int slot = 0; slot < target.getContainerSize() && !stack.isEmpty(); slot++) {
			ItemStack present = target.getItem(slot);
			if (!target.canPlaceItem(slot, stack) || (!present.isEmpty() && !ItemStack.isSameItemSameComponents(present, stack))) continue;
			int moved = Math.min(stack.getCount(), Math.max(0, target.getMaxStackSize(stack) - present.getCount()));
			if (moved == 0) continue;
			if (present.isEmpty()) target.setItem(slot, stack.copyWithCount(moved)); else present.grow(moved);
			stack.shrink(moved);
		}
		target.setChanged();
	}

	private static boolean allowed(Job job, ItemStack stack) {
		return !stack.isEmpty() && DockSupplyPolicy.itemId(stack).equals(job.item()) && DockSupplyPolicy.kind(stack) == job.kind();
	}

	static boolean cargoMatches(Job job, List<ItemStack> cargo) {
		int count = 0;
		for (ItemStack stack : cargo) {
			if (stack.isEmpty()) continue;
			if (!allowed(job, stack)) return false;
			count += stack.getCount();
		}
		return count == job.remaining();
	}

	private static Status endpoints(ServerLevel level, Route route) {
		Status target = targetStatus(level, route);
		if (target != Status.READY) return target;
		BlockPos pos = BlockPos.of(route.source());
		if (!level.hasChunkAt(pos)) return Status.WAIT_CHUNK;
		return source(level, pos, route.owner()) == null ? Status.SOURCE_LOST : Status.READY;
	}

	private static Status targetStatus(ServerLevel level, Route route) {
		if (!dimension(level).equals(route.dimension())) return Status.ACCESS_DENIED;
		BlockPos pos = BlockPos.of(route.dock());
		if (!level.hasChunkAt(pos)) return Status.WAIT_CHUNK;
		return dock(level, route) == null ? Status.DOCK_LOST : Status.READY;
	}

	private static DockBlockEntity dock(ServerLevel level, Route route) {
		BlockPos pos = BlockPos.of(route.dock());
		return dimension(level).equals(route.dimension()) && level.hasChunkAt(pos)
			&& level.getBlockEntity(pos) instanceof DockBlockEntity dock && dock.isOwnedBy(route.owner()) ? dock : null;
	}

	private static Container source(ServerLevel level, BlockPos pos, UUID owner) {
		if (!level.hasChunkAt(pos)) return null;
		var entity = level.getBlockEntity(pos);
		if (entity instanceof CarrierCargoContainerBlockEntity cargo)
			return cargo.logisticsAccessible(owner) ? cargo : null;
		// Whitelist local storage only: never service/recovery buffers or remote/virtual inventories.
		return (entity instanceof ChestBlockEntity || entity instanceof BarrelBlockEntity || entity instanceof ShulkerBoxBlockEntity)
			&& entity instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity base && !base.isLocked()
			&& entity instanceof Container container && container.getContainerSize() <= SupplyNetworkPolicy.MAX_SOURCE_SLOTS
			? container : null;
	}

	private static int count(Container container, String item) {
		if (container instanceof CarrierCargoContainerBlockEntity cargo) return cargo.indexedCount(item);
		if (!supportedSourceSize(container)) return 0;
		int count = 0;
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (DockSupplyPolicy.itemId(stack).equals(item) && container.canTakeItem(container, slot, stack)) count += stack.getCount();
		}
		return count;
	}

	private static boolean supportedSourceSize(Container container) {
		return container instanceof CarrierCargoContainerBlockEntity
			|| container.getContainerSize() <= SupplyNetworkPolicy.MAX_SOURCE_SLOTS;
	}

	private static int[] sourceSlots(Container source, String item) {
		if (source instanceof CarrierCargoContainerBlockEntity cargo) return cargo.indexedSlots(item);
		int[] result = new int[source.getContainerSize()];
		for (int slot = 0; slot < result.length; slot++) result[slot] = slot;
		return result;
	}

	private static List<SourceStock> sourceStocks(Container source) {
		java.util.LinkedHashMap<String, SourceStock> stocks = new java.util.LinkedHashMap<>();
		for (int slot = 0; slot < source.getContainerSize(); slot++) {
			ItemStack stack = source.getItem(slot);
			DockSupplyPolicy.SupplyKind kind = DockSupplyPolicy.kind(stack);
			if (kind == null || !source.canTakeItem(source, slot, stack)) continue;
			String item = DockSupplyPolicy.itemId(stack);
			stocks.compute(item, (ignored, current) -> current == null
				? new SourceStock(item, kind, stack.getCount(), stack.copyWithCount(1))
				: new SourceStock(item, kind, current.count() + stack.getCount(), current.sample()));
		}
		return List.copyOf(stocks.values());
	}

	private static int room(DockBlockEntity dock, ItemStack stack) {
		if (dock == null || stack.isEmpty()) return 0;
		int count = 0;
		for (int slot = DockBlockEntity.SUPPLY_BUFFER_START; slot <= DockBlockEntity.SUPPLY_BUFFER_END; slot++) {
			ItemStack present = dock.getItem(slot);
			if (dock.canPlaceItem(slot, stack) && (present.isEmpty() || ItemStack.isSameItemSameComponents(present, stack)))
				count += Math.max(0, Math.min(stack.getMaxStackSize(), dock.getMaxStackSize(stack)) - present.getCount());
		}
		return count;
	}

	public static List<DockSummary> summary(ServerLevel level, UUID owner) {
		SupplyNetworkRegistry registry = SupplyNetworkSavedData.get(level).registry();
		return registry.configurations().stream().filter(c -> c.route().owner().equals(owner)
			&& c.route().dimension().equals(dimension(level))).map(c -> {
			DockBlockEntity dock = dock(level, c.route());
			List<StockSummary> stock = c.rules().stream().map(r -> {
				int actual = dock == null ? -1 : dock.effectiveSupplyCount(r.kind());
				int incoming = registry.incoming(c.route(), r.kind());
				return new StockSummary(r.kind(), r.minimum(), r.target(), r.priority(), actual, incoming,
					actual < 0 ? -1 : SupplyNetworkPolicy.demand(r.minimum(), r.target(), actual, incoming,
						registry.refilling(c.route(), r.kind())));
			}).toList();
			return new DockSummary(c, registry.status(c.route()), stock, registry.jobs().stream()
				.filter(j -> j.route().owner().equals(owner) && j.route().dock() == c.route().dock()).toList());
		}).toList();
	}

	private static void notifyChanges(ServerLevel level, SupplyNetworkSavedData data) {
		data.notices.keySet().retainAll(data.registry().configurations().stream().map(Configuration::route).toList());
		data.ownerNoticeTicks.keySet().retainAll(data.registry().configurations().stream().map(c -> c.route().owner()).toList());
		for (Configuration config : data.registry().configurations()) {
			Status status = data.registry().status(config.route());
			SupplyNetworkSavedData.Notice previous = data.notices.get(config.route());
			boolean automaticSourceWithoutNodes = config.route().source() == Route.AUTO_SOURCE
				&& data.nodes(config.route().owner(), config.route().dimension()).isEmpty();
			if (!SupplyNetworkPolicy.operatorNoticeRequired(automaticSourceWithoutNodes, status)) {
				if (previous != null && previous.status() != status)
					data.notices.put(config.route(), new SupplyNetworkSavedData.Notice(status, previous.tick()));
				continue;
			}
			if (previous != null && (previous.status() == status
				|| level.getGameTime() - previous.tick() < SupplyNetworkPolicy.NOTICE_INTERVAL)) continue;
			ServerPlayer owner = level.getServer().getPlayerList().getPlayer(config.route().owner());
			if (owner == null || !CarrierDroneServiceAdapter.ownerSupportsExterior(level, owner)) continue;
			Long lastNotice = data.ownerNoticeTicks.get(owner.getUUID());
			if (lastNotice != null && level.getGameTime() - lastNotice < SupplyNetworkPolicy.NOTICE_INTERVAL) continue;
			owner.sendSystemMessage(Component.literal("[MORROWGEAR] SUPPLY / "
				+ DockBlockEntity.idFor(BlockPos.of(config.route().dock())) + " / " + status.name().replace('_', ' ')));
			data.notices.put(config.route(), new SupplyNetworkSavedData.Notice(status, level.getGameTime()));
			data.ownerNoticeTicks.put(owner.getUUID(), level.getGameTime());
		}
	}

	private static String dimension(ServerLevel level) { return level.dimension().identifier().toString(); }
	private static double square(double value) { return value * value; }
	private record Demand(Configuration config, Rule rule, int missing) {}
	private record SourceStock(String item, DockSupplyPolicy.SupplyKind kind, int count, ItemStack sample) {}
	private record SourceChoice(BlockPos position, Container container, ItemStack stack, int movable,
		int priority, double distance) {}
	public record StockSummary(SupplyKind kind, int minimum, int target, int priority, int stock, int incoming, int missing) {}
	public record DockSummary(Configuration configuration, Status status, List<StockSummary> stock, List<Job> jobs) {}
}
