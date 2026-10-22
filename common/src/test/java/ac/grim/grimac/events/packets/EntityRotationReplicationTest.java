package ac.grim.grimac.events.packets;

import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.data.PacketStateData;
import ac.grim.grimac.utils.data.ReachInterpolationData;
import ac.grim.grimac.utils.data.TrackerData;
import ac.grim.grimac.utils.data.interpolation.LegacyEntityInterpolation;
import ac.grim.grimac.utils.data.packetentity.PacketEntity;
import ac.grim.grimac.utils.latency.CompensatedEntities;
import ac.grim.grimac.utils.latency.LatencyUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual packet routing, deferred transactions and PacketEntity interpolation; controlled transport/player. */
class EntityRotationReplicationTest {
    private PacketEventsAPI<?> previousApi;

    @BeforeEach void mappings() {
        previousApi = PacketEvents.getAPI();
        var api = mock(PacketEventsAPI.class);
        when(api.getServerManager()).thenReturn((ServerManager) () -> ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
    }

    @AfterEach void restoreApi() { PacketEvents.setAPI(previousApi); }

    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static ReachInterpolationData interpolation(PacketEntity entity) throws Exception {
        Field strategy = PacketEntity.class.getDeclaredField("interpolation");
        strategy.setAccessible(true);
        Field field = LegacyEntityInterpolation.class.getDeclaredField("newPacketLocation");
        field.setAccessible(true);
        return (ReachInterpolationData) field.get(strategy.get(entity));
    }

    private static final class Target extends PacketEntity {
        Target(GrimPlayer player) { super(player, null, EntityTypes.PLAYER, 0, 64, 0); }
        @Override protected void initAttributes(GrimPlayer player) { }
        @Override public double getAttributeValue(Attribute attribute) { return 1; }
    }

    private static final class Scene {
        final GrimPlayer player = mock(GrimPlayer.class);
        final AtomicInteger sent = new AtomicInteger(100), received = new AtomicInteger(0);
        final PacketEntityReplication listener = mock(PacketEntityReplication.class, CALLS_REAL_METHODS);
        final Target target;
        final TrackerData tracked;
        final LatencyUtils latency;
        final ClientVersion version;

        Scene(ClientVersion version) throws Exception {
            this.version = version;
            when(player.getClientVersion()).thenReturn(version);
            set(player, GrimPlayer.class, "lastTransactionSent", sent);
            set(player, GrimPlayer.class, "lastTransactionReceived", received);
            set(player, GrimPlayer.class, "packetStateData", new PacketStateData());
            doAnswer(i -> { sent.incrementAndGet(); return null; }).when(player).sendTransaction();
            latency = new LatencyUtils(player);
            set(player, GrimPlayer.class, "latencyUtils", latency);
            var entities = mock(CompensatedEntities.class);
            set(player, GrimPlayer.class, "compensatedEntities", entities);
            target = new Target(player);
            tracked = new TrackerData(0, 64, 0, 0, 0, EntityTypes.PLAYER, 0);
            when(entities.getTrackedEntity(42)).thenReturn(tracked);
            when(entities.getEntity(42)).thenReturn(target);
            set(listener, GrimProcessor.class, "player", player);
            set(listener, PacketEntityReplication.class, "hasSentPreWavePacket", new AtomicBoolean(true));
        }

        void send(PacketWrapper<?> wrapper) {
            var event = mock(PacketSendEvent.class);
            var user = mock(User.class);
            when(user.getClientVersion()).thenReturn(version);
            when(event.getUser()).thenReturn(user);
            when(event.getServerVersion()).thenReturn(ServerVersion.V_1_21_11);
            when(event.getPacketId()).thenReturn(wrapper.getNativePacketId());
            when(event.getPacketType()).thenReturn(wrapper.getPacketTypeData().getPacketType());
            // PacketEvents' normal cached-wrapper handoff. Byte decoding itself is outside this fixture.
            doReturn(wrapper).when(event).getLastUsedWrapper();
            listener.onPacketSend(event);
            assertFalse(event.isCancelled());
        }

        void acknowledge(int transaction) {
            received.set(transaction);
            latency.handleNettySyncTransaction(transaction);
        }

        void full(float yaw, float pitch) {
            send(new WrapperPlayServerEntityPositionSync(42,
                new EntityPositionData(new Vector3d(1, 64, 0), new Vector3d(), yaw, pitch), false));
            acknowledge(sent.get());
            target.onMovement(true);
        }
    }

    @ParameterizedTest @ValueSource(ints = {-128, -64, 0, 1, 64, 127})
    void rotationOnlyPreservesDegreesAndDoesNotRestartIdenticalModernTarget(int packedYaw) throws Exception {
        var scene = new Scene(ClientVersion.V_1_21_11);
        float yaw = packedYaw * (360.0F / 256), pitch = 45;
        scene.full(yaw, pitch);
        var before = interpolation(scene.target);
        scene.send(new WrapperPlayServerEntityRotation(42, yaw, pitch, false));
        assertSame(before, interpolation(scene.target), "packet application must wait for its transaction");
        scene.acknowledge(scene.sent.get());
        assertSame(before, interpolation(scene.target), "identical target must retain interpolation progress");
        assertEquals(yaw, scene.tracked.getXRot());
        assertEquals(pitch, scene.tracked.getYRot());
    }

    @ParameterizedTest @ValueSource(ints = {-128, -64, 0, 1, 64, 127})
    void combinedUpdateUsesTheSameAngleUnitsAsFullSync(int packedYaw) throws Exception {
        var scene = new Scene(ClientVersion.V_1_21_9);
        float yaw = packedYaw * (360.0F / 256), pitch = -45;
        scene.full(yaw, pitch);
        var before = interpolation(scene.target);
        scene.send(new WrapperPlayServerEntityRelativeMoveAndRotation(42, 0, 0, 0, yaw, pitch, false));
        scene.acknowledge(scene.sent.get());
        assertSame(before, interpolation(scene.target));
        assertEquals(yaw, scene.tracked.getXRot());
        assertEquals(pitch, scene.tracked.getYRot());
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_20", "V_1_20_2", "V_1_21_4", "V_1_21_5", "V_1_21_7", "V_1_21_9", "V_1_21_11"})
    void preservesVersionSpecificRotationOnlyInterpolation(ClientVersion version) throws Exception {
        var scene = new Scene(version);
        scene.full(90, 45);
        var before = interpolation(scene.target);
        scene.send(new WrapperPlayServerEntityRotation(42, 90, 45, false));
        scene.acknowledge(scene.sent.get());
        assertEquals(90, scene.tracked.getXRot());
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_9)) assertSame(before, interpolation(scene.target));
        else assertNotSame(before, interpolation(scene.target), "older clients must keep their existing restart/freeze rules");
    }

