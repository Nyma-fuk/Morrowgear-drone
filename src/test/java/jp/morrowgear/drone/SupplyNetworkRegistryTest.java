package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mojang.serialization.JsonOps;
import jp.morrowgear.drone.DockSupplyPolicy.SupplyKind;
import jp.morrowgear.drone.SupplyNetworkPolicy.Rule;
import jp.morrowgear.drone.SupplyNetworkPolicy.Status;
import jp.morrowgear.drone.SupplyNetworkRegistry.Configuration;
import jp.morrowgear.drone.SupplyNetworkRegistry.Job;
import jp.morrowgear.drone.SupplyNetworkRegistry.Route;
import jp.morrowgear.drone.SupplyNetworkRegistry.Stage;
import jp.morrowgear.drone.SupplyNetworkRegistry.Token;
import org.junit.jupiter.api.Test;

final class SupplyNetworkRegistryTest {
	private final UUID owner = UUID.randomUUID();
	private final Route route = new Route(owner, "minecraft:overworld", 10, 20);
	private final AtomicInteger dirty = new AtomicInteger();
	private final SupplyNetworkRegistry registry = new SupplyNetworkRegistry(dirty::incrementAndGet);

	private Configuration configure(Route route) { return registry.configure(route, SupplyNetworkPolicy.defaults(), true); }
	private Job reserve(Configuration config) {
		return registry.reserve(config, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 0).orElseThrow();
	}

	@Test void duplicateTicksCannotAssignTwoJobsToTheSameDock() {
		Configuration config = configure(route);
		Job first = reserve(config);
		assertTrue(registry.reserve(config, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 1).isEmpty());
		assertEquals(List.of(first), registry.jobs());
		assertEquals(16, registry.incoming(route, SupplyKind.FUEL));
	}

