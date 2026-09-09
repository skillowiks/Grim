package ac.grim.grimac.events.packets;

import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.Property;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes.PropertyModifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AttributePacketSnapshotTest {
    @Test
    void deferredSnapshotDoesNotRetainMutablePacketPropertiesOrModifiers() {
        PropertyModifier modifier = new PropertyModifier(UUID.randomUUID(), -0.45, PropertyModifier.Operation.MULTIPLY_TOTAL);
        // Unknown attributes are permitted in packets; copying must preserve them too.
        Property packet = new Property((Attribute) null, 0.1, new ArrayList<>(List.of(modifier)));
        List<Property> snapshot = PacketEntityReplication.copyAttributes(List.of(packet));
        packet.setValue(0.9);
        modifier.setAmount(7);
        packet.getModifiers().clear();
        assertEquals(0.1, snapshot.get(0).getValue());
        assertEquals(-0.45, snapshot.get(0).getModifiers().get(0).getAmount());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get(0).getModifiers().clear());
    }
}
