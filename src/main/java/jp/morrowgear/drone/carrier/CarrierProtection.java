package jp.morrowgear.drone.carrier;

import java.util.IdentityHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class CarrierProtection {
    public static final TagKey<Block> PROTECTED = TagKey.create(Registries.BLOCK, CarrierModule.id("carrier_protected"));
    public static final TagKey<Block> NATURAL = TagKey.create(Registries.BLOCK, CarrierModule.id("carrier_mineable"));
    public static final TagKey<Block> PLASMA_ABLATIVE = TagKey.create(Registries.BLOCK, CarrierModule.id("carrier_plasma_ablative"));
    public enum PlasmaResponse { VAPORIZE, ABLATE, RESIST }
    private static final Map<BlockState, PlasmaResponse> PLASMA_RESPONSE_CACHE = new IdentityHashMap<>();
    private static final Map<BlockState, PlasmaResponse> CARRIER_MINING_RESPONSE_CACHE = new IdentityHashMap<>();
    public enum Provenance { NATURAL, PLAYER_BUILT, UNKNOWN }
    public interface Guard {
        /** Claim integrations can identify legacy/moved builds; UNKNOWN uses explicit region consent. */
        Provenance provenance(ServerLevel level, BlockPos pos);
        boolean permits(ServerPlayer owner, ServerLevel level, BlockPos pos);
    }
    private static Guard guard = new Guard() {
        public Provenance provenance(ServerLevel level, BlockPos pos) { return Provenance.UNKNOWN; }
        public boolean permits(ServerPlayer owner, ServerLevel level, BlockPos pos) { return true; }
    };
    private CarrierProtection() {}

    /** Explicitly requested rescue corridor, not unrestricted terrain excavation. */
    public static boolean rescuePermitted(ServerPlayer owner, ServerLevel level, BlockPos pos, BlockState state) {
        if (!owner.mayBuild() || owner.isSpectator() || !level.getWorldBorder().isWithinBounds(pos)
            || !level.mayInteract(owner, pos) || !owner.mayInteract(level, pos)
            || state.is(PROTECTED) || state.is(Blocks.BEDROCK) || state.hasBlockEntity()
            || level.getBlockEntity(pos) != null || state.getDestroySpeed(level, pos) < 0
            || !state.getFluidState().isEmpty()) return false;
        try {
            return !CarrierSavedData.get(level.getServer()).playerBuilt(level.dimension().identifier().toString(), pos.asLong())
                && guard.provenance(level, pos) != Provenance.PLAYER_BUILT && guard.permits(owner, level, pos)
                && PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, owner, pos, state, null);
        } catch (RuntimeException ex) { return false; }
    }
    public static void install(Guard integration) { guard = java.util.Objects.requireNonNull(integration); }
    /** Parent automation/piston integrations may record placements that bypass player interaction. */
    public static void recordPlayerBuild(ServerLevel level, BlockPos pos) {
        CarrierSavedData.get(level.getServer()).markPlayerBuilt(level.dimension().identifier().toString(), pos.asLong());
    }
    public static boolean permitted(ServerPlayer owner, ServerLevel level, BlockPos pos, BlockState state) {
        if (!owner.mayBuild() || owner.isSpectator() || !level.getWorldBorder().isWithinBounds(pos)
            || !level.mayInteract(owner, pos) || !owner.mayInteract(level, pos)
            || materialStop(state, state.is(NATURAL), state.is(PROTECTED)) != null
            || level.getBlockEntity(pos) != null
            || !state.is(Blocks.LIGHT) && state.getDestroySpeed(level, pos) < 0) return false;
        try {
            return !CarrierSavedData.get(level.getServer()).playerBuilt(level.dimension().identifier().toString(), pos.asLong())
                && guard.provenance(level, pos) != Provenance.PLAYER_BUILT && guard.permits(owner, level, pos)
                && PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(level, owner, pos, state, null);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Independent of provenance/claims; callers must still perform every owner and world check. */
    static CarrierPolicy.Stop materialStop(BlockState state, boolean naturalTag, boolean protectedTag) {
        if (state.is(Blocks.BEDROCK) || protectedTag || state.hasBlockEntity()) return CarrierPolicy.Stop.PROTECTED;
        if (state.is(Blocks.LIGHT)) return null;
        if (drainableFluid(state)) return null;
        if (!naturalTag || state.getBlock() instanceof FallingBlock
            && !state.is(Blocks.SAND) && !state.is(Blocks.RED_SAND) && !state.is(Blocks.GRAVEL)) return CarrierPolicy.Stop.PROTECTED;
        if (state.getBlock() instanceof LeavesBlock && state.getValue(LeavesBlock.PERSISTENT)) return CarrierPolicy.Stop.PROTECTED;
        return null;
    }

    /** Canonical block states let the mining hot loop cache tag classification by identity. */
    public static PlasmaResponse plasmaResponse(BlockState state) {
        PlasmaResponse cached = PLASMA_RESPONSE_CACHE.get(state);
        if (cached != null) return cached;
        PlasmaResponse computed = plasmaResponse(state, state.is(NATURAL), state.is(PLASMA_ABLATIVE), state.is(PROTECTED));
        PLASMA_RESPONSE_CACHE.put(state, computed);
        return computed;
    }
    static PlasmaResponse plasmaResponse(BlockState state, boolean natural, boolean ablative, boolean protectedTag) {
        if (state.is(Blocks.BEDROCK) || protectedTag || state.hasBlockEntity()) return PlasmaResponse.RESIST;
        if (state.is(Blocks.LIGHT)) return PlasmaResponse.VAPORIZE;
        if (ablative) return PlasmaResponse.ABLATE;
        return natural || drainableFluid(state) ? PlasmaResponse.VAPORIZE : PlasmaResponse.RESIST;
    }

    /**
     * The carrier's strategic plasma excavator is an explicit destructive operation. It ignores
     * hardness, claims, provenance, protected tags and block entities; only the material's residue
     * class changes. World bounds and loaded-chunk checks remain the caller's responsibility.
     */
    public static PlasmaResponse carrierMiningResponse(BlockState state) {
        PlasmaResponse cached = CARRIER_MINING_RESPONSE_CACHE.get(state);
        if (cached != null) return cached;
        PlasmaResponse computed = carrierMiningResponse(state.is(PLASMA_ABLATIVE));
        CARRIER_MINING_RESPONSE_CACHE.put(state, computed);
        return computed;
    }

    static PlasmaResponse carrierMiningResponse(boolean ablative) {
        return ablative ? PlasmaResponse.ABLATE : PlasmaResponse.VAPORIZE;
    }

    /** Only non-fluid ablative residue survives long enough to create a drop and reserve cargo space. */
    static boolean recoversDrops(PlasmaResponse response, boolean drainingFluid) {
        return response == PlasmaResponse.ABLATE && !drainingFluid;
    }

    /** A successful data-pack reload may change block tag membership. */
    static void clearPlasmaResponseCache() {
        PLASMA_RESPONSE_CACHE.clear();
        CARRIER_MINING_RESPONSE_CACHE.clear();
    }
    static int plasmaResponseCacheSize() { return PLASMA_RESPONSE_CACHE.size(); }
    static int carrierMiningResponseCacheSize() { return CARRIER_MINING_RESPONSE_CACHE.size(); }

    /** A fluid-only block may be drained; waterlogged terrain still follows the underlying block policy. */
    static boolean drainableFluid(BlockState state) {
        var fluid = state.getFluidState();
        return !fluid.isEmpty() && state.getBlock() == fluid.createLegacyBlock().getBlock();
    }
}
