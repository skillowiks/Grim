package ac.grim.grimac.command.commands;

import ac.grim.grimac.platform.api.sender.Sender;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GrimTestWebhookTest {
    @Test
    void exceptionalCompletionSendsFailureInsteadOfUnboxingNull() {
        List<Component> messages = new ArrayList<>();
        Sender sender = recordingSender(messages);
        Component success = Component.text("success"), failure = Component.text("failure");
        CompletableFuture.<Boolean>failedFuture(new IllegalStateException("offline"))
                .whenComplete((result, throwable) -> GrimTestWebhook.sendResult(sender, success, failure, result));
        assertEquals(List.of(failure), messages);
    }

    @Test
    void successfulAndRejectedResultsEachProduceOneReply() {
        List<Component> messages = new ArrayList<>();
        Sender sender = recordingSender(messages);
        Component success = Component.text("success"), failure = Component.text("failure");
        GrimTestWebhook.sendResult(sender, success, failure, true);
        GrimTestWebhook.sendResult(sender, success, failure, false);
        assertEquals(List.of(success, failure), messages);
    }

    private static Sender recordingSender(List<Component> messages) {
        return (Sender) Proxy.newProxyInstance(Sender.class.getClassLoader(), new Class<?>[]{Sender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage")) {
                        messages.add((Component) args[0]);
                        return null;
                    }
                    throw new AssertionError("Unexpected Sender call: " + method.getName());
                });
    }
}
