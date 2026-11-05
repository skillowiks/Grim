package ac.grim.grimac.checks.impl.velocity;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.util.LpVector3d;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.experimental.UtilityClass;

@UtilityClass
public class VectorPrecisionConverter {

    private static final class ServerVersionHolder {
        private static final ServerVersion VERSION = PacketEvents.getAPI().getServerManager().getVersion();
    }

    public static Vector3d convert(ClientVersion version, Vector3d vector) {
        ServerVersion serverVersion = ServerVersionHolder.VERSION;
        if (version.isNewerThanOrEquals(ClientVersion.V_1_21_9) && serverVersion.isOlderThanOrEquals(ServerVersion.V_1_21_8)) {
            return VectorPrecisionConverter.legacyToLp(vector);
        } else if (version.isOlderThanOrEquals(ClientVersion.V_1_21_7) && serverVersion.isNewerThanOrEquals(ServerVersion.V_1_21_9)) {
            return VectorPrecisionConverter.lpToLegacy(vector);
        }

        return vector;
    }

    public static Vector3d legacyToLp(Vector3d legacy) {
        ByteBuf buf = Unpooled.buffer();
        try {
            PacketWrapper<?> wrapper = PacketWrapper.createUniversalPacketWrapper(buf);
            LpVector3d.write(wrapper, legacy);
            return LpVector3d.read(wrapper);
        } finally {
            buf.release();
        }
    }

    public static Vector3d lpToLegacy(Vector3d lp) {
        return new Vector3d(
                toLegacyVelocity(lp.x) / 8000d,
                toLegacyVelocity(lp.y) / 8000d,
                toLegacyVelocity(lp.z) / 8000d
        );
    }

    private static short toLegacyVelocity(double value) {
        // Match ViaBackwards VelocityUtil: LP encoding is lossy, so round to the
        // nearest legacy unit and saturate before narrowing. Truncation can turn
        // a client's 0.003 velocity into 0.002875, which prediction then zeros.
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value * 8000d)));
    }

}
