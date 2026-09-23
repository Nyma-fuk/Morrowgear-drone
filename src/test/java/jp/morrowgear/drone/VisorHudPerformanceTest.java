package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VisorHudPerformanceTest {
    private static final Path SOURCE = Path.of("src/client/java/jp/morrowgear/drone/client/VisorHudOverlay.java");
    @TempDir Path directory;

    @Test void executesTheActualClientCacheAndDashHelperWithoutMinecraftClientBootstrap() throws Exception {
        String source = Files.readString(SOURCE);
        String helper = source.substring(source.indexOf("final class VisorHudCache<V>"));
        Path input = directory.resolve("HudCacheChecks.java");
        Files.writeString(input, helper + "\n" + """
            public class HudCacheChecks {
                public static void run() {
                    var cache = new VisorHudCache<Integer>(2);
                    var origin = new VisorHudCache.Point(0, 0, 0);
                    var edge = new VisorHudCache.Point(1, 0, 0);
                    var moved = new VisorHudCache.Point(1.01, 0, 0);
                    Object entity = new Object();
                    int[] calls = {0};
                    java.util.function.Supplier<Integer> query = () -> ++calls[0];
                    check(cache.get(entity, 0, origin, origin, "normal", 10, 1, query) == 1);
                    check(cache.get(entity, 9, edge, edge, "normal", 10, 1, query) == 1);
                    check(cache.get(entity, 10, origin, origin, "normal", 10, 1, query) == 2);
                    check(cache.get(entity, 10, moved, origin, "normal", 10, 1, query) == 3);
                    check(cache.get(entity, 10, moved, moved, "normal", 10, 1, query) == 4);
                    check(cache.get(entity, 10, moved, moved, "critical", 1, 1, query) == 5);
                    check(cache.get(entity, 10, moved, moved, "critical", 1, 1, query) == 5);
                    check(cache.get(entity, 11, moved, moved, "critical", 1, 1, query) == 6);
                    check(cache.get(entity, 2, moved, moved, "critical", 1, 1, query) == 7);
                    cache.clear();
                    check(cache.size() == 0);
                    check(cache.get(entity, 2, moved, moved, "critical", 1, 1, query) == 8);
                    for (int i = 0; i < 500; i++) cache.get(new Object(), 2, origin, origin, "x", 10, 1, query);
                    check(cache.size() == 2);
                    var identities = new VisorHudCache<Integer>(4);
                    check(identities.get(new String("same id"), 0, origin, origin, "x", 10, 1, () -> 1) == 1);
                    check(identities.get(new String("same id"), 0, origin, origin, "x", 10, 1, () -> 2) == 2);
                    var projection = new VisorHudCache<Integer>(4);
                    check(projection.get(entity, 0, origin, origin, "view1", 1, 0, () -> 1) == 1);
                    check(projection.get(entity, 0, origin, origin, "view2", 1, 0, () -> 2) == 2);
                    check(projection.get(entity, 1, origin, origin, "view2", 1, 0, () -> 3) == 3);
                    for (int[] line : new int[][] {{0,0,0,0}, {5,8,4,8}, {0,0,3840,2160},
                        {4096,2160,0,0}, {10,20,10,-10000}, {Integer.MIN_VALUE,0,Integer.MAX_VALUE,1}}) {
                        for (int dash : new int[] {-1, 1, 7, 1000}) {
                            var segments = VisorHudCache.dashes(line[0], line[1], line[2], line[3], dash);
                            check(!segments.isEmpty() && segments.size() <= 64);
                            check(segments.getFirst().x1() == line[0] && segments.getFirst().y1() == line[1]);
                            check(segments.getLast().x2() == line[2] && segments.getLast().y2() == line[3]);
                            for (var segment : segments) check(Double.isFinite(segment.x1()) && Double.isFinite(segment.y2()));
                        }
                    }
                }
                private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
            }
            """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        assertEquals(0, compiler.run(null, null, null, "-proc:none", "-d", directory.toString(), input.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[] {directory.toUri().toURL()}, null)) {
            loader.loadClass("HudCacheChecks").getMethod("run").invoke(null);
        }
    }

    @Test void clientWiresBoundedWorkAndClearsBothCachesOnWorldOrPlayerReplacement() throws Exception {
        String source = Files.readString(SOURCE);
        assertTrue(source.contains("hudLevel != client.level || hudPlayer != client.player"));
        assertTrue(source.contains("visibility.clear(); projections.clear(); tickMemo.clear();"));
        assertTrue(source.contains("entity.level() != client.level"));
        assertTrue(source.contains("critical ? 1 : 10, 1"));
        assertTrue(source.contains(".limit(MAX_CONTACTS)"));
        assertTrue(source.contains(".limit(MAX_BEACONS)"));
        assertFalse(source.contains("clusterCount("));
        assertTrue(source.contains("drawProximity(g, client, contacts, clusters"));
        String dashed = source.substring(source.indexOf("private void drawDashedLine("), source.indexOf("private void drawText("));
        assertTrue(dashed.contains("VisorHudCache.dashes("));
        assertFalse(dashed.contains("i <="));
        assertTrue(dashed.contains("1, color)"));
    }
}
