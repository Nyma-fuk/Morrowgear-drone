package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class FieldOperationWorkBudgetTest {
	@Test void allScoutsShareOneOperationScanBudgetPerTick() {
		FieldOperationRegistry.Operation operation = new FieldOperationRegistry.Operation(
			FieldOperationType.ORE, BlockPos.ZERO, 32, 0);
		assertEquals(384, operation.claimScanBudget(10, 384, 512));
		assertEquals(128, operation.claimScanBudget(10, 384, 512));
		assertEquals(0, operation.claimScanBudget(10, 64, 512));
		assertEquals(384, operation.claimScanBudget(11, 384, 512));
	}
}
