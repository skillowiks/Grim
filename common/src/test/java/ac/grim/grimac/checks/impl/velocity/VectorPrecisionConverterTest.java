package ac.grim.grimac.checks.impl.velocity;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.util.LpVector3d;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VectorPrecisionConverterTest {

    @Test
    void roundsActualLpEncodedServerVelocities() {
        for (double[] sample : new double[][]{
                {0.0030162615090425808, 0.003},
                {0.08307781780646721, 0.083125},
                {-0.07544406518948656, -0.0755}
        }) {
            ByteBuf payloadBuffer = Unpooled.buffer();
            try {
                // Use the real LP codec with direct buffer access, without a running
                // PacketEvents server singleton in the unit-test JVM.
                PacketWrapper<?> wrapper = new BufferPacketWrapper(payloadBuffer);
                LpVector3d.write(wrapper, new Vector3d(0, sample[0], 0));
                Vector3d decoded = LpVector3d.read(wrapper);
                assertEquals(sample[1], VectorPrecisionConverter.lpToLegacy(decoded).y);
            } finally {
                payloadBuffer.release();
            }
        }
    }

    @Test
    void roundsLossyComponentsToClientVelocity() {
        Vector3d result = VectorPrecisionConverter.lpToLegacy(new Vector3d(0.39998779, 0.08307781780646721, -0.07544406518948656));

        assertEquals(0.4, result.x);
        assertEquals(0.083125, result.y);
        assertEquals(-0.0755, result.z);
    }

    @Test
    void preservesApexVelocityAtMovementThreshold() {
        // An LP component just below 0.003 must reconstruct the client's 24/8000,
        // not 23/8000, or the movement threshold incorrectly zeros it.
        Vector3d result = VectorPrecisionConverter.lpToLegacy(new Vector3d(0, 0.00299, 0));
        assertEquals(0.003, result.y);

        assertVerticalTrace(result.y, new double[]{
                0.0030000000000001137, -0.07546000146865595,
                -0.15235080440444904, -0.22770379274810182,
                -0.3015497227621182, -0.3739187355843683
        });
    }

    @Test
    void reproducesPreApexVelocityTraceFromDebugLog() {
        double velocity = VectorPrecisionConverter.lpToLegacy(new Vector3d(0, 0.08307781780646721, 0)).y;

        assertVerticalTrace(velocity, new double[]{
                0.08312499999999545, 0.0030625000595989604,
                -0.07539875140905394, -0.15229077934486668,
                -0.22764496818857083, -0.3014920746926606,
                -0.3738622404751908
        });
    }

    @Test
    void saturatesLargeVelocitiesInsteadOfWrappingTheirSign() {
        Vector3d result = VectorPrecisionConverter.lpToLegacy(new Vector3d(5, -5, 0));
        assertEquals(32767 / 8000d, result.x);
        assertEquals(-32768 / 8000d, result.y);
        assertEquals(0, result.z);
    }

    @Test
    void preservesEveryRepresentableLegacyComponent() {
        for (int component = Short.MIN_VALUE; component <= Short.MAX_VALUE; component++) {
            double velocity = component / 8000d;
            Vector3d result = VectorPrecisionConverter.lpToLegacy(new Vector3d(velocity, velocity, velocity));
            assertEquals(velocity, result.x);
            assertEquals(velocity, result.y);
            assertEquals(velocity, result.z);
        }
    }

    private static final class BufferPacketWrapper extends PacketWrapper<BufferPacketWrapper> {
        private final ByteBuf payload;

        private BufferPacketWrapper(ByteBuf payload) {
            super(ClientVersion.UNKNOWN, ServerVersion.V_1_21_11, -2);
            this.payload = payload;
        }

        @Override
        public short readUnsignedByte() {
            return payload.readUnsignedByte();
        }

        @Override
        public long readUnsignedInt() {
            return payload.readUnsignedInt();
        }

        @Override
        public void writeByte(int value) {
            payload.writeByte(value);
        }

        @Override
        public void writeShortLE(int value) {
            payload.writeShortLE(value);
        }

        @Override
        public void writeInt(int value) {
            payload.writeInt(value);
        }
    }

    private static void assertVerticalTrace(double velocity, double[] recordedMovements) {
        for (double recorded : recordedMovements) {
            if (Math.abs(velocity) < 0.003) velocity = 0;
            assertEquals(recorded, velocity, 1e-12);
            velocity = (velocity - 0.08) * (double) 0.98F;
        }
    }
}
