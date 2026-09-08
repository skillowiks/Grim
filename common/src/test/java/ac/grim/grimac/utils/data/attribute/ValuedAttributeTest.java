package ac.grim.grimac.utils.data.attribute;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.attribute.Attributes;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.PropertyModifier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ac.grim.grimac.utils.latency.CompensatedEntities.SNOW_MODIFIER_UUID;
import static ac.grim.grimac.utils.latency.CompensatedEntities.SPRINTING_MODIFIER_UUID;
import static com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.PropertyModifier.Operation.*;
import static org.junit.jupiter.api.Assertions.*;

class ValuedAttributeTest {
    private static final double EPSILON = 1.0E-12;
    private static PacketEventsAPI<?> previousApi;

    @BeforeAll
    static void initializePacketMappings() {
        previousApi = PacketEvents.getAPI();
        PacketEvents.setAPI(new AttributeTestApi());
    }

    @AfterAll
    static void restorePacketApi() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void preservesSprintModifierInMutablePacketWhileCalculatingBaseMovementSpeed() {
        PropertyModifier sprint = named("sprinting", 0.3, MULTIPLY_TOTAL);
        PropertyModifier speed = named("effect.speed", 0.6, MULTIPLY_TOTAL);
        List<PropertyModifier> modifiers = new ArrayList<>(List.of(sprint, speed));
        Property packet = property(0.1, modifiers);
        ValuedAttribute value = movementSpeed();

        assertEquals(0.16, value.with(packet), EPSILON);
        assertSame(modifiers, packet.getModifiers());
        assertEquals(List.of(sprint, speed), modifiers);
        assertEquals(0.208, packet.calcValue0(), EPSILON);
        assertNotSame(packet, value.property().orElseThrow());
        assertNotSame(modifiers, value.property().orElseThrow().getModifiers());
    }

    @Test
    void acceptsImmutablePacketModifiersAndRecognizesLegacySprintUuid() {
        PropertyModifier sprint = new PropertyModifier(SPRINTING_MODIFIER_UUID, 0.3, MULTIPLY_TOTAL);
        Property packet = property(0.1, List.of(sprint, named("effect.speed", 0.4, MULTIPLY_TOTAL)));
        ValuedAttribute value = movementSpeed();

        assertEquals(0.14, value.with(packet), EPSILON);
        value.recalculate();
        assertEquals(0.14, value.get(), EPSILON);
        assertEquals(2, packet.getModifiers().size());
        assertSame(sprint, packet.getModifiers().get(0));
    }

    @Test
    void preservesNormalOperationOrderAndOtherSpeedModifiers() {
        Property packet = property(0.1, List.of(
                named("equipment_addition", 0.05, ADDITION),
                named("equipment_base", 0.5, MULTIPLY_BASE),
                named("effect.speed", 0.6, MULTIPLY_TOTAL),
                named("effect.slowness", -0.15, MULTIPLY_TOTAL),
                named("sprinting", 0.3, MULTIPLY_TOTAL)));
        ValuedAttribute value = movementSpeed();

        assertEquals(0.306, value.with(packet), EPSILON);
        value.recalculate();
        assertEquals(0.306, value.get(), EPSILON);
        assertEquals(5, packet.getModifiers().size());
    }

    @Test
    void repeatedReadersAndRecalculationsCannotConsumePacketSprintState() {
        Property packet = property(0.1, new ArrayList<>(List.of(
                named("sprinting", 0.3, MULTIPLY_TOTAL),
                named("effect.speed", 0.6, MULTIPLY_TOTAL))));
        ValuedAttribute firstReader = movementSpeed();
        ValuedAttribute secondReader = movementSpeed();

        for (int tick = 0; tick < 3; tick++) {
            assertEquals(0.16, firstReader.with(packet), EPSILON);
            firstReader.recalculate();
            assertEquals(0.16, secondReader.with(packet), EPSILON);
            assertEquals(0.208, packet.calcValue0(), EPSILON);
            assertEquals("sprinting", packet.getModifiers().get(0).getName().getKey());
        }
    }

    @Test
    void laterPacketObjectAndModifierChangesDoNotChangeStoredState() {
        PropertyModifier speed = named("effect.speed", 0.6, MULTIPLY_TOTAL);
        Property packet = property(0.1, new ArrayList<>(List.of(speed)));
        ValuedAttribute value = movementSpeed();
        value.with(packet);

        packet.setValue(0.5);
        speed.setAmount(7.0);
        speed.setOperation(ADDITION);
        speed.setName(new ResourceLocation("test:changed"));
        packet.getModifiers().clear();
        value.recalculate();

        assertEquals(0.16, value.get(), EPSILON);
        Property stored = value.property().orElseThrow();
        assertEquals(0.1, stored.getValue());
        assertEquals("effect.speed", stored.getModifiers().get(0).getName().getKey());
        assertEquals(0.6, stored.getModifiers().get(0).getAmount());
        assertEquals(MULTIPLY_TOTAL, stored.getModifiers().get(0).getOperation());
    }

