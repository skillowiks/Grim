package ac.grim.grimac.events.packets;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PacketPlayerAttackTest {
    @Test
    void pre121ClientsUseEnchantmentsAndIgnoreTheReceivedAttribute() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_8, ClientVersion.V_1_9, ClientVersion.V_1_20_5}) {
            assertEquals(2.0F, PacketPlayerAttack.clientAttackKnockback(version, 2, 5.0));
            assertEquals(0.0F, PacketPlayerAttack.clientAttackKnockback(version, 0, 5.0));
            // Keep the existing negative-enchantment handling in the attack listener.
            assertEquals(-1.0F, PacketPlayerAttack.clientAttackKnockback(version, -1, 5.0));
        }
    }

    @Test
    void modernClientsUseFractionalAttributesAndDoNotPredictServerEnchantments() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_21, ClientVersion.V_1_21_4, ClientVersion.V_1_21_9}) {
            assertEquals(0.25F, PacketPlayerAttack.clientAttackKnockback(version, 0, 0.25));
            assertEquals(0.25F, PacketPlayerAttack.clientAttackKnockback(version, 2, 0.25));
            assertEquals(0.0F, PacketPlayerAttack.clientAttackKnockback(version, 2, 0.0));
        }
    }

    @Test
    void modernAttributeUsesTheClientFloatConversion() {
        assertEquals((float) 0.1, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_4, 0, 0.1));
        assertEquals(0.0F, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_4, 0, Double.MIN_VALUE));
        assertEquals(Float.MIN_VALUE, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_4, 0, Float.MIN_VALUE));
    }

    @Test
    void version12111HalvesKnockbackAfterConvertingToFloat() {
        assertEquals(0.125F, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_11, 2, 0.25));
        assertEquals(2.5F, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_11, 0, 5.0));
        assertEquals(0.0F, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_11, 2, 0.0));
        // Positive doubles can become zero at this version's float division;
        // testing the unconverted attribute would incorrectly require a slowdown.
        assertEquals(0.0F, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_11, 0, Float.MIN_VALUE));
        assertEquals(Float.MIN_VALUE, PacketPlayerAttack.clientAttackKnockback(ClientVersion.V_1_21_11, 0, 2.0 * Float.MIN_VALUE));
    }
}
