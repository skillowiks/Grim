package ac.grim.grimac.checks.impl.aim.triggerbot;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A constant-space fence for outbound state that may invalidate observations.
 * The caller opens it before a relevant send and seals that token only after the
 * physical send, using the transaction counter then. An acknowledgement must
 * pass that counter: an earlier transaction cannot establish packet delivery.
 * This helper neither sends transactions nor accesses player/packet state.
 */
public final class TriggerBotStateFence {
    private static final long UNSEALED = Long.MIN_VALUE;
    private static final VarHandle AFTER_SENT;

    static {
        try {
            AFTER_SENT = MethodHandles.lookup().findVarHandle(Token.class, "afterSent", long.class);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private final AtomicReference<Token> latest = new AtomicReference<>();

    /** One token is allocated per relevant send; its identity belongs to this fence. */
    public static final class Token {
        // Written only before successful CAS publication, immutable afterwards.
        private long generation;
        private volatile long afterSent = UNSEALED;

        private Token() {
        }

        public long generation() {
            return generation;
        }
    }

    /**
     * Opens a new fence immediately, replacing any previous one. CAS defines the
     * order of concurrent opens; retries reuse the same unpublished token.
     */
    public Token pending() {
        Token token = new Token();
        Token previous;
        do {
            previous = latest.get();
            token.generation = previous == null ? 1 : previous.generation + 1;
        } while (!latest.compareAndSet(previous, token));
        return token;
    }

    /**
     * Seal once, after the relevant packet was sent. Completion of a replaced or
     * foreign token cannot alter the newest fence. Repeated completion is a no-op.
     */
    public void sent(Token token, int lastTransactionSent) {
        if (token == null || latest.get() != token) return;
        // A concurrent pending() can replace token here. Sealing this old token
        // still cannot modify the newly published token or clear its fence.
        AFTER_SENT.compareAndSet(token, UNSEALED, (long) lastTransactionSent);
    }

    /** Read-only; satisfying the fence does not discard its generation. */
    public boolean isPending(int lastTransactionReceived) {
        Token token = latest.get();
        if (token == null) return false;
        long afterSent = token.afterSent;
        return afterSent == UNSEALED || lastTransactionReceived <= afterSent;
    }

    /** Receive-thread consumers can detect new sends even after acknowledgement. */
    public long generation() {
        Token token = latest.get();
        return token == null ? 0 : token.generation;
    }
}
