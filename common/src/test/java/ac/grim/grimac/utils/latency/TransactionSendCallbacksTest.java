package ac.grim.grimac.utils.latency;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.*;

class TransactionSendCallbacksTest {
    @Test
    void callbackReceivesItsActualSendIndexDespiteInterleavingAndRunsOnce() {
        TransactionSendCallbacks callbacks = new TransactionSendCallbacks();
        AtomicInteger boundary = new AtomicInteger(-1);
        callbacks.put((short) -8, boundary::set);
        callbacks.put((short) -9, ignored -> fail("Another ping must not supply this boundary"));
        IntConsumer sent = callbacks.take((short) -8);
        assertNotNull(sent);
        assertEquals(-1, boundary.get()); // Taken but cancelled: no after-send callback.
        sent.accept(123);
        assertEquals(123, boundary.get());
        assertNull(callbacks.take((short) -8));
    }

    @Test
    void ordinaryIdReuseAndFailedWriteCleanupCannotDeliverOrphanCallback() {
        TransactionSendCallbacks callbacks = new TransactionSendCallbacks();
        IntConsumer first = ignored -> fail("Orphan callback");
        callbacks.put((short) -8, first);
        callbacks.put((short) -8, null);
        assertNull(callbacks.take((short) -8));
        callbacks.put((short) -8, first);
        callbacks.remove((short) -8, first);
        assertNull(callbacks.take((short) -8));
        IntConsumer replacement = ignored -> { };
        callbacks.put((short) -8, replacement);
        callbacks.remove((short) -8, first);
        assertSame(replacement, callbacks.take((short) -8));
    }

    @Test
    void failedWritesAreBoundedWithoutRunningEvictedCallbacks() {
        TransactionSendCallbacks callbacks = new TransactionSendCallbacks();
        IntConsumer callback = ignored -> fail("Unsent callbacks must not run");
        for (int i = 0; i <= TransactionSendCallbacks.MAX_PENDING; i++) callbacks.put((short) -i, callback);
        assertNull(callbacks.take((short) 0));
        for (int i = 1; i <= TransactionSendCallbacks.MAX_PENDING; i++) assertSame(callback, callbacks.take((short) -i));
    }
}
