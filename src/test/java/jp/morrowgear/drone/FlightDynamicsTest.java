package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class FlightDynamicsTest {
	@Test
	void cruiseSpeedTransitionsSmoothlyThroughCatchUpRange() {
		double before = FlightDynamics.speedLimit(11.9, false, false);
		double after = FlightDynamics.speedLimit(12.1, false, false);
		assertTrue(after > before);
		assertTrue(after - before < 0.02);
		assertTrue(FlightDynamics.speedLimit(20.0, false, false) > after);
		assertEquals(0.96 * 0.85, FlightDynamics.speedLimit(40.0, false, false), 0.0001);
		assertEquals(0.44 * 0.85, FlightDynamics.speedLimit(2.0, false, false), 0.0001);
	}

	@Test
	void dockingUsesStableApproachAndFinalLimits() {
		assertEquals(1.08 * 0.85, FlightDynamics.speedLimit(30.0, true, false), 0.0001);
		assertTrue(FlightDynamics.speedLimit(12.0, true, false) > 0.238);
		assertTrue(FlightDynamics.speedLimit(12.0, true, false) < 0.918);
		assertEquals(0.28 * 0.85, FlightDynamics.speedLimit(5.0, true, false), 0.0001);
		assertEquals(0.14 * 0.85, FlightDynamics.speedLimit(30.0, true, true), 0.0001);
	}

	@Test
	void steeringAcceleratesWithoutInstantVelocityJump() {
		Vec3 next = FlightDynamics.steer(Vec3.ZERO, Vec3.ZERO, new Vec3(10, 0, 0), Vec3.ZERO, 0.5, false);
		assertTrue(next.x > 0.0);
		assertTrue(next.x < 0.5);
		assertEquals(0.0, next.y, 0.0001);
		assertEquals(0.0, next.z, 0.0001);
	}

	@Test
	void mechanicalBrakeSettlesWithoutHoverOscillation() {
		Vec3 velocity = new Vec3(0.2, 0.08, -0.1);
		for (int i = 0; i < 8; i++) velocity = FlightDynamics.brake(velocity);
		assertEquals(Vec3.ZERO, velocity);
	}

	@Test
	void routeSteeringDoesNotTreatANearbyWaypointAsTheFinalDestination() {
		Vec3 normal = Vec3.ZERO;
		Vec3 routed = Vec3.ZERO;
		for (int tick = 0; tick < 12; tick++) {
			normal = FlightDynamics.steer(normal, Vec3.ZERO,
				new Vec3(2.0, 0, 0), Vec3.ZERO, 0.72, false);
			routed = FlightDynamics.steerRoute(routed, Vec3.ZERO,
				new Vec3(2.0, 0, 0), Vec3.ZERO, 0.72, 30.0);
		}

		assertTrue(routed.length() > normal.length() * 1.5);
		assertTrue(routed.length() < 0.72);
	}

	@Test
	void convergingUnitMatchesLeaderVelocityNearItsSlot() {
		Vec3 leaderVelocity = new Vec3(0.38, 0.0, 0.0);
		Vec3 result = Vec3.ZERO;
		for (int tick = 0; tick < 12; tick++) {
			result = FlightDynamics.steerConverging(result, Vec3.ZERO,
				new Vec3(2.0, 0.0, 0.0), Vec3.ZERO, 0.55, leaderVelocity);
		}

		assertTrue(result.x > 0.30);
		assertTrue(result.x <= 0.55);
	}

	@Test
	void predictiveRouteLimitsHorizontalAndVerticalAcceleration() {
		Vec3 current = new Vec3(0.7, 0.05, 0.0);
		Vec3 next = FlightDynamics.steerPredictiveRoute(current, Vec3.ZERO,
			new Vec3(0, 30, 30), Vec3.ZERO, 0.96, 60.0);
		Vec3 change = next.subtract(current);
		assertTrue(change.multiply(1, 0, 1).length() <= 0.0551);
		assertTrue(Math.abs(change.y) <= 0.0321);
	}

	@Test
	void combatBreakawayCannotCauseASuddenSpeedJump() {
		Vec3 current = new Vec3(0.18, 0.0, 0.0);
		Vec3 next = FlightDynamics.steerCombatBreakaway(current, Vec3.ZERO,
			new Vec3(30, 12, 0), 1.14);
		assertTrue(next.subtract(current).length() <= 0.0851);
		assertTrue(next.length() <= 1.14);
	}

	@Test
	void movingCombatSlotCannotReverseVelocityAbruptly() {
		Vec3 current = new Vec3(0.9, 0.0, 0.0);
		Vec3 next = FlightDynamics.steerMovingOrbit(current, Vec3.ZERO,
			new Vec3(-20, 4, 0), new Vec3(-0.9, 0.0, 0.0), 0.92);

		assertTrue(next.subtract(current).length() <= 0.1201);
		assertTrue(next.length() <= 0.92);
	}

	@Test
	void lowerSpeedLimitDeceleratesInsteadOfClippingVelocity() {
		Vec3 current = new Vec3(0.9, 0.0, 0.0);
		Vec3 next = FlightDynamics.steer(current, Vec3.ZERO, new Vec3(5, 0, 0),
			Vec3.ZERO, 0.119, true);
		assertTrue(next.x > 0.8);
		assertTrue(next.subtract(current).length() <= FlightDynamics.HORIZONTAL_ACCELERATION + 1e-9);
		for (int tick = 0; tick < 80; tick++) {
			next = FlightDynamics.steer(next, Vec3.ZERO, new Vec3(5, 0, 0), Vec3.ZERO, 0.119, true);
		}
		assertEquals(0.119, next.x, 1e-6);
	}

	@Test
	void motionBoundsAccelerationAndJerkAcrossFlightModes() {
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		Vec3 velocity = Vec3.ZERO;
		Vec3 acceleration = Vec3.ZERO;
		for (int tick = 0; tick < 360; tick++) {
			Vec3 requested = switch (tick / 60) {
				case 0 -> FlightDynamics.steer(velocity, Vec3.ZERO, new Vec3(0, 8, 0), Vec3.ZERO, 0.4, false);
				case 1 -> FlightDynamics.steerRoute(velocity, Vec3.ZERO, new Vec3(20, 8, 15), Vec3.ZERO, 0.816, 40);
				case 2 -> FlightDynamics.steerConverging(velocity, Vec3.ZERO, new Vec3(-3, 0, 0), Vec3.ZERO, 0.7, new Vec3(-0.4, 0, 0));
				case 3 -> FlightDynamics.steerLaserFormation(velocity, Vec3.ZERO, new Vec3(0, -2, 1), new Vec3(0, 0, -0.3), 0.46);
				case 4 -> FlightDynamics.steer(velocity, Vec3.ZERO, new Vec3(0, -1, 0), Vec3.ZERO, 0.119, true);
				default -> FlightDynamics.brake(velocity);
			};
			Vec3 next = motion.step(velocity, requested, tick, false);
			Vec3 nextAcceleration = next.subtract(velocity);
			Vec3 jerk = nextAcceleration.subtract(acceleration);
			assertTrue(nextAcceleration.horizontalDistance() <= FlightDynamics.HORIZONTAL_ACCELERATION + 1e-9);
			assertTrue(Math.abs(nextAcceleration.y) <= FlightDynamics.VERTICAL_ACCELERATION + 1e-9);
			assertTrue(jerk.horizontalDistance() <= FlightDynamics.HORIZONTAL_JERK + 1e-9);
			assertTrue(Math.abs(jerk.y) <= FlightDynamics.VERTICAL_JERK + 1e-9);
			velocity = next;
			acceleration = nextAcceleration;
		}
		assertEquals(0.0, velocity.length(), 1e-10);
	}

	@Test
	void landingReachesLowSpeedTouchdownWithoutPerpetualAsymptote() {
		for (Vec3 start : new Vec3[] {new Vec3(0, 5, 0), new Vec3(1.55, 0.75, 0)}) {
			for (double drag : new double[] {1.0, 0.91}) {
				FlightDynamics.Motion motion = new FlightDynamics.Motion();
				Vec3 position = start;
				Vec3 velocity = new Vec3(0.18, -0.05, 0.03);
				boolean landed = false;
				for (int tick = 0; tick < 400; tick++) {
					Vec3 requested = FlightDynamics.steer(velocity, position, Vec3.ZERO, Vec3.ZERO, 0.119, true);
					velocity = motion.step(velocity, requested, tick, false);
					if (FlightDynamics.touchdownReady(position.length(), velocity)) {
						landed = true;
						break;
					}
					position = position.add(velocity);
					velocity = velocity.multiply(drag, drag == 1.0 ? 1.0 : 0.98, drag);
				}
				assertTrue(landed, "Unsettled landing from " + start + " drag=" + drag + " at " + position);
			}
		}
		assertFalse(FlightDynamics.touchdownReady(0.02, new Vec3(0.12, -0.2, 0)));
		assertFalse(FlightDynamics.touchdownReady(0.2, Vec3.ZERO));
	}

	@Test
	void avoidanceRemainsActiveAtTheNavigationTargetAndMatchedFormationSlot() {
		Vec3 away = new Vec3(-0.18, 0.08, 0);
		assertTrue(FlightDynamics.steer(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, away, 0.4, false).dot(away) > 0);
		assertTrue(FlightDynamics.steerConverging(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, away, 0.4, Vec3.ZERO).dot(away) > 0);
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		motion.step(Vec3.ZERO, new Vec3(0.5, 0, 0), 0, false);
		Vec3 urgent = FlightDynamics.steerUrgent(new Vec3(0.5, 0, 0), Vec3.ZERO,
			new Vec3(-8, 6, 0), Vec3.ZERO, 0.8);
		assertTrue(urgent.x < 0);
		assertTrue(urgent.y > 0);
		assertEquals(urgent, motion.step(new Vec3(0.5, 0, 0), urgent, 1, true));
		assertEquals(Vec3.ZERO, motion.step(urgent, Vec3.ZERO, 2, true));
		Vec3 resumed = motion.step(Vec3.ZERO, new Vec3(1, 0, 0), 3, false);
		assertTrue(resumed.x <= FlightDynamics.HORIZONTAL_JERK);
	}

	@Test
	void invalidInputsCannotPoisonSubsequentFlightTicks() {
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		Vec3 invalid = new Vec3(Double.NaN, Double.POSITIVE_INFINITY, 0);
		assertEquals(Vec3.ZERO, motion.step(invalid, invalid, 0, false));
		assertEquals(Vec3.ZERO, FlightDynamics.brake(invalid));
		assertEquals(Vec3.ZERO, FlightDynamics.steer(invalid, Vec3.ZERO, invalid, Vec3.ZERO, Double.NaN, false));
		assertEquals(Vec3.ZERO, FlightDynamics.steerMovingOrbit(invalid, Vec3.ZERO, invalid, invalid, Double.NaN));
		assertFalse(FlightDynamics.touchdownReady(Double.NaN, Vec3.ZERO));
		assertFalse(FlightDynamics.touchdownReady(0.0, invalid));
		assertTrue(Double.isFinite(FlightDynamics.speedLimit(Double.NaN, false, false)));
		assertTrue(motion.step(Vec3.ZERO, new Vec3(0.1, 0, 0), 1, false).x > 0);
	}

	@Test
	void laserFormationTracksAnOrbitWithoutUnboundedSlotCorrection() {
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		Vec3 position = new Vec3(10, 2, 0);
		Vec3 velocity = Vec3.ZERO;
		double lastError = Double.MAX_VALUE;
		for (int tick = 0; tick < 600; tick++) {
			double angle = tick * 0.024;
			Vec3 slot = new Vec3(Math.cos(angle) * 10, 2, Math.sin(angle) * 10);
			Vec3 nextSlot = new Vec3(Math.cos(angle + 0.024) * 10, 2, Math.sin(angle + 0.024) * 10);
			velocity = motion.step(velocity, FlightDynamics.steerLaserFormation(velocity, position,
				slot, nextSlot.subtract(slot), 0.46), tick, false);
			position = position.add(velocity);
			velocity = velocity.multiply(0.91, 0.98, 0.91);
			lastError = position.distanceTo(nextSlot);
		}
		assertTrue(lastError < 2.0, "Orbit error=" + lastError);
	}

	@Test
	void wideDockReturnCentersAboveRaisedRimAndFinishesWithAlignedYaw() {
		for (boolean wide : new boolean[] {false, true}) {
			double deck = wide ? 5.048 / 16.0 : 5.5 / 16.0;
			Vec3 target = new Vec3(0, wide ? 0.316 : 0.344, 0);
			AABB[] blocks = wide ? new AABB[] {
				new AABB(-1.5, 0, -1.5, 1.5, deck, 1.5),
				new AABB(-2.5, 0, -2.5, -1.5, 6.65 / 16.0, 2.5),
				new AABB(1.5, 0, -2.5, 2.5, 6.65 / 16.0, 2.5),
				new AABB(-1.5, 0, -2.5, 1.5, 6.65 / 16.0, -1.5),
				new AABB(-1.5, 0, 1.5, 1.5, 6.65 / 16.0, 2.5)
			} : new AABB[] {new AABB(-1.5, 0, -1.5, 1.5, deck, 1.5)};
			for (Vec3 start : new Vec3[] {target.add(1.55, 0.75, 0.04), target.add(-0.3, 5, 0.2)}) {
				Vec3 position = start;
				Vec3 velocity = new Vec3(0.02, -0.07, 0.01);
				FlightDynamics.Motion motion = new FlightDynamics.Motion();
				float yaw = 170;
				boolean landed = false;
				for (int tick = 0; tick < 800; tick++) {
					AABB body = new AABB(position.x - 1.5, position.y, position.z - 1.5,
						position.x + 1.5, position.y + 0.75, position.z + 1.5);
					Vec3 requested = FlightDynamics.steer(velocity, position, target, Vec3.ZERO, 0.119, true);
					Vec3 next = motion.step(velocity, requested, tick, false);
					Vec3 safe = FlightDynamics.collisionSafeVelocity(next, requested, true,
						step -> clearOfBlocks(FlightDynamics.landingSweep(body, step), blocks));
					if (safe != next) safe = motion.step(velocity, safe, tick, true);
					assertTrue(clearOfBlocks(FlightDynamics.landingSweep(body, safe), blocks));
					yaw = motion.heading(yaw, 0, safe, tick);
					if (FlightDynamics.touchdownReady(position.distanceTo(target), safe)
						&& Math.abs(yaw) < 0.75f
						&& clearOfBlocks(FlightDynamics.landingSweep(body, target.subtract(position)), blocks)) {
						landed = true;
						break;
					}
					position = position.add(safe);
					velocity = safe.multiply(0.91, 0.98, 0.91);
				}
				assertTrue(landed, "Dock return stalled: wide=" + wide + " position=" + position + " yaw=" + yaw);
			}
		}
	}

	private static boolean clearOfBlocks(AABB swept, AABB[] blocks) {
		for (AABB block : blocks) if (swept.intersects(block)) return false;
		return true;
	}

	@Test
	void landingSweepAllowsDeckClearanceButRejectsRealBlockIntersection() {
		AABB body = new AABB(-1.5, 0.34, -1.5, 1.5, 1.09, 1.5);
		AABB floor = new AABB(-1.5, 0, -1.5, 1.5, 0.3155, 1.5);
		assertFalse(FlightDynamics.landingSweep(body, new Vec3(0, -0.024, 0)).intersects(floor));
		assertTrue(FlightDynamics.landingSweep(body, new Vec3(0, -0.04, 0)).intersects(floor));
		Vec3 blocked = new Vec3(0.01, -0.04, 0);
		Vec3 slide = FlightDynamics.collisionSafeVelocity(blocked, blocked, true,
			step -> !FlightDynamics.landingSweep(body, step).intersects(floor));
		assertEquals(0.0, new Vec3(0.01, 0, 0).distanceTo(slide), 1e-12);
	}

	@Test
	void headingHistorySurvivesEntityPreviousYawBeingResetEachTick() {
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		float yaw = 0;
		float previousRate = 0;
		for (int tick = 0; tick < 15; tick++) {
			float next = motion.heading(yaw, 140, Vec3.ZERO, tick);
			float rate = next - yaw;
			assertTrue(Math.abs(rate - previousRate) <= 0.6501f);
			previousRate = rate;
			yaw = next;
		}
		assertTrue(yaw > 25, "Yaw history must not limit every tick to the initial acceleration step");
		float restarted = motion.heading(yaw, 140, Vec3.ZERO, 100);
		assertEquals(0.65f, restarted - yaw, 0.0001f);
	}

	@Test
	void dockRimContactReproducesTheObservedUnboundedRecoverySpike() {
		Vec3 deck = new Vec3(0, 0.316, 0);
		FlightDynamics.Motion legacyLaunch = new FlightDynamics.Motion();
		Vec3 first = legacyLaunch.step(Vec3.ZERO, FlightDynamics.steer(Vec3.ZERO, deck,
			deck.add(12, 6, 0), Vec3.ZERO, 0.5, false), 0, false);
		AABB body = new AABB(-1.5, deck.y, -1.5, 1.5, deck.y + 0.75, 1.5);
		AABB rim = new AABB(1.5, 0, -2.5, 2.5, 6.65 / 16.0, 2.5);
		assertTrue(FlightDynamics.landingSweep(body, first).intersects(rim));

		Vec3 afterContact = new Vec3(0, first.y * 0.98, 0);
		Vec3 unbounded = FlightDynamics.steerUrgent(afterContact, Vec3.ZERO,
			new Vec3(4.6, 2.4, 0), Vec3.ZERO, 0.5).multiply(0.91, 0.98, 0.91);
		Vec3 delta = unbounded.subtract(afterContact);
		assertEquals(0.276192137, delta.length(), 1e-8);
		assertEquals(0.272139002, delta.subtract(afterContact).length(), 1e-8);

		Vec3 departure = FlightDynamics.dockDepartureTarget(deck, deck);
		Vec3 bounded = new FlightDynamics.Motion().step(Vec3.ZERO,
			FlightDynamics.steer(Vec3.ZERO, deck, departure, Vec3.ZERO, 0.14, false), 0,
			FlightDynamics.requiresSafetyRecovery(true, true, true, false, true));
		assertFalse(FlightDynamics.landingSweep(body, bounded).intersects(rim));
		assertEquals(0.0, bounded.horizontalDistance(), 1e-12);
		assertTrue(bounded.y > 0 && bounded.y <= FlightDynamics.VERTICAL_JERK);
	}

	@Test
	void groundAndDepartureContactsAreNotEmergencyAccelerationButHazardsStillAre() {
		assertFalse(FlightDynamics.requiresSafetyRecovery(false, true, true, false, false));
		assertFalse(FlightDynamics.requiresSafetyRecovery(true, false, false, false, true));
		// A cached escape route can still be active after its actual contact has cleared.
		for (int escapeTicks = 28; escapeTicks > 0; escapeTicks--) {
			assertFalse(FlightDynamics.requiresSafetyRecovery(false, false, false, false, false));
		}
		assertTrue(FlightDynamics.requiresSafetyRecovery(true, false, true, false, false));
		assertTrue(FlightDynamics.requiresSafetyRecovery(false, true, false, false, false));
		assertTrue(FlightDynamics.requiresSafetyRecovery(false, false, false, true, true));
	}

	@Test
	void departureGateEndsAboveRimAndNeverCapturesADistantAircraft() {
		Vec3 deck = new Vec3(2, 120.316, 3);
		assertEquals(deck.add(0, 0.55, 0), FlightDynamics.dockDepartureTarget(deck, deck));
		assertNull(FlightDynamics.dockDepartureTarget(deck.add(0, 0.25, 0), deck));
		assertNull(FlightDynamics.dockDepartureTarget(deck.add(0.31, 0, 0), deck));
		assertNull(FlightDynamics.dockDepartureTarget(deck.add(0, -1, 0), deck));
		assertNull(FlightDynamics.dockDepartureTarget(new Vec3(Double.NaN, 0, 0), deck));
	}

	@Test
	void nativeDragTakeoffFollowAndDockDoNotInheritAnUnboundedEscapeWindow() {
		Vec3 deck = new Vec3(0, 0.316, 0);
		AABB[] blocks = {
			new AABB(-1.5, 0, -1.5, 1.5, 5.048 / 16.0, 1.5),
			new AABB(-2.5, 0, -2.5, -1.5, 6.65 / 16.0, 2.5),
			new AABB(1.5, 0, -2.5, 2.5, 6.65 / 16.0, 2.5),
			new AABB(-1.5, 0, -2.5, 1.5, 6.65 / 16.0, -1.5),
			new AABB(-1.5, 0, 1.5, 1.5, 6.65 / 16.0, 2.5)
		};
		FlightDynamics.Motion motion = new FlightDynamics.Motion();
		Vec3 position = deck;
		Vec3 velocity = Vec3.ZERO;
		Vec3 observedAcceleration = Vec3.ZERO;
		boolean finalApproach = false;
		boolean landed = false;
		double maxDelta = 0;
		double maxJerk = 0;
		for (int tick = 0; tick < 600; tick++) {
			boolean docking = tick >= 160;
			Vec3 target = docking ? deck.add(0, finalApproach ? 0 : 5, 0)
				: deck.add(tick < 80 ? 8 : -6, 6, 4);
			Vec3 departure = docking ? null : FlightDynamics.dockDepartureTarget(position, deck);
			boolean takingOff = departure != null;
			if (takingOff) target = departure;
			boolean safety = FlightDynamics.requiresSafetyRecovery(false, tick == 0,
				tick == 0, false, takingOff);
			assertFalse(safety);
			Vec3 current = new Vec3(Math.abs(velocity.x) < 0.003 ? 0 : velocity.x,
				Math.abs(velocity.y) < 0.003 ? 0 : velocity.y, Math.abs(velocity.z) < 0.003 ? 0 : velocity.z);
			double speed = takingOff ? 0.14 : FlightDynamics.speedLimit(position.distanceTo(target), docking, finalApproach);
			Vec3 requested = FlightDynamics.steer(current, position, target, Vec3.ZERO, speed, finalApproach);
			Vec3 next = motion.step(current, requested, tick, safety);
			AABB body = new AABB(position.x - 1.5, position.y, position.z - 1.5,
				position.x + 1.5, position.y + 0.75, position.z + 1.5);
			Vec3 safe = FlightDynamics.collisionSafeVelocity(next, requested, finalApproach,
				step -> clearOfBlocks(FlightDynamics.landingSweep(body, step), blocks));
			if (!finalApproach) assertTrue(safe == next, "Ordinary takeoff/follow/approach must not contact the rim");
			if (safe != next) safe = motion.step(current, safe, tick, true);
			assertTrue(clearOfBlocks(FlightDynamics.landingSweep(body, safe), blocks));
			if (finalApproach && FlightDynamics.touchdownReady(position.distanceTo(deck), safe)
				&& clearOfBlocks(FlightDynamics.landingSweep(body, deck.subtract(position)), blocks)) {
				landed = true;
				break;
			}
			Vec3 observed = safe.multiply(0.91, 0.98, 0.91);
			Vec3 delta = observed.subtract(velocity);
			if (!finalApproach) {
				maxDelta = Math.max(maxDelta, delta.length());
				maxJerk = Math.max(maxJerk, delta.subtract(observedAcceleration).length());
			}
			observedAcceleration = delta;
			velocity = observed;
			position = position.add(safe);
			if (docking && !finalApproach && position.distanceTo(target) <= 1.2) finalApproach = true;
			if (tick == 159) assertTrue(position.y > deck.y + 4.0);
		}
		assertTrue(landed, "Return must still finish after the smooth departure: " + position);
		// Diagnostics measure post-drag velocity, not the per-axis motor acceleration caps.
		assertTrue(maxDelta < 0.11, "Post-drag delta=" + maxDelta);
		assertTrue(maxJerk < 0.035, "Post-drag jerk=" + maxJerk);
	}
}
