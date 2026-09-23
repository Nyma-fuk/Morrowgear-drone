package jp.morrowgear.drone;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

final class FieldOperationRegistry {
	private static final long TTL_TICKS = 20L * 60L * 30L;
	private static final Map<Key, Operation> OPERATIONS = new HashMap<>();

	private FieldOperationRegistry() {
	}

	static synchronized Operation acquire(String dimension, UUID owner, String id,
		FieldOperationType type, BlockPos anchor, int radius, long now) {
		prune(now);
		Key key = new Key(dimension, owner, id);
		Operation existing = OPERATIONS.get(key);
		if (existing != null && existing.matches(type, anchor, radius)) {
			existing.touch(now);
			return existing;
		}
		Operation replacement = new Operation(type, anchor, radius, now);
		OPERATIONS.put(key, replacement);
		return replacement;
	}

	static synchronized Operation find(String dimension, UUID owner, String id, long now) {
		prune(now);
		Operation operation = OPERATIONS.get(new Key(dimension, owner, id));
		if (operation != null) operation.touch(now);
		return operation;
	}

	static synchronized void clear() { OPERATIONS.clear(); }

	private static void prune(long now) {
		OPERATIONS.values().removeIf(operation -> now - operation.lastAccessTick > TTL_TICKS);
	}

	static final class Operation {
		private final FieldOperationType type;
		private final BlockPos anchor;
		private final int radius;
		private final int minX;
		private final int minY;
		private final int minZ;
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final int scanTotal;
		private final Set<Long> discovered = new LinkedHashSet<>();
		private final Set<Long> deferredScan = new LinkedHashSet<>();
		private final PriorityQueue<WorkTarget> work = new PriorityQueue<>(Comparator
			.comparingInt(WorkTarget::priority)
			.thenComparingLong(WorkTarget::distance)
			.thenComparingLong(WorkTarget::packed));
		private final Map<Long, Integer> targetPriorities = new HashMap<>();
		private final Set<Long> claimed = new HashSet<>();
		private final Set<UUID> dropEntities = new HashSet<>();
		private final ArrayDeque<ReplantRequest> replant = new ArrayDeque<>();
		private final List<ItemStack> stockpile = new ArrayList<>();
		private int scanCursor;
		private int processed;
		private int replanted;
		private int surveyedAir;
		private int surveyedSolid;
		private int surveyedFluid;
		private boolean scanComplete;
		private long lastAccessTick;

		Operation(FieldOperationType type, BlockPos anchor, int requestedRadius, long now) {
			this.type = type;
			this.anchor = anchor.immutable();
			this.radius = DroneStatePolicy.fieldRadius(type, requestedRadius);
			minX = anchor.getX() - radius;
			minZ = anchor.getZ() - radius;
			sizeX = radius * 2 + 1;
			sizeZ = radius * 2 + 1;
			if (type == FieldOperationType.ORE) {
				minY = anchor.getY() - 32;
				sizeY = 65;
			} else if (type == FieldOperationType.FORESTRY) {
				// The map anchor is terrain-derived. Scan both below and above it so
				// tall jungle/spruce trunks and trees rooted below nearby slopes are included.
				minY = anchor.getY() - 32;
				sizeY = 65;
			} else {
				minY = anchor.getY() - 5;
				sizeY = 6;
			}
			scanTotal = sizeX * sizeY * sizeZ;
			lastAccessTick = now;
		}

		synchronized void recordSurveySample(boolean air, boolean fluid, long now) {
			touch(now);
			if (air) surveyedAir++; else surveyedSolid++;
			if (fluid) surveyedFluid++;
		}

		synchronized BlockPos nextScanPos(long now) {
			touch(now);
			if (scanComplete) return null;
			if (scanCursor >= scanTotal) {
				if (!deferredScan.isEmpty()) {
					Long packed = deferredScan.iterator().next();
					deferredScan.remove(packed);
					return BlockPos.of(packed);
				}
				scanComplete = true;
				return null;
			}
			int index = scanCursor++;
			int x = index % sizeX;
			int yz = index / sizeX;
			int z = yz % sizeZ;
			int y = yz / sizeZ;
			return new BlockPos(minX + x, minY + y, minZ + z);
		}

		synchronized void retryScanPos(BlockPos pos, long now) {
			touch(now);
			if (pos != null) deferredScan.add(pos.asLong());
			scanComplete = false;
		}

		synchronized void addTarget(BlockPos pos) {
			addTarget(pos, 0);
		}

		synchronized void addTarget(BlockPos pos, int priority) {
			long packed = pos.asLong();
			if (!discovered.add(packed)) return;
			targetPriorities.put(packed, priority);
			work.add(new WorkTarget(packed, priority, targetDistance(pos)));
		}

		synchronized BlockPos claimTarget(long now) {
			touch(now);
			WorkTarget target = work.poll();
			if (target == null) return null;
			claimed.add(target.packed());
			return BlockPos.of(target.packed());
		}

