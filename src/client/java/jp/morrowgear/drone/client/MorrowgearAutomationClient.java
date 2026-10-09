package jp.morrowgear.drone.client;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import jp.morrowgear.drone.MorrowgearAutomation;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Drives and captures native screens without moving the operating-system mouse. */
final class MorrowgearAutomationClient {
    private static final String[] PAGE_NAMES = {
        "operations-map", "wing-formation", "missions", "dock-supply", "supply-network",
        "recovery", "equipment", "carrier", "visor", "reports", "history"
    };
    private static int ticks;
    private static boolean uiStarted;
    private static int exitDelay;
    private static int capturePage;
    private static int captureDelay;
    private static int selectedCapturePage = -1;
    private static boolean captureRequested;
    private static final AtomicInteger CAPTURED = new AtomicInteger();

    private MorrowgearAutomationClient() {}

    static void register() {
        if (!MorrowgearAutomation.requested()) return;
        ClientTickEvents.END_CLIENT_TICK.register(MorrowgearAutomationClient::tick);
    }

    private static void tick(Minecraft client) {
        ticks++;
        boolean ui = MorrowgearAutomation.scenario().equals("ui");
        boolean capture = MorrowgearAutomation.scenario().equals("ui_capture");
        boolean multiplayerConnect = MorrowgearAutomation.scenario().equals("multiplayer_connect");
        if (multiplayerConnect && !MorrowgearAutomation.finished()
            && client.player != null && client.level != null && !client.hasSingleplayerServer()
            && ticks > 40) {
            MorrowgearAutomation.completeClientScenario(true,
                "remote Fabric server joined and client world synchronized");
        }
        if ((ui || capture) && !uiStarted && client.player != null && client.level != null && ticks > 80) {
            uiStarted = true;
            client.setScreenAndShow(new TacticalScreen());
            return;
        }
        if (ui && uiStarted && client.gui.screen() instanceof TacticalScreen screen && ticks > 90
            && !MorrowgearAutomation.finished()) {
            var result = screen.runAutomationSmoke();
            MorrowgearAutomation.completeClientScenario(result.passed(), result.detail());
        }
        if (capture && uiStarted && !MorrowgearAutomation.finished()) capture(client);
        if (!MorrowgearAutomation.finished()) return;
        if (++exitDelay >= 40) client.stop();
    }

    private static void capture(Minecraft client) {
        if (captureRequested) {
            if (CAPTURED.get() >= capturePage) {
                captureRequested = false;
                captureDelay = 5;
                if (capturePage >= PAGE_NAMES.length) MorrowgearAutomation.completeClientScenario(true,
                    PAGE_NAMES.length + " HMI screenshots captured without OS mouse input");
            }
            return;
        }
        if (selectedCapturePage >= 0) {
            if (captureDelay-- > 0) return;
            int page = selectedCapturePage;
            selectedCapturePage = -1;
            requestCapture(client, page);
            return;
        }
        if (capturePage >= PAGE_NAMES.length) return;
        if (!(client.gui.screen() instanceof TacticalScreen screen)) {
            MorrowgearAutomation.completeClientScenario(false, "controller screen unavailable before page " + capturePage);
            return;
        }
        var selected = screen.selectAutomationPage(capturePage);
        if (!selected.passed()) {
            MorrowgearAutomation.completeClientScenario(false, selected.detail());
            return;
        }
        selectedCapturePage = capturePage++;
        captureDelay = 4;
    }

    private static void requestCapture(Minecraft client, int page) {
        Path directory = screenshotDirectory();
        try { Files.createDirectories(directory); }
        catch (IOException failure) {
            MorrowgearAutomation.completeClientScenario(false, "screenshot directory failed: " + failure);
            return;
        }
        Path target = directory.resolve(String.format("%02d-%s.png", page + 1, PAGE_NAMES[page]));
        captureRequested = true;
        Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> write(image, target));
    }

    private static void write(NativeImage image, Path target) {
        try {
            image.writeToFile(target);
            CAPTURED.incrementAndGet();
        } catch (IOException failure) {
            MorrowgearAutomation.completeClientScenario(false, "screenshot write failed: " + failure);
        } finally {
            image.close();
        }
    }

    private static Path screenshotDirectory() {
        String configured = System.getenv("MORROWGEAR_AUTOMATION_SCREENSHOT_DIR");
        return configured == null || configured.isBlank()
            ? Path.of("build", "verification", "mouse-free", "screenshots").toAbsolutePath()
            : Path.of(configured).toAbsolutePath().normalize();
    }
}
