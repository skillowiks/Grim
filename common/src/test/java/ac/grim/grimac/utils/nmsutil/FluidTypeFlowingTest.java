package ac.grim.grimac.utils.nmsutil;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FluidTypeFlowingTest {
    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;
    private static PacketEventsAPI<?> previousApi;

    @BeforeAll
    static void initializePacketMappings() {
        previousApi = PacketEvents.getAPI();
        PacketEvents.setAPI(new FluidTestApi());
    }

    @AfterAll
    static void restorePacketApi() {
        PacketEvents.setAPI(previousApi);
    }

    @Test
    void sourceAndFallingFluidsRetainTheirOwnHeight() {
        for (StateType type : new StateType[]{StateTypes.WATER, StateTypes.LAVA}) {
            assertEquals(8 / 9f, height(fluid(type, 0)));
            for (int level = 8; level <= 15; level++) {
                assertEquals(8 / 9f, height(fluid(type, level)));
            }
        }
    }

    @Test
    void flowingLevelsKeepTheirGradientForEarliestAndCurrentModernClients() {
        for (ClientVersion version : new ClientVersion[]{ClientVersion.V_1_13, VERSION}) {
            for (StateType type : new StateType[]{StateTypes.WATER, StateTypes.LAVA}) {
                for (int level = 1; level <= 7; level++) {
                    WrappedBlockState state = WrappedBlockState.getDefaultState(version, type).clone();
                    state.setLevel(level);
                    assertEquals((8 - level) / 9f, FluidTypeFlowing.getOwnFluidHeight(version, state));
                }
            }
        }
    }

    @Test
    void waterloggedAndAquaticBlocksAreSourceHeightButDryBlocksAreEmpty() {
        WrappedBlockState slab = block(StateTypes.OAK_SLAB);
        assertEquals(0f, height(slab));
        slab.setWaterlogged(true);
        assertEquals(8 / 9f, height(slab));
        for (StateType type : new StateType[]{StateTypes.KELP, StateTypes.KELP_PLANT,
                StateTypes.SEAGRASS, StateTypes.TALL_SEAGRASS, StateTypes.BUBBLE_COLUMN}) {
            assertEquals(8 / 9f, height(block(type)));
        }
        assertEquals(0f, height(block(StateTypes.AIR)));
        assertEquals(0f, height(block(StateTypes.STONE)));
    }

    @Test
    void fluidAboveCannotTurnWestwardGradientIntoNorthwardFlow() {
        // Vanilla 1.13 and 1.21.4 FlowingFluid: getOwnHeight at the origin and
        // same-level neighbour. Immersion height is a separate world query.
        for (StateType type : new StateType[]{StateTypes.WATER, StateTypes.LAVA}) {
            Fixture world = new Fixture();
            world.put(0, 0, 0, fluid(type, 7));
            world.put(0, 1, 0, fluid(type, 0));
            world.put(1, 0, 0, fluid(type, 0));
            world.put(0, 0, -1, fluid(type, 7));

            assertEquals(1f, world.immersionHeight(0, 0, 0));
            // Negative east gradient means WEST; the north gradient is zero.
            assertEquals(-7 / 9f, world.sameLevelGradient(1, 0), 1.0E-7f);
            assertEquals(0f, world.sameLevelGradient(0, -1));
            assertEquals(0f, world.oldSameLevelGradient(1, 0));
            assertEquals(7 / 9f, world.oldSameLevelGradient(0, -1), 1.0E-7f);
        }
    }

    @Test
    void coveredFlowingNeighbourDoesNotHideItsLowerOwnHeight() {
        Fixture world = new Fixture();
        world.put(0, 0, 0, fluid(StateTypes.WATER, 0));
        world.put(1, 0, 0, fluid(StateTypes.WATER, 7));
        world.put(1, 1, 0, fluid(StateTypes.WATER, 0));

        assertEquals(7 / 9f, world.sameLevelGradient(1, 0), 1.0E-7f);
        assertEquals(0f, world.oldSameLevelGradient(1, 0));
    }

    @Test
    void lowerNeighbourUsesTheSameOwnHeightAsItsFluidState() {
        Fixture world = new Fixture();
        world.put(0, 0, 0, fluid(StateTypes.WATER, 0));
        world.put(1, -1, 0, fluid(StateTypes.WATER, 7));

        // Vanilla's downward-step gradient: origin - (lower neighbour - 8/9).
        // It can exceed one before the final flow vector is normalized.
        assertEquals(1.6666667f, world.lowerGradient(1, 0));
        world.put(1, -1, 0, fluid(StateTypes.WATER, 8));
        assertEquals(8 / 9f, world.lowerGradient(1, 0), 1.0E-7f);
    }

    private static float height(WrappedBlockState state) {
        return FluidTypeFlowing.getOwnFluidHeight(VERSION, state);
    }

    private static WrappedBlockState block(StateType type) {
        return WrappedBlockState.getDefaultState(VERSION, type).clone();
    }

    private static WrappedBlockState fluid(StateType type, int level) {
        WrappedBlockState state = block(type);
        state.setLevel(level);
        return state;
    }

    private record Position(int x, int y, int z) {
    }

    /** Sparse block fixture with independent immersion and flow-height queries. */
    private static final class Fixture {
        private final Map<Position, WrappedBlockState> blocks = new HashMap<>();

        void put(int x, int y, int z, WrappedBlockState state) {
            blocks.put(new Position(x, y, z), state);
        }

        float ownHeight(int x, int y, int z) {
            WrappedBlockState state = blocks.get(new Position(x, y, z));
            return state == null ? 0 : height(state);
        }

        float immersionHeight(int x, int y, int z) {
            if (ownHeight(x, y, z) == 0) return 0;
            return ownHeight(x, y + 1, z) > 0 ? 1 : ownHeight(x, y, z);
        }

        float sameLevelGradient(int x, int z) {
            return FluidTypeFlowing.getFlowHeightDifference(ownHeight(0, 0, 0), ownHeight(x, 0, z), false);
        }

        float oldSameLevelGradient(int x, int z) {
            return Math.min(immersionHeight(0, 0, 0), 8 / 9f)
                    - Math.min(immersionHeight(x, 0, z), 8 / 9f);
        }

        float lowerGradient(int x, int z) {
            return FluidTypeFlowing.getFlowHeightDifference(ownHeight(0, 0, 0), ownHeight(x, -1, z), true);
        }
    }

    private static final class FluidTestApi extends PacketEventsAPI<Object> {
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
            throw new UnsupportedOperationException("No server in fluid tests");
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
            throw new UnsupportedOperationException("No protocol transport in fluid tests");
        }

        @Override
        public PlayerManager getPlayerManager() {
            throw new UnsupportedOperationException("No players in fluid tests");
        }

        @Override
        public NettyManager getNettyManager() {
            throw new UnsupportedOperationException("No network in fluid tests");
        }

        @Override
        public ChannelInjector getInjector() {
            throw new UnsupportedOperationException("No channel injection in fluid tests");
        }
    }
}
