package ac.grim.grimac.utils.data;

import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.enums.Pose;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReachInterpolationGuaranteedHitboxTest {
    private static PacketEventsAPI<?> previousApi;

    @BeforeAll
    static void initializeMappings() {
        previousApi = PacketEvents.getAPI();
        PacketEvents.setAPI(new GeometryTestApi());
    }

    @AfterAll
    static void restoreMappings() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void fixedFeetProduceTheExactStandingBox() {
        SimpleCollisionBox feet = box(2, 3, 4, 2, 3, 4);
        assertBox(box(1.7, 3, 3.7, 2.3, 4.8, 4.3),
                ReachInterpolationData.guaranteedHitbox(feet, 0.6, 1.8, 0.6));
        assertBox(box(2, 3, 4, 2, 3, 4), feet);
    }

    @Test
    void feetRangeProducesAnIntersectionRatherThanAShrunkenOuterEnvelope() {
        SimpleCollisionBox positions = box(0, 2, -0.1, 0.2, 2.3, 0.1);
        SimpleCollisionBox inner = ReachInterpolationData.guaranteedHitbox(positions, 0.6, 1.8, 0.6);
        assertBox(box(-0.1, 2.3, -0.2, 0.3, 3.8, 0.2), inner);
        for (double x : new double[]{0, 0.1, 0.2}) {
            for (double y : new double[]{2, 2.15, 2.3}) {
                for (double z : new double[]{-0.1, 0, 0.1}) {
                    assertContained(box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3), inner);
                }
            }
        }
        assertBox(box(0, 2, -0.1, 0.2, 2.3, 0.1), positions);
    }

    @Test
    void wideOrTouchingUncertaintyHasNoPositiveVolumeInnerBox() {
        assertNull(ReachInterpolationData.guaranteedHitbox(box(0, 0, 0, 0.6, 0, 0), 0.6, 1.8, 0.6));
        assertNull(ReachInterpolationData.guaranteedHitbox(box(0, 0, 0, 1, 0, 0), 0.6, 1.8, 0.6));
        assertNull(ReachInterpolationData.guaranteedHitbox(box(0, 0, 0, 0, 1.8, 0), 0.6, 1.8, 0.6));
        assertNull(ReachInterpolationData.guaranteedHitbox(box(0, 0, 0, 0, 0, 0.6), 0.6, 1.8, 0.6));
    }

    @Test
    void rejectsInvalidPositionsAndDimensions() {
        SimpleCollisionBox origin = box(0, 0, 0, 0, 0, 0);
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertNull(ReachInterpolationData.guaranteedHitbox(origin, invalid, 1.8, 0.6));
            assertNull(ReachInterpolationData.guaranteedHitbox(origin, 0.6, invalid, 0.6));
            assertNull(ReachInterpolationData.guaranteedHitbox(origin, 0.6, 1.8, invalid));
        }
        assertNull(ReachInterpolationData.guaranteedHitbox(box(1, 0, 0, 0, 0, 0), 0.6, 1.8, 0.6));
        assertNull(ReachInterpolationData.guaranteedHitbox(box(0, 0, 0, Double.NaN, 0, 0), 0.6, 1.8, 0.6));
        assertNull(ReachInterpolationData.guaranteedHitbox(null, 0.6, 1.8, 0.6));
    }

    @Test
    void oldAndNewPacketIntervalsAreIntersectedWithoutChangingOuterBoxes() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        ReachInterpolationData old = location(entity, box(0, 0, 0, 0, 0, 0));
        ReachInterpolationData current = location(entity, box(0.2, 0, 0, 0.2, 0, 0));
        install(entity, old, current);
        SimpleCollisionBox outerBefore = entity.getPossibleCollisionBoxes().copy();
        double halfWidth = (double) 0.6F / 2;
        SimpleCollisionBox expected = box(0.2 - halfWidth, 0, -halfWidth, halfWidth, 1.8F, halfWidth);
        SimpleCollisionBox inner = entity.getGuaranteedCollisionBox();
        assertBox(expected, inner);
        inner.offset(10, 10, 10);
        assertBox(expected, entity.getGuaranteedCollisionBox());
        assertBox(outerBefore, entity.getPossibleCollisionBoxes());
        assertBox(box(0, 0, 0, 0, 0, 0), old.getPossibleLocationCombined());
        assertBox(box(0.2, 0, 0, 0.2, 0, 0), current.getPossibleLocationCombined());

        install(entity, old, location(entity, box(2, 0, 0, 2, 0, 0)));
        assertNull(entity.getGuaranteedCollisionBox());
        install(entity, null, location(entity, box(0, 0, 0, 2, 0, 0)));
        assertNull(entity.getGuaranteedCollisionBox());
    }

    @Test
    void nonRelativeTeleportUncertaintyIsIncludedBeforeIntersection() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        ReachInterpolationData location = location(entity, box(0, 0, 0, 0, 0, 0));
        location.expandNonRelative();
        install(entity, null, location);
        double halfWidth = (double) 0.6F / 2;
        assertBox(box(0.03125 - halfWidth, 0.015625, 0.03125 - halfWidth,
                halfWidth - 0.03125, (double) 1.8F - 0.015625, halfWidth - 0.03125), entity.getGuaranteedCollisionBox());
        // The existing raw feet and outer envelope are not narrowed by this read.
        assertBox(box(0, 0, 0, 0, 0, 0), location.getPossibleLocationCombined());
        assertBox(box(-halfWidth - 0.03125, -0.015625, -halfWidth - 0.03125,
                halfWidth + 0.03125, (double) 1.8F + 0.015625, halfWidth + 0.03125), entity.getPossibleCollisionBoxes());
    }

    @Test
    void standingDimensionsUseTheExistingFloatScaleCalculation() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        entity.scale = 0.5;
        install(entity, null, location(entity, box(0, 0, 0, 0, 0, 0)));
        double halfWidth = (double) (0.6F * 0.5F) / 2;
        assertBox(box(-halfWidth, 0, -halfWidth, halfWidth, 1.8F * 0.5F, halfWidth), entity.getGuaranteedCollisionBox());
    }

    @Test
    void excludesUnknownPosesDeadTargetsVehiclesAndNonPlayers() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        assertNull(entity.getGuaranteedCollisionBox());
        install(entity, null, location(entity, box(0, 0, 0, 0, 0, 0)));
        assertNotNull(entity.getGuaranteedCollisionBox());
        entity.beginPoseTransition(Pose.CROUCHING);
        assertNull(entity.getGuaranteedCollisionBox());
        entity.completePoseTransition(Pose.CROUCHING);
        assertNull(entity.getGuaranteedCollisionBox());
        entity.completePoseTransition(Pose.STANDING);
        entity.isDead = true;
        assertNull(entity.getGuaranteedCollisionBox());
        entity.isDead = false;
        entity.isBaby = true;
        assertNull(entity.getGuaranteedCollisionBox());
        entity.isBaby = false;
        entity.riding = new TestEntity(EntityTypes.PLAYER);
        assertNull(entity.getGuaranteedCollisionBox());
        TestEntity mob = new TestEntity(EntityTypes.ZOMBIE);
        install(mob, null, location(mob, box(0, 0, 0, 0, 0, 0)));
        assertNull(mob.getGuaranteedCollisionBox());
    }

    @Test
    void hitboxStateSnapshotsPreserveBothDisjointIntervalsWithoutLiveReferences() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        SimpleCollisionBox oldFeet = box(0, 0, 1, 0, 0, 1);
        SimpleCollisionBox currentFeet = box(0, 0, 2, 0, 0, 3);
        ReachInterpolationData old = location(entity, oldFeet);
        ReachInterpolationData current = location(entity, currentFeet);
        install(entity, old, current);
        SimpleCollisionBox outerBefore = entity.getPossibleCollisionBoxes();
        List<HitboxStateSnapshot> snapshots = entity.getPossibleHitboxStates();
        HitboxStateSnapshot expectedCurrent = new HitboxStateSnapshot(0, 0, 2, 0, 0, 3, 0.6F, 1.8F, 0.6F);
        HitboxStateSnapshot expectedOld = new HitboxStateSnapshot(0, 0, 1, 0, 0, 1, 0.6F, 1.8F, 0.6F);
        assertEquals(List.of(expectedCurrent, expectedOld), snapshots);
        assertThrows(UnsupportedOperationException.class, () -> snapshots.clear());
        assertBox(outerBefore, entity.getPossibleCollisionBoxes());
        assertBox(box(0, 0, 1, 0, 0, 1), oldFeet);
        assertBox(box(0, 0, 2, 0, 0, 3), currentFeet);
        assertNull(entity.getGuaranteedCollisionBox());
        currentFeet.offset(5, 5, 5);
        entity.scale = 0.5;
        assertEquals(List.of(expectedCurrent, expectedOld), snapshots);
        assertNotEquals(expectedCurrent, current.snapshotHitboxState());
    }

    @Test
    void hitboxStateSnapshotsIncludeTeleportUncertaintyAndFloatScaledDimensions() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        entity.scale = 0.5;
        ReachInterpolationData location = location(entity, box(0, 0, 2, 0, 0, 2));
        location.expandNonRelative();
        install(entity, null, location);
        assertEquals(List.of(new HitboxStateSnapshot(-0.03125, -0.015625, 2 - 0.03125,
                0.03125, 0.015625, 2 + 0.03125, 0.6F * 0.5F, 1.8F * 0.5F, 0.6F * 0.5F)), entity.getPossibleHitboxStates());
        assertBox(box(0, 0, 2, 0, 0, 2), location.getPossibleLocationCombined());
    }

    @Test
    void hitboxStateSnapshotsExcludeUncertainDimensionsAndUnsupportedEntities() throws Exception {
        TestEntity entity = new TestEntity(EntityTypes.PLAYER);
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        install(entity, null, location(entity, box(0, 0, 0, 0, 0, 0)));
        assertEquals(1, entity.getPossibleHitboxStates().size());
        entity.beginPoseTransition(Pose.CROUCHING);
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        entity.completePoseTransition(Pose.CROUCHING);
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        entity.completePoseTransition(Pose.STANDING);
        entity.isDead = true;
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        entity.isDead = false;
        entity.isBaby = true;
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        entity.isBaby = false;
        entity.riding = new TestEntity(EntityTypes.PLAYER);
        assertTrue(entity.getPossibleHitboxStates().isEmpty());
        TestEntity mob = new TestEntity(EntityTypes.ZOMBIE);
        install(mob, null, location(mob, box(0, 0, 0, 0, 0, 0)));
        assertTrue(mob.getPossibleHitboxStates().isEmpty());
    }

    private static ReachInterpolationData location(PacketEntity entity, SimpleCollisionBox feet) {
        return new ReachInterpolationData(null, feet, entity);
    }

    private static void install(PacketEntity entity, ReachInterpolationData old, ReachInterpolationData current) throws Exception {
        Field oldField = PacketEntity.class.getDeclaredField("oldPacketLocation");
        oldField.setAccessible(true);
        oldField.set(entity, old);
        Field currentField = PacketEntity.class.getDeclaredField("newPacketLocation");
        currentField.setAccessible(true);
        currentField.set(entity, current);
    }

    private static SimpleCollisionBox box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return new SimpleCollisionBox(minX, minY, minZ, maxX, maxY, maxZ, false);
    }

    private static void assertBox(SimpleCollisionBox expected, SimpleCollisionBox actual) {
        assertNotNull(actual);
        assertEquals(expected.minX, actual.minX, 1e-12);
        assertEquals(expected.minY, actual.minY, 1e-12);
        assertEquals(expected.minZ, actual.minZ, 1e-12);
        assertEquals(expected.maxX, actual.maxX, 1e-12);
        assertEquals(expected.maxY, actual.maxY, 1e-12);
        assertEquals(expected.maxZ, actual.maxZ, 1e-12);
    }

    private static void assertContained(SimpleCollisionBox outer, SimpleCollisionBox inner) {
        assertTrue(outer.minX <= inner.minX + 1e-12 && outer.minY <= inner.minY + 1e-12 && outer.minZ <= inner.minZ + 1e-12);
        assertTrue(outer.maxX >= inner.maxX - 1e-12 && outer.maxY >= inner.maxY - 1e-12 && outer.maxZ >= inner.maxZ - 1e-12);
    }

    private static final class TestEntity extends PacketEntity {
        private double scale = 1;

        TestEntity(EntityType type) { super(null, type); }
        @Override protected void initAttributes(GrimPlayer player) { }
        @Override public double getAttributeValue(Attribute attribute) { return scale; }
    }

    private static final class GeometryTestApi extends PacketEventsAPI<Object> {
        @Override public ServerManager getServerManager() { return () -> ServerVersion.V_1_21_11; }
        @Override public boolean isLoaded() { return false; }
        @Override public boolean isInitialized() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public Object getPlugin() { return null; }
        @Override public void init() { throw new UnsupportedOperationException(); }
        @Override public ProtocolManager getProtocolManager() { throw new UnsupportedOperationException(); }
        @Override public PlayerManager getPlayerManager() { throw new UnsupportedOperationException(); }
        @Override public NettyManager getNettyManager() { throw new UnsupportedOperationException(); }
        @Override public ChannelInjector getInjector() { throw new UnsupportedOperationException(); }
    }
}
