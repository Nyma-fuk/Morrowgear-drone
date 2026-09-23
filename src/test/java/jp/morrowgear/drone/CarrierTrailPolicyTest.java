package jp.morrowgear.drone;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import jp.morrowgear.drone.FormationTrailPolicy.TrailStyle;
import jp.morrowgear.drone.carrier.CarrierNavigation;
import jp.morrowgear.drone.carrier.CarrierPolicy;
import jp.morrowgear.drone.carrier.CarrierTrailPolicy;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class CarrierTrailPolicyTest {
    private static final Vec3 ORIGIN = new Vec3(120, 80, -240);
    private static final double EPSILON = 1.0e-8;

    // The controller's tick inputs, without Minecraft client classes or a running world.
    private static final class Samples {
        final FormationTrailHistory history = new FormationTrailHistory();
        Vec3 previous;
        long previousTick = Long.MIN_VALUE;

        void tick(long tick, Vec3 position, float yaw) {
            tick(tick, position, yaw, CarrierPolicy.Mode.IDLE);
        }

        void tick(long tick, Vec3 position, float yaw, CarrierPolicy.Mode mode) {
            if (previousTick == tick) return;
            boolean moving = previous != null
                && CarrierTrailPolicy.emitting(mode, position.distanceTo(previous), tick - previousTick);
            history.tick(moving ? TrailStyle.NAVIGATION : TrailStyle.NONE, position,
                position.add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, yaw)),
                position.add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.RIGHT, yaw)), tick, moving, 0);
            previous = position;
            previousTick = tick;
        }
    }

    private static void close(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, EPSILON);
        assertEquals(expected.y, actual.y, EPSILON);
        assertEquals(expected.z, actual.z, EPSILON);
    }

    private static Samples cruise() {
        var samples = new Samples();
        for (int tick = 0; tick <= 3; tick++) samples.tick(tick, ORIGIN.add(0, 0, -.2 * tick), 0);
        return samples;
    }

    private static long segments(FormationTrailHistory history) {
        var points = history.points();
        long count = 0;
        for (int i = 1; i < points.size(); i++)
            if (points.get(i - 1).strip() == points.get(i).strip()) count++;
        return count;
    }

    @Test void anchorsMatchTheUnchangedCanonicalRearExhaust() {
        close(new Vec3(-7, 6.7, 24.9), CarrierTrailPolicy.LEFT);
        close(new Vec3(7, 6.7, 24.9), CarrierTrailPolicy.RIGHT);
        assertEquals(14, CarrierTrailPolicy.LEFT.distanceTo(CarrierTrailPolicy.RIGHT), EPSILON);
        assertTrue(CarrierTrailPolicy.LEFT.z < CarrierPolicy.LENGTH / 2);
        assertTrue(CarrierTrailPolicy.LEFT.y < CarrierPolicy.HEIGHT);
    }

    @Test void positiveYawRotatesAftAnchorsInTheSameDirectionAsTheMesh() {
        close(new Vec3(24.9, 6.7, 7), CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, 90));
        close(new Vec3(24.9, 6.7, -7), CarrierTrailPolicy.rotate(CarrierTrailPolicy.RIGHT, 90));
        close(new Vec3(7, 6.7, -24.9), CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, 180));
        close(new Vec3(-24.9, 6.7, -7), CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, -90));
    }

    @Test void exhaustStaysBehindTheNavigationNoseAndUprightAtEveryHeading() {
        for (int yaw = -540; yaw <= 540; yaw += 15) {
            Vec3 left = CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, yaw);
            Vec3 right = CarrierTrailPolicy.rotate(CarrierTrailPolicy.RIGHT, yaw);
            Vec3 nose = CarrierNavigation.rotate(new Vec3(0, 0, -1), yaw);
            close(CarrierNavigation.rotate(CarrierTrailPolicy.LEFT, yaw), left);
            assertEquals(-24.9, left.add(right).scale(.5).dot(nose), EPSILON);
            assertEquals(14, left.distanceTo(right), EPSILON);
            assertEquals(6.7, left.y, EPSILON);
            close(CarrierTrailPolicy.LEFT, CarrierTrailPolicy.rotate(left, -yaw));
        }
    }

    @Test void wrappedHeadingUsesTheShortArcWithoutMovingOldAnchors() {
        Vec3 before = CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, 179);
        Vec3 after = CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, -179);
        assertTrue(before.distanceTo(after) < 1);
        close(after, CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, 181));
        double radius = Math.hypot(CarrierTrailPolicy.LEFT.x, CarrierTrailPolicy.LEFT.z);
        assertTrue(before.distanceTo(after) <= radius * Math.toRadians(2) + EPSILON);
    }

    @Test void emissionRequiresConsecutiveFiniteNavigationMovementWithinTheBound() {
        for (double distance : new double[] {.025, .2, .5, 1})
            assertTrue(CarrierTrailPolicy.emitting(CarrierPolicy.Mode.IDLE, distance, 1));
        for (double distance : new double[] {-1, 0, .0249, 1.0001, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(CarrierTrailPolicy.emitting(CarrierPolicy.Mode.IDLE, distance, 1));
        for (long elapsed : new long[] {-1, 0, 2, 40})
            assertFalse(CarrierTrailPolicy.emitting(CarrierPolicy.Mode.IDLE, .5, elapsed));
        for (CarrierPolicy.Mode mode : CarrierPolicy.Mode.values())
            if (mode != CarrierPolicy.Mode.IDLE) assertFalse(CarrierTrailPolicy.emitting(mode, .5, 1));
    }

    @Test void firstObservationAndRepeatedFramesCannotInventATrail() {
        var samples = new Samples();
        samples.tick(0, ORIGIN, 0);
        assertFalse(samples.history.hasPoints());
        samples.tick(1, ORIGIN.add(0, 0, -.2), 0);
        var original = samples.history.points();
        for (int frame = 0; frame < 100; frame++) samples.tick(1, ORIGIN.add(0, 0, -.2), 0);
        assertEquals(original, samples.history.points());
    }

    @Test void stoppingRetiresWorldSpacePointsAndRestartUsesANewStrip() {
        var samples = cruise();
        Vec3 stopped = samples.previous;
        samples.tick(4, stopped, 0);
        assertFalse(samples.history.emitting());
        var retired = samples.history.points().getLast();
        assertEquals(4, retired.stoppedAt());
        samples.tick(5, stopped.add(0, 0, -.2), 0);
        assertNotEquals(retired.strip(), samples.history.points().getLast().strip());
        assertEquals(retired, samples.history.points().get(2));
        assertEquals(0, retired.alpha(4 + FormationLightTrailProfile.STOP_FADE_TICKS));
    }

    @Test void rotatingAtRestDoesNotSweepAnExhaustRibbonAcrossTheShip() {
        var samples = cruise();
        Vec3 stopped = samples.previous;
        var oldLeft = samples.history.points().getLast().left();
        samples.tick(4, stopped, 2);
        for (int tick = 5; tick <= 8; tick++) samples.tick(tick, stopped, (tick - 3) * 2);
        assertFalse(samples.history.emitting());
        assertEquals(3, samples.history.points().size());
        close(oldLeft, samples.history.points().getLast().left());
        int retiredStrip = samples.history.points().getLast().strip();
        Vec3 moved = stopped.add(CarrierNavigation.rotate(new Vec3(0, 0, -.2), 10));
        samples.tick(9, moved, 10);
        var fresh = samples.history.points().getLast();
        assertNotEquals(retiredStrip, fresh.strip());
        close(moved.add(CarrierTrailPolicy.rotate(CarrierTrailPolicy.LEFT, 10)), fresh.left());
        assertEquals(14, fresh.left().distanceTo(fresh.right()), EPSILON);
    }

    @Test void packetGapsAndCorrectionsBreakStripsWithoutConnectingAcrossTheGap() {
        var samples = cruise();
        int oldStrip = samples.history.points().getLast().strip();
        samples.tick(10, samples.previous.add(0, 0, -.5), 0);
        assertFalse(samples.history.emitting());
        samples.tick(11, samples.previous.add(0, 0, -.2), 0);
        assertNotEquals(oldStrip, samples.history.points().getLast().strip());
        int resumedStrip = samples.history.points().getLast().strip();
        samples.tick(12, samples.previous.add(0, 0, -2), 0);
        assertFalse(samples.history.emitting());
        samples.tick(13, samples.previous.add(0, 0, -.2), 0);
        assertNotEquals(resumedStrip, samples.history.points().getLast().strip());
    }

    @Test void teleportsAndRewoundClocksClearTheOldHistory() {
        var samples = cruise();
        samples.tick(4, samples.previous.add(32, 0, 0), 0);
        assertFalse(samples.history.hasPoints());
        samples.tick(5, samples.previous.add(0, 0, -.2), 0);
        assertEquals(1, samples.history.points().size());
        samples.tick(2, samples.previous, 0);
        assertFalse(samples.history.hasPoints());
        samples.tick(3, samples.previous.add(0, 0, -.2), 0);
        assertEquals(1, samples.history.points().size());
    }

    @Test void workModeChangesRetireNavigationExhaust() {
        for (CarrierPolicy.Mode mode : CarrierPolicy.Mode.values()) {
            if (mode == CarrierPolicy.Mode.IDLE) continue;
            var samples = cruise();
            samples.tick(4, samples.previous.add(0, 0, -.2), 0, mode);
            assertFalse(samples.history.emitting());
            assertTrue(samples.history.points().stream().allMatch(point -> point.stoppedAt() == 4));
        }
    }

    @Test void maximumTrackedLongFlightsHaveFiniteHistoryAndStopLifetime() {
        assertTrue(CarrierTrailPolicy.MAX_TRACKED > 0 && CarrierTrailPolicy.MAX_TRACKED <= 4);
        assertTrue(Double.isFinite(CarrierTrailPolicy.RANGE) && CarrierTrailPolicy.RANGE <= 384);
        for (int ship = 0; ship < CarrierTrailPolicy.MAX_TRACKED; ship++) {
            var samples = new Samples();
            for (int tick = 0; tick < 2000; tick++) {
                samples.tick(tick, ORIGIN.add(ship * 64, 0, -.5 * tick), 0);
                var points = samples.history.points();
                assertTrue(points.size() <= FormationLightTrailProfile.MAX_CONTROL_POINTS);
                if (!points.isEmpty()) assertTrue(tick - points.getFirst().tick() < FormationLightTrailProfile.LIFETIME_TICKS);
            }
            for (int tick = 2000; tick <= 2000 + FormationLightTrailProfile.STOP_FADE_TICKS; tick++)
                samples.tick(tick, samples.previous, 0);
            assertFalse(samples.history.hasPoints());
        }
    }

    @Test void renderInterpolationRetargetsOnlyACopyOfTheNewestAnchor() {
        var samples = cruise();
        var saved = samples.history.points();
        var newest = saved.getLast();
        Vec3 interpolated = samples.previous.add(0, 0, .1);
        var rendered = newest.at(interpolated.add(CarrierTrailPolicy.LEFT), interpolated.add(CarrierTrailPolicy.RIGHT));
        assertEquals(saved, samples.history.points());
        assertEquals(newest.tick(), rendered.tick());
        assertEquals(newest.strip(), rendered.strip());
        assertEquals(newest.stoppedAt(), rendered.stoppedAt());
        assertEquals(newest.alpha(3.5), rendered.alpha(3.5));
        assertEquals(.1, newest.left().distanceTo(rendered.left()), EPSILON);
    }

    @Test void twoTickNetworkUpdatesNeedInterpolatedClientPositionsToProduceSegments() {
        var rawPackets = new Samples();
        var interpolated = new Samples();
        for (int tick = 0; tick < 12; tick++) {
            rawPackets.tick(tick, ORIGIN.add(0, 0, -(tick / 2)), 0);
            interpolated.tick(tick, ORIGIN.add(0, 0, -.5 * tick), 0);
        }
        assertEquals(0, segments(rawPackets.history), "packet-only positions retire every singleton on the intervening tick");
        assertTrue(segments(interpolated.history) >= 8, "client interpolation must provide continuous motion samples");
    }

    @Test void clientSamplingIsTickBasedBoundedAndIndependentOfTheRenderFrustum() throws Exception {
        String controller = source("src/client/java/jp/morrowgear/drone/client/CarrierTrailController.java");
        String client = source("src/client/java/jp/morrowgear/drone/client/MorrowgearDroneClient.java");
        assertTrue(client.contains("CarrierTrailController.tick(client)"));
        assertTrue(controller.contains("if (level != client.level || client.player == null)"));
        assertTrue(controller.contains("if (now == tick) return"));
        assertTrue(controller.contains("if (now < tick) tracks.clear()"));
        assertTrue(controller.contains("client.isPaused()"));
        assertTrue(controller.contains("limit(CarrierTrailPolicy.MAX_TRACKED)"));
        assertTrue(controller.contains("tracks.keySet().removeIf(id -> !seen.contains(id))"));
        assertFalse(controller.contains("isVisible("));
        assertFalse(controller.contains("camera"));
    }

    @Test void rendererIncludesTrailBoundsAndDoesNotRotateWorldSpacePointsTwice() throws Exception {
        String renderer = source("src/client/java/jp/morrowgear/drone/client/CarrierRenderer.java");
        String controller = source("src/client/java/jp/morrowgear/drone/client/CarrierTrailController.java");
        assertTrue(renderer.contains("AABB hull = CarrierTrailController.bounds(entity)"));
        assertTrue(controller.contains("result.minmax(new AABB(point.left(), point.right()).inflate(1))"));
        int submit = renderer.indexOf("@Override public void submit(");
        int rotate = renderer.indexOf("poses.mulPose(Axis.YP.rotationDegrees(state.heading))", submit);
        int pop = renderer.indexOf("poses.popPose()", rotate);
        int trail = renderer.indexOf("if (state.trail.size() > 1)", submit);
        assertTrue(submit >= 0 && rotate > submit && pop > rotate && trail > pop);
        String drawing = renderer.substring(trail, renderer.indexOf("if (state.boardingPad != null", trail));
        assertTrue(drawing.contains("if (a.strip() != b.strip()) continue"));
        assertTrue(drawing.contains("Math.min(a.alpha(state.trailTime), b.alpha(state.trailTime))"));
        assertTrue(drawing.contains("RenderTypes.entityTranslucentEmissive(TRAIL_TEXTURE)"));
        assertFalse(drawing.contains("MorrowgearRenderTypes.energyBeam()"));
        assertFalse(drawing.contains("mulPose("));
        assertTrue(drawing.contains("a.right()).subtract(origin)"));
        assertTrue(drawing.contains("b.right()).subtract(origin)"));
        assertFalse(renderer.contains("CarrierTrailController.tick("));
    }

    @Test void entityProvidesANonnullHandlerAndAdvancesItBeforeReturningFromTheClientBranch() throws Exception {
        assertTrue(interpolationWired(source("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java")));
    }

    @Test void interpolationGuardRejectsNullHandlersAndMissingClientAdvancement() throws Exception {
        String entity = source("src/main/java/jp/morrowgear/drone/carrier/CarrierEntity.java");
        assertTrue(entity.contains("return interpolation;") && entity.contains("interpolation.interpolate();"));
        assertFalse(interpolationWired(entity.replace("return interpolation;", "return null;")));
        assertFalse(interpolationWired(entity.replace("interpolation.interpolate();", "/* interpolation.interpolate(); */")));
        assertFalse(interpolationWired(entity.replace("interpolation.interpolate();", "other.interpolate();")));
    }

    private static String source(String path) throws Exception { return Files.readString(Path.of(path)); }

    private static Tree unwrap(Tree tree) {
        return tree instanceof ParenthesizedTree parentheses ? unwrap(parentheses.getExpression()) : tree;
    }

    private static MethodTree method(ClassTree owner, String name) {
        return owner.getMembers().stream().filter(MethodTree.class::isInstance).map(MethodTree.class::cast)
            .filter(method -> method.getName().contentEquals(name)).findFirst().orElseThrow();
    }

    private static boolean interpolationWired(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Source contracts require the build JDK's parser");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var input = new SimpleJavaFileObject(URI.create("string:///CarrierEntity.java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignored) { return source; }
        };
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            // Parse only; comments and strings cannot satisfy the runtime wiring contract.
            var task = (JavacTask)compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next();
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
            var entity = (ClassTree)unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).findFirst().orElseThrow();
            var getter = method(entity, "getInterpolation").getBody().getStatements();
            if (getter.size() != 1 || !(getter.getFirst() instanceof ReturnTree returned) || returned.getExpression() == null) return false;
            String field = returned.getExpression().toString().replaceFirst("^this\\.", "");
            boolean initialized = entity.getMembers().stream().filter(VariableTree.class::isInstance).map(VariableTree.class::cast)
                .anyMatch(variable -> variable.getName().contentEquals(field)
                    && variable.getInitializer() instanceof NewClassTree created
                    && created.getIdentifier().toString().endsWith("InterpolationHandler")
                    && !created.getArguments().isEmpty() && created.getArguments().getFirst().toString().equals("this"));
            if (!initialized) return false;
            for (var statement : method(entity, "tick").getBody().getStatements()) {
                if (!(statement instanceof IfTree branch)) continue;
                if (!unwrap(branch.getCondition()).toString().equals("level().isClientSide()")) continue;
                if (!(branch.getThenStatement() instanceof BlockTree body)) return false;
                boolean advanced = false;
                for (var child : body.getStatements()) {
                    if (child instanceof ReturnTree) return advanced;
                    if (child instanceof ExpressionStatementTree expression && expression.getExpression() instanceof MethodInvocationTree call
                        && call.getArguments().isEmpty() && call.getMethodSelect().toString().equals(field + ".interpolate")) advanced = true;
                }
            }
            return false;
        }
    }
}