	@Test void oneDroneCannotClaimAnotherDockWhileAssigned() {
		Job first = reserve(configure(route));
		Configuration other = configure(new Route(owner, route.dimension(), 10, 21));
		assertTrue(registry.reserve(other, first.drone(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 1).isEmpty());
	}

	@Test void sourceReservationsSubtractOtherDocksAndNeverOverdraw() {
		reserve(configure(route));
		Configuration other = configure(new Route(owner, route.dimension(), 10, 21));
		Job second = registry.reserve(other, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 20, 64, 1).orElseThrow();
		assertEquals(4, second.requested());
		assertEquals(20, registry.reservedSource(route, "minecraft:coal"));
	}

	@Test void differentOwnersCannotClaimOneSourceOrDock() {
		configure(route);
		assertThrows(IllegalArgumentException.class, () -> configure(new Route(UUID.randomUUID(), route.dimension(), 10, 30)));
		assertThrows(IllegalArgumentException.class, () -> configure(new Route(UUID.randomUUID(), route.dimension(), 11, 20)));
	}

	@Test void dimensionsWithIdenticalCoordinatesDoNotShareReservations() {
		reserve(configure(route));
		Route nether = new Route(owner, "minecraft:the_nether", 10, 20);
		assertEquals(0, registry.reservedSource(nether, "minecraft:coal"));
		assertEquals(0, registry.incoming(nether, SupplyKind.FUEL));
		assertNotNull(reserve(configure(nether)));
	}

	@Test void onlyExplicitSupplyKindAndExactItemCanBeReserved() {
		Configuration config = configure(route);
		assertTrue(registry.reserve(config, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:diamond", 16, 64, 64, 0).isEmpty());
		assertTrue(registry.reserve(config, UUID.randomUUID(), SupplyKind.GUN, "minecraft:coal", 16, 64, 64, 0).isEmpty());
		assertTrue(registry.reserve(config, UUID.randomUUID(), SupplyKind.GUN, "minecraft:iron_nugget", 16, 64, 64, 0).isEmpty());
	}

	@Test void loadCommitsOnceAndReleasesTheSourceReservation() {
		Job job = reserve(configure(route));
		assertTrue(registry.loaded(job.token(), 10));
		assertFalse(registry.loaded(job.token(), 10));
		assertFalse(registry.current(job));
		assertEquals(0, registry.reservedSource(route, job.item()));
		assertEquals(10, registry.incoming(route, SupplyKind.FUEL));
	}

	@Test void sourceMutationCannotCommitMoreThanReserved() {
		Job job = reserve(configure(route));
		assertFalse(registry.loaded(job.token(), 17));
		assertFalse(registry.loaded(job.token(), -1));
		assertFalse(registry.loaded(job.token(), 0));
		assertEquals(job, registry.job(job.token()).orElseThrow());
	}

	@Test void partialUnloadConservesLoadedItemsAndFullDockKeepsTheRemainder() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 16);
		assertTrue(registry.delivered(job.token(), 6));
		assertTrue(registry.delivered(job.token(), 0));
		Job partial = registry.job(job.token()).orElseThrow();
		assertEquals(16, partial.delivered() + partial.remaining());
		assertEquals(10, partial.remaining());
		assertEquals(Status.DOCK_FULL, partial.status());
		registry.heartbeat(job.token(), 200, false);
		assertEquals(Status.DOCK_FULL, registry.status(route));
		assertTrue(registry.delivered(job.token(), 10));
		assertFalse(registry.delivered(job.token(), 10));
		assertTrue(registry.jobs().isEmpty());
	}

	@Test void cancellingLadenJobReleasesReservationsButRetainsManifest() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 16);
		registry.delivered(job.token(), 5);
		registry.cancel(job.token(), Status.DOCK_LOST);
		Job held = registry.job(job.token()).orElseThrow();
		assertEquals(Stage.HELD, held.stage());
		assertEquals(11, held.remaining());
		assertEquals(0, registry.incoming(route, SupplyKind.FUEL));
		assertFalse(registry.dockReserved(route));
		assertFalse(registry.delivered(job.token(), 11));
		assertFalse(registry.loaded(job.token(), 16));
	}

	@Test void configureInvalidatesGenerationWithoutErasingCargo() {
		Configuration old = configure(route);
		Job job = reserve(old);
		registry.loaded(job.token(), 16);
		Configuration updated = registry.configure(route, old.rules(), false);
		assertTrue(updated.revision() > old.revision());
		assertEquals(16, registry.job(job.token()).orElseThrow().remaining());
		assertFalse(registry.current(job));
		assertTrue(registry.reserve(old, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 1).isEmpty());
	}

	@Test void unchangedConfigurationDoesNotCancelAnActiveTrip() {
		Configuration config = configure(route);
		Job job = reserve(config);
		assertEquals(config, configure(route));
		assertTrue(registry.current(job));
	}

	@Test void staleTokenCannotDeliverCancelOrReleaseANewerJob() {
		Configuration config = configure(route);
		Job old = reserve(config);
		registry.release(old.token());
		Job current = reserve(config);
		assertTrue(current.token().generation() > old.token().generation());
		registry.cancel(old.token(), Status.CANCELLED);
		registry.release(old.token());
		assertFalse(registry.loaded(old.token(), 16));
		assertTrue(registry.current(current));
	}

	@Test void rechargeKeepsReservationAndGenerationAndResumesTheSameJob() {
		Job job = reserve(configure(route));
		registry.heartbeat(job.token(), 100, true);
		registry.expireMissing(2000, List.of(job.drone()));
		assertEquals(Status.RECHARGING, registry.job(job.token()).orElseThrow().status());
		assertEquals(16, registry.reservedSource(route, job.item()));
		registry.heartbeat(job.token(), 2010, false);
		assertEquals(Status.IN_TRANSIT, registry.job(job.token()).orElseThrow().status());
		assertTrue(registry.loaded(job.token(), 16));
	}

	@Test void missingDroneExpiryCannotResumeStaleReservation() {
		Job job = reserve(configure(route));
		registry.expireMissing(1201, List.of());
		assertEquals(Stage.HELD, registry.job(job.token()).orElseThrow().stage());
		assertEquals(0, registry.reservedSource(route, job.item()));
		registry.heartbeat(job.token(), 1300, false);
		assertFalse(registry.loaded(job.token(), 16));
		assertEquals(Status.ASSIGNMENT_LOST, registry.status(route));
	}

	@Test void cleanReloadPreservesAssignmentAndNeverStoresItemsTwice() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 16);
		registry.delivered(job.token(), 7);
		SupplyNetworkSavedData data = new SupplyNetworkSavedData();
		data.registry().restore(registry.generation(), registry.configurations(), registry.jobs());
		var json = SupplyNetworkSavedData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
		SupplyNetworkSavedData restored = SupplyNetworkSavedData.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
		Job loaded = restored.registry().job(job.drone()).orElseThrow();
		assertEquals(job.token(), loaded.token());
		assertEquals(9, loaded.remaining());
		assertFalse(json.toString().contains("components"));
		assertFalse(restored.registry().loaded(job.token(), 16));
		assertTrue(restored.registry().delivered(job.token(), 9));
		assertTrue(restored.registry().jobs().isEmpty());
	}

	@Test void reloadWithReconfiguredRouteHoldsOldJobInsteadOfReassigningIt() {
		Configuration old = configure(route);
		Job job = reserve(old);
		registry.configure(route, old.rules(), false);
		SupplyNetworkRegistry restored = new SupplyNetworkRegistry(() -> {});
		restored.restore(registry.generation(), registry.configurations(), List.of(job));
		assertEquals(Stage.HELD, restored.job(job.token()).orElseThrow().stage());
		assertFalse(restored.loaded(job.token(), 16));
	}

	@Test void invalidTokenGenerationIsRejectedByCodec() {
		var json = new com.google.gson.JsonObject();
		json.addProperty("id", UUID.randomUUID().toString());
		json.addProperty("generation", 0);
		assertTrue(SupplyNetworkSavedData.TOKEN_CODEC.parse(JsonOps.INSTANCE, json).error().isPresent());
	}

	@Test void missingDockCanReturnLadenCargoToOriginalSourceWithoutReloadingIt() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 12);
		registry.cancel(job.token(), Status.DOCK_LOST);
		Job returning = registry.redirectRetained(job.token(), route.source(), true).orElseThrow();
		assertEquals(Stage.RETURN_SOURCE, returning.stage());
		assertEquals(route.source(), returning.deliveryTarget());
		assertEquals(job.token(), returning.token());
		assertEquals(0, registry.reservedSource(route, job.item()));
		assertEquals(0, registry.incoming(route, SupplyKind.FUEL));
		assertFalse(registry.loaded(job.token(), 12));
		assertTrue(registry.delivered(job.token(), 12));
	}

	@Test void alternateDockIsOwnerScopedAndReservesItsBuffer() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 12);
		registry.cancel(job.token(), Status.DOCK_LOST);
		Configuration otherOwner = configure(new Route(UUID.randomUUID(), route.dimension(), 11, 30));
		assertTrue(registry.redirectRetained(job.token(), otherOwner.route().dock(), false).isEmpty());
		Configuration alternate = configure(new Route(owner, route.dimension(), 10, 40));
		Job returning = registry.redirectRetained(job.token(), alternate.route().dock(), false).orElseThrow();
		assertEquals(Stage.RETURN_DOCK, returning.stage());
		assertTrue(registry.dockReserved(alternate.route()));
		assertEquals(12, registry.incoming(alternate.route(), SupplyKind.FUEL));
		assertEquals(0, registry.incoming(route, SupplyKind.FUEL));
		assertTrue(registry.reserve(alternate, UUID.randomUUID(), SupplyKind.FUEL, "minecraft:coal", 16, 64, 64, 1).isEmpty());
	}

	@Test void redirectedReturnAndHeldFailureSurviveReloadWithoutChangingGeneration() {
		Job job = reserve(configure(route));
		registry.loaded(job.token(), 12);
		registry.cancel(job.token(), Status.DOCK_LOST);
		SupplyNetworkRegistry restored = new SupplyNetworkRegistry(() -> {});
		restored.restore(registry.generation(), registry.configurations(), registry.jobs());
		assertEquals(Status.DOCK_LOST, restored.job(job.token()).orElseThrow().status());
		restored.redirectRetained(job.token(), route.source(), true).orElseThrow();
		SupplyNetworkRegistry restarted = new SupplyNetworkRegistry(() -> {});
		restarted.restore(restored.generation(), restored.configurations(), restored.jobs());
		assertEquals(restored.jobs(), restarted.jobs());
		assertEquals(job.token(), restarted.job(job.drone()).orElseThrow().token());
	}

	@Test void persistentMutationsMarkSavedDataDirty() {
		Job job = reserve(configure(route));
		int count = dirty.get();
		registry.loaded(job.token(), 16);
		registry.delivered(job.token(), 3);
		registry.cancel(job.token(), Status.CANCELLED);
		registry.release(job.token());
		assertEquals(count + 4, dirty.get());
	}

	@Test void malformedRulesAndUnboundedRoutesAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> registry.configure(route, List.of(), true));
		assertThrows(IllegalArgumentException.class, () -> new Rule(SupplyKind.FUEL, 577, 0));
		for (int i = 0; i < SupplyNetworkPolicy.MAX_ROUTES; i++) configure(new Route(owner, route.dimension(), 10, 100 + i));
		assertThrows(IllegalStateException.class, () -> configure(route));
	}
}