    @ParameterizedTest
    @EnumSource(value = ClientVersion.class, names = {"V_1_21_11", "V_26_3"})
    void relativeUpdatesKeepDegreesAndReachTheirTargetAcrossInterpolationModels(ClientVersion version) throws Exception {
        var scene = new Scene(version);
        scene.full(90, 45);
        scene.send(new WrapperPlayServerEntityRelativeMoveAndRotation(42, 0.25, 0, 0, -90, -45, false));
        scene.acknowledge(scene.sent.get() + 1);
        assertEquals(-90, scene.tracked.getXRot());
        assertEquals(-45, scene.tracked.getYRot());
        assertEquals(1.25, scene.target.trackedServerPosition.getPos().x);

        scene.send(new WrapperPlayServerEntityRotation(42, 45, 90, false));
        scene.acknowledge(scene.sent.get() + 1);
        assertEquals(45, scene.tracked.getXRot());
        assertEquals(90, scene.tracked.getYRot());
        for (int tick = 0; tick < 20; tick++) scene.target.onMovement(true);
        var bounds = scene.target.getPossibleLocationBoxes();
        assertEquals(1.25, bounds.minX, 1e-9);
        assertEquals(1.25, bounds.maxX, 1e-9);
        assertEquals(64, bounds.minY, 1e-9);
        assertEquals(64, bounds.maxY, 1e-9);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void genuineRotationAndPositionChangesStillRestartInterpolation(boolean position) throws Exception {
        var scene = new Scene(ClientVersion.V_1_21_11);
        scene.full(90, 45);
        var before = interpolation(scene.target);
        if (position) scene.send(new WrapperPlayServerEntityRelativeMoveAndRotation(42, 0.25, 0, 0, 90, 45, false));
        else scene.send(new WrapperPlayServerEntityRotation(42, -90, -45, false));
        scene.acknowledge(scene.sent.get());
        assertNotSame(before, interpolation(scene.target));
        assertEquals(position ? 1.25 : 1, scene.target.trackedServerPosition.getPos().x);
    }
}
