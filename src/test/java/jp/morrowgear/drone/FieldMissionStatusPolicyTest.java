package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FieldMissionStatusPolicyTest {
	@Test
	void scoutTransitionsFromSurveyToOverwatchCargoEscortAndCompletion() {
		assertEquals(FieldOperationState.SCOUT_SURVEY,
			FieldMissionStatusPolicy.scout(false, true, false, true, false));
		assertEquals(FieldOperationState.SCOUT_OVERWATCH,
			FieldMissionStatusPolicy.scout(true, true, false, true, false));
		assertEquals(FieldOperationState.SCOUT_CARGO_ESCORT,
			FieldMissionStatusPolicy.scout(true, true, true, true, false));
		assertEquals(FieldOperationState.COMPLETE,
			FieldMissionStatusPolicy.scout(true, true, true, true, true));
	}

	@Test
	void scoutOnlyMissionCompletesAfterPublishingSurvey() {
		assertEquals(FieldOperationState.COMPLETE,
			FieldMissionStatusPolicy.scout(true, false, true, false, true));
	}

	@Test
	void cargoHoldsUntilWorkCreatesSomethingToCollect() {
		assertEquals(FieldOperationState.CARGO_HOLD,
			FieldMissionStatusPolicy.cargo(false, false, false, false, 0));
		assertEquals(FieldOperationState.COLLECTING,
			FieldMissionStatusPolicy.cargo(false, false, true, false, 0));
		assertEquals(FieldOperationState.DELIVERING,
			FieldMissionStatusPolicy.cargo(true, true, false, true, 0));
		assertEquals(FieldOperationState.CARGO_HOLD,
			FieldMissionStatusPolicy.cargo(true, true, false, false,
				FieldMissionStatusPolicy.CARGO_QUIET_TICKS - 1));
		assertEquals(FieldOperationState.COMPLETE,
			FieldMissionStatusPolicy.cargo(true, true, false, false,
				FieldMissionStatusPolicy.CARGO_QUIET_TICKS));
	}

	@Test
	void guardTransfersFromEngineerToCargoEscort() {
		assertEquals(FieldOperationState.GUARD_WORK_ESCORT,
			FieldMissionStatusPolicy.guard(true, false, true, false));
		assertEquals(FieldOperationState.GUARD_CARGO_ESCORT,
			FieldMissionStatusPolicy.guard(true, true, true, false));
		assertEquals(FieldOperationState.COMPLETE,
			FieldMissionStatusPolicy.guard(true, true, true, true));
	}
}