    @Test
    void localPowderSnowChangesSurviveBothRecalculationsWithoutChangingPacket() {
        PropertyModifier snow = new PropertyModifier(SNOW_MODIFIER_UUID, -0.025, ADDITION);
        Property packet = property(0.1, List.of(
                named("sprinting", 0.3, MULTIPLY_TOTAL),
                named("effect.speed", 0.6, MULTIPLY_TOTAL), snow));
        ValuedAttribute value = movementSpeed();
        assertEquals(0.12, value.with(packet), EPSILON);
        Property local = value.property().orElseThrow();

        // PlayerBaseTick retains this reference while removing and re-adding snow.
        local.getModifiers().removeIf(modifier -> SNOW_MODIFIER_UUID.equals(modifier.getUUID()));
        value.recalculate();
        assertEquals(0.16, value.get(), EPSILON);
        assertSame(local, value.property().orElseThrow());

        local.getModifiers().add(new PropertyModifier(SNOW_MODIFIER_UUID, -0.05, ADDITION));
        value.recalculate();
        assertEquals(0.08, value.get(), EPSILON);
        assertSame(local, value.property().orElseThrow());
        assertEquals(3, packet.getModifiers().size());
        assertSame(snow, packet.getModifiers().get(2));
        assertEquals(-0.025, snow.getAmount());
    }

    @Test
    void temporaryPropertyOverrideCanRestorePreviousLocalState() {
        ValuedAttribute value = movementSpeed();
        value.with(property(0.1, List.of(named("effect.speed", 0.6, MULTIPLY_TOTAL))));
        Property previous = value.property().orElseThrow();
        List<PropertyModifier> temporaryModifiers = new ArrayList<>(previous.getModifiers());
        temporaryModifiers.add(named("suffocating", -0.5, MULTIPLY_TOTAL));

        assertEquals(0.08, value.with(property(previous.getValue(), temporaryModifiers)), EPSILON);
        assertEquals(0.16, value.with(previous), EPSILON);
        assertEquals(1, previous.getModifiers().size());
    }

    @Test
    void stillClampsResultAndAppliesRewriters() {
        ValuedAttribute value = ValuedAttribute.ranged(Attributes.MOVEMENT_SPEED, 0.1, 0, 1)
                .withSetRewriter((oldValue, newValue) -> newValue / 2)
                .withGetRewriter(speed -> speed * 3);

        assertEquals(0.5, value.with(property(2, List.of())), EPSILON);
        assertEquals(1.5, value.get(), EPSILON);
        value.recalculate();
        assertEquals(1.5, value.get(), EPSILON);
        value.reset();
        assertTrue(value.property().isEmpty());
        assertEquals(0.3, value.get(), EPSILON);
    }

    private static ValuedAttribute movementSpeed() {
        return ValuedAttribute.ranged(Attributes.MOVEMENT_SPEED, 0.1, 0, 1024);
    }

    private static Property property(double base, List<PropertyModifier> modifiers) {
        return new Property(Attributes.MOVEMENT_SPEED, base, modifiers);
    }

    private static PropertyModifier named(String key, double amount, PropertyModifier.Operation operation) {
        return new PropertyModifier(new ResourceLocation("minecraft:" + key), amount, operation);
    }

    /** Provides real registry resources and attribute clamping without starting a server. */
    private static final class AttributeTestApi extends PacketEventsAPI<Object> {
        @Override
        public ServerManager getServerManager() {
            return () -> ServerVersion.V_1_21_4;
        }

        @Override
        public boolean isLoaded() {
            return false;
        }

        @Override
        public void init() {
            throw new UnsupportedOperationException("No server in attribute tests");
        }

        @Override
        public boolean isInitialized() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return null;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            throw new UnsupportedOperationException("No protocol transport in attribute tests");
        }

        @Override
        public PlayerManager getPlayerManager() {
            throw new UnsupportedOperationException("No players in attribute tests");
        }

        @Override
        public NettyManager getNettyManager() {
            throw new UnsupportedOperationException("No network in attribute tests");
        }

        @Override
        public ChannelInjector getInjector() {
            throw new UnsupportedOperationException("No channel injection in attribute tests");
        }
    }
}
