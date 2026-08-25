package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class FieldOperationRegistryTest {
	@AfterEach
	void clearRegistry() {
		FieldOperationRegistry.clear();
	}

	@Test
	void sharesOneOperationAcrossRolesAndTracksWork() {
		UUID owner = UUID.randomUUID();
		BlockPos anchor = new BlockPos(10, 40, -5);
		FieldOperationRegistry.Operation scout = FieldOperationRegistry.acquire(
			"overworld", owner, "OP-1", FieldOperationType.ORE, anchor, 24, 1L);
		FieldOperationRegistry.Operation engineer = FieldOperationRegistry.acquire(
			"overworld", owner, "OP-1", FieldOperationType.ORE, anchor, 24, 2L);
		assertTrue(scout == engineer);
		BlockPos ore = new BlockPos(12, 20, -4);
		scout.addTarget(ore);
		assertEquals(ore, engineer.claimTarget(3L));
		engineer.completeTarget(ore, 4L);
		FieldOperationRegistry.Snapshot snapshot = scout.snapshot(5L);
		assertEquals(1, snapshot.found());
		assertEquals(1, snapshot.processed());
		assertEquals(0, snapshot.workRemaining());
	}

	@Test
	void scanCursorIsSharedAndFinite() {
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "SCAN", FieldOperationType.FORESTRY,
			BlockPos.ZERO, 6, 1L);
		BlockPos first = operation.nextScanPos(2L);
		BlockPos second = operation.nextScanPos(2L);
		assertNotNull(first);
		assertNotNull(second);
		assertFalse(first.equals(second));
		BlockPos current = second;
		while (current != null) current = operation.nextScanPos(3L);
		assertNull(operation.nextScanPos(4L));
		assertTrue(operation.snapshot(5L).scanComplete());
	}

	@Test
	void excavationTargetsArePublishedBySurveyInsteadOfPreloaded() {
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "DIG", FieldOperationType.EXCAVATE,
			new BlockPos(0, 64, 0), 2, 1L);
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(2L);
		assertFalse(snapshot.scanComplete());
		assertEquals(0, snapshot.found());
	}

	@Test
	void excavationStartsAtTheSurfaceCenterBeforeOuterOrDeeperBlocks() {
		BlockPos anchor = new BlockPos(10, 64, 10);
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "DIG-ORDER", FieldOperationType.EXCAVATE,
			anchor, 2, 1L);
		operation.addTarget(anchor, 0);
		operation.addTarget(anchor.below(), 1);
		assertEquals(anchor, operation.claimTarget(2L));
	}

	@Test
	void surveyDataLinkTracksAirSolidAndFluidSamples() {
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "INTEL", FieldOperationType.ORE,
			BlockPos.ZERO, 12, 1L);
		operation.recordSurveySample(true, false, 2L);
		operation.recordSurveySample(false, false, 2L);
		operation.recordSurveySample(false, true, 2L);
		FieldOperationRegistry.Snapshot snapshot = operation.snapshot(3L);
		assertEquals(1, snapshot.surveyedAir());
		assertEquals(2, snapshot.surveyedSolid());
		assertEquals(1, snapshot.surveyedFluid());
	}

	@Test
	void forestryPrioritizesNearbyLogsBeforeLeavesAndRemoteLogs() {
		BlockPos anchor = new BlockPos(0, 64, 0);
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "FOREST-ORDER", FieldOperationType.FORESTRY,
			anchor, 6, 1L);
		BlockPos nearbyLog = anchor.above();
		BlockPos remoteLog = anchor.offset(6, 0, 6);
		BlockPos nearbyLeaf = anchor.offset(1, 0, 0);
		operation.addTarget(nearbyLeaf, 1);
		operation.addTarget(remoteLog, 0);
		operation.addTarget(nearbyLog, 0);
		assertEquals(nearbyLog, operation.claimTarget(2L));
		assertEquals(remoteLog, operation.claimTarget(3L));
		assertEquals(nearbyLeaf, operation.claimTarget(4L));
	}

	@Test
	void activeTreeTargetsCanBePromotedAheadOfOtherNearbyTrees() {
		BlockPos anchor = new BlockPos(0, 64, 0);
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "FOREST-TREE", FieldOperationType.FORESTRY,
			anchor, 6, 1L);
		BlockPos otherTree = anchor.offset(1, 0, 0);
		BlockPos connectedUpperTrunk = anchor.above(3);
		operation.addTarget(otherTree, 0);
		operation.addTarget(connectedUpperTrunk, 0);
		operation.promoteTarget(connectedUpperTrunk, -20, 2L);
		assertEquals(connectedUpperTrunk, operation.claimTarget(3L));
		assertEquals(otherTree, operation.claimTarget(4L));
	}

	@Test
	void deferredUnloadedPositionsPreventFalseCompletionAndAreRetried() {
		FieldOperationRegistry.Operation operation = FieldOperationRegistry.acquire(
			"overworld", UUID.randomUUID(), "DEFER", FieldOperationType.FORESTRY,
			BlockPos.ZERO, 6, 1L);
		BlockPos deferred = operation.nextScanPos(2L);
		operation.retryScanPos(deferred, 2L);
		BlockPos current;
		do current = operation.nextScanPos(3L); while (current != null && !current.equals(deferred));
		assertEquals(deferred, current);
		assertFalse(operation.snapshot(4L).scanComplete());
		assertNull(operation.nextScanPos(5L));
		assertTrue(operation.snapshot(6L).scanComplete());
	}

	@Test
	void sameOrderIdWithDifferentSpecificationReplacesStaleRegistry() {
		UUID owner = UUID.randomUUID();
		FieldOperationRegistry.Operation first = FieldOperationRegistry.acquire(
			"overworld", owner, "REUSED", FieldOperationType.ORE, BlockPos.ZERO, 12, 1L);
		FieldOperationRegistry.Operation same = FieldOperationRegistry.acquire(
			"overworld", owner, "REUSED", FieldOperationType.ORE, BlockPos.ZERO, 12, 2L);
		FieldOperationRegistry.Operation replacement = FieldOperationRegistry.acquire(
			"overworld", owner, "REUSED", FieldOperationType.FORESTRY, new BlockPos(32, 64, 32), 24, 3L);
		assertTrue(first == same);
		assertFalse(first == replacement);
		assertEquals(FieldOperationType.FORESTRY, replacement.snapshot(4L).type());
		assertEquals(new BlockPos(32, 64, 32), replacement.snapshot(4L).anchor());
		assertEquals(24, replacement.snapshot(4L).radius());
	}
}