		synchronized void completeTarget(BlockPos pos, long now) {
			touch(now);
			if (claimed.remove(pos.asLong())) processed++;
		}

		synchronized void retryTarget(BlockPos pos, long now) {
			touch(now);
			long packed = pos.asLong();
			if (claimed.remove(packed)) {
				int priority = targetPriorities.getOrDefault(packed, 0);
				work.add(new WorkTarget(packed, priority, targetDistance(pos)));
			}
		}

		synchronized void promoteTarget(BlockPos pos, int priority, long now) {
			touch(now);
			long packed = pos.asLong();
			if (!discovered.contains(packed) || claimed.contains(packed)) return;
			int current = targetPriorities.getOrDefault(packed, 0);
			if (priority >= current) return;
			work.removeIf(target -> target.packed() == packed);
			targetPriorities.put(packed, priority);
			work.add(new WorkTarget(packed, priority, targetDistance(pos)));
		}

		private long targetDistance(BlockPos pos) {
			long dx = pos.getX() - anchor.getX();
			long dy = pos.getY() - anchor.getY();
			long dz = pos.getZ() - anchor.getZ();
			return dx * dx + dy * dy + dz * dz;
		}

		synchronized void addReplant(BlockPos pos, ItemStack sapling, long now) {
			touch(now);
			if (!sapling.isEmpty()) replant.addLast(new ReplantRequest(pos.immutable(), sapling.copyWithCount(1)));
		}

		synchronized ReplantRequest claimReplant(long now) {
			touch(now);
			return replant.pollFirst();
		}

		synchronized void completeReplant(long now) { touch(now); replanted++; }

		synchronized void registerDrop(UUID entityId, long now) {
			touch(now);
			if (entityId != null) dropEntities.add(entityId);
		}

		synchronized boolean ownsDrop(UUID entityId, long now) {
			touch(now);
			return entityId != null && dropEntities.contains(entityId);
		}

		synchronized void retryReplant(ReplantRequest request, long now) {
			touch(now);
			if (request != null) replant.addLast(request);
		}

		synchronized void addDrops(List<ItemStack> drops, long now) {
			touch(now);
			for (ItemStack incoming : drops) {
				if (incoming.isEmpty()) continue;
				ItemStack remaining = incoming.copy();
				for (ItemStack stored : stockpile) {
					if (!ItemStack.isSameItemSameComponents(stored, remaining)) continue;
					int moved = Math.min(remaining.getCount(), stored.getMaxStackSize() - stored.getCount());
					stored.grow(moved);
					remaining.shrink(moved);
					if (remaining.isEmpty()) break;
				}
				if (!remaining.isEmpty()) stockpile.add(remaining);
			}
		}

		synchronized List<ItemStack> takeDrops(int freeStacks, long now) {
			touch(now);
			List<ItemStack> result = new ArrayList<>();
			while (freeStacks-- > 0 && !stockpile.isEmpty()) result.add(stockpile.removeFirst());
			return result;
		}

		synchronized boolean consumeSapling(ItemStack sapling, long now) {
			touch(now);
			for (int i = 0; i < stockpile.size(); i++) {
				ItemStack stored = stockpile.get(i);
				if (!ItemStack.isSameItemSameComponents(stored, sapling)) continue;
				stored.shrink(1);
				if (stored.isEmpty()) stockpile.remove(i);
				return true;
			}
			return false;
		}

		synchronized Snapshot snapshot(long now) {
			touch(now);
			int stock = stockpile.stream().mapToInt(ItemStack::getCount).sum();
			int progress = scanComplete ? 100 : scanTotal == 0 ? 100 : Math.min(99, scanCursor * 100 / scanTotal);
			return new Snapshot(type, anchor, radius, progress, discovered.size(), processed,
				work.size() + claimed.size(), replant.size(), replanted, stock, scanComplete,
				scanComplete && work.isEmpty() && claimed.isEmpty() && replant.isEmpty(),
				surveyedAir, surveyedSolid, surveyedFluid);
		}

		private boolean matches(FieldOperationType requestedType, BlockPos requestedAnchor, int requestedRadius) {
			int normalizedRadius = DroneStatePolicy.fieldRadius(requestedType, requestedRadius);
			return type == requestedType && anchor.equals(requestedAnchor) && radius == normalizedRadius;
		}

		private void touch(long now) { lastAccessTick = now; }
	}

	record Snapshot(FieldOperationType type, BlockPos anchor, int radius, int scanProgress,
		int found, int processed, int workRemaining, int replantRemaining, int replanted,
		int stockItems, boolean scanComplete, boolean workComplete,
		int surveyedAir, int surveyedSolid, int surveyedFluid) {
	}

	record ReplantRequest(BlockPos pos, ItemStack sapling) {
	}

	private record WorkTarget(long packed, int priority, long distance) {
	}

	private record Key(String dimension, UUID owner, String id) {
	}
}
