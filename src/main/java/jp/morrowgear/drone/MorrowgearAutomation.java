package jp.morrowgear.drone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Opt-in, dedicated-world automation. Normal launches never enter this path. */
public final class MorrowgearAutomation {
    public static final String DRONE_WORLD = "Morrowgear Drone Test";
    private static final String SCENARIO = value("MORROWGEAR_AUTOMATION_SCENARIO");
    private static final String REPORT = rawValue("MORROWGEAR_AUTOMATION_REPORT");
    private static final int START_DELAY_TICKS = 40;
    private static final int TIMEOUT_TICKS = 18_000;
    private static volatile State state = State.DISABLED;
    private static volatile String detail = "automation disabled";
    private static ServerPlayer player;
    private static long joinedAt;
    private static long startedAt;

    private enum State { DISABLED, WAITING, RUNNING, PASSED, FAILED }

    private MorrowgearAutomation() {}

    public static void register() {
        if (SCENARIO.isBlank()) return;
        state = State.WAITING;
        detail = "waiting for dedicated test world";
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (player != null) return;
            ServerPlayer joined = handler.getPlayer();
            player = joined;
            joinedAt = server.getTickCount();
            detail = "dedicated world joined";
        });
        ServerTickEvents.END_SERVER_TICK.register(MorrowgearAutomation::tick);
    }

    public static boolean requested() { return !SCENARIO.isBlank(); }
    public static String scenario() { return SCENARIO; }
    public static boolean finished() { return state == State.PASSED || state == State.FAILED; }
    public static boolean passed() { return state == State.PASSED; }
    public static String detail() { return detail; }

    /**
     * Integrated-server completion can be observed through a separate class-loader state on
     * the render thread. The report is the cross-boundary completion signal in that case.
     */
    public static boolean completionReportExists() {
        if (REPORT.isBlank()) return false;
        Path target = Path.of(REPORT).toAbsolutePath().normalize();
        try {
            return Files.isRegularFile(target) && Files.size(target) > 0L;
        } catch (IOException ignored) {
            return false;
        }
    }

    /** Used by the client-side HMI driver after it has exercised real Screen instances. */
    public static void completeClientScenario(boolean passed, String message) {
        if (!SCENARIO.equals("ui") && !SCENARIO.equals("ui_capture")
            && !SCENARIO.equals("multiplayer_connect")) return;
        complete(passed, message);
    }

    private static void tick(MinecraftServer server) {
        if (finished() || player == null || player.hasDisconnected()) return;
        long now = server.getTickCount();
        if (state == State.WAITING && now - joinedAt >= START_DELAY_TICKS) {
            if (!allowed(player, SCENARIO)) {
                complete(false, "refused world=" + server.getWorldData().getLevelName()
                    + "; creative=" + player.isCreative() + "; players="
                    + server.getPlayerList().getPlayers().size() + "; scenario=" + SCENARIO);
                return;
            }
            startedAt = now;
            if (SCENARIO.equals("ui") || SCENARIO.equals("ui_capture")) {
                int prepared = MorrowgearRuntimeVerifier.startAutomation(player, "ui");
                if (prepared == 0) complete(false, "UI fixture preparation was refused");
                else { state = State.RUNNING; detail = "UI fixture ready; client driver running"; }
                return;
            }
            boolean started = SCENARIO.equals("carrier")
                ? CarrierRuntimeVerification.startAutomation(player)
                : MorrowgearRuntimeVerifier.startAutomation(player, SCENARIO) != 0;
            if (!started) complete(false, "scenario start was refused: " + SCENARIO);
            else { state = State.RUNNING; detail = "scenario running"; }
            return;
        }
        if (state != State.RUNNING) return;
        if (now - startedAt > TIMEOUT_TICKS) {
            complete(false, "timeout after " + TIMEOUT_TICKS + " ticks");
            return;
        }
        if (SCENARIO.equals("ui") || SCENARIO.equals("ui_capture")) return;
        var result = SCENARIO.equals("carrier")
            ? CarrierRuntimeVerification.automationStatus(server)
            : MorrowgearRuntimeVerifier.automationStatus(SCENARIO);
        detail = result.detail();
        if (result.done()) complete(result.passed(), result.detail());
    }

    private static boolean allowed(ServerPlayer candidate, String scenario) {
        MinecraftServer server = candidate.level().getServer();
        String required = scenario.equals("carrier") ? CarrierRuntimeVerification.WORLD_NAME : DRONE_WORLD;
        return server.isSingleplayer() && candidate.isCreative()
            && server.getPlayerList().getPlayers().size() == 1
            && required.equals(server.getWorldData().getLevelName());
    }

    private static synchronized void complete(boolean passed, String message) {
        if (finished()) return;
        state = passed ? State.PASSED : State.FAILED;
        detail = message == null ? "" : message;
        writeReport();
        MorrowgearDrone.LOGGER.info("[MORROWGEAR AUTOMATION] {} / {} / {}",
            state, SCENARIO, detail);
    }

    private static void writeReport() {
        if (REPORT.isBlank()) return;
        Path target = Path.of(REPORT).toAbsolutePath().normalize();
        String json = "{\n"
            + "  \"scenario\": \"" + escape(SCENARIO) + "\",\n"
            + "  \"status\": \"" + state.name().toLowerCase(Locale.ROOT) + "\",\n"
            + "  \"detail\": \"" + escape(detail) + "\",\n"
            + "  \"finishedAt\": \"" + Instant.now() + "\"\n"
            + "}\n";
        try {
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, json, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            MorrowgearDrone.LOGGER.error("[MORROWGEAR AUTOMATION] report write failed: {}", target, failure);
        }
    }

    private static String value(String name) {
        return rawValue(name).toLowerCase(Locale.ROOT);
    }

    private static String rawValue(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\r", "\\r").replace("\n", "\\n");
    }

    record Result(boolean done, boolean passed, String detail) {
        static Result running(String detail) { return new Result(false, false, detail); }
        static Result pass(String detail) { return new Result(true, true, detail); }
        static Result fail(String detail) { return new Result(true, false, detail); }
    }
}
