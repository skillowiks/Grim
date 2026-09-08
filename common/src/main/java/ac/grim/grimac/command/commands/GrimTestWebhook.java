package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.data.webhook.discord.WebhookMessage;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

public class GrimTestWebhook implements BuildableCommand {
    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        commandManager.command(
                commandManager.commandBuilder("grim", "grimac")
                        .literal("testwebhook")
                        .permission("grim.testwebhook")
                        .handler(this::handleTestWebhook)
        );
    }

    private void handleTestWebhook(@NotNull CommandContext<Sender> context) {
        if (GrimAPI.INSTANCE.getDiscordManager().isDisabled()) {
            context.sender().sendMessage(MessageUtil.miniMessage(GrimAPI.INSTANCE.getConfigManager().getWebhookNotEnabled()));
            return;
        }

        Sender sender = context.sender();
        Component successMessage = MessageUtil.miniMessage(GrimAPI.INSTANCE.getConfigManager().getWebhookTestSucceeded());
        Component failureMessage = MessageUtil.miniMessage(GrimAPI.INSTANCE.getConfigManager().getWebhookTestFailed());
        CompletableFuture<Boolean> result;
        try {
            WebhookMessage webhookMessage = new WebhookMessage().content(GrimAPI.INSTANCE.getConfigManager().getWebhookTestMessage());
            result = GrimAPI.INSTANCE.getDiscordManager().sendWebhookMessage(webhookMessage);
        } catch (RuntimeException exception) {
            sendResult(sender, successMessage, failureMessage, false);
            logFailure(exception);
            return;
        }
        result.whenCompleteAsync((successful, throwable) -> {
            sendResult(sender, successMessage, failureMessage, successful);
            if (throwable != null) {
                logFailure(throwable);
            }
        }, task -> GrimAPI.INSTANCE.getScheduler().getGlobalRegionScheduler().run(GrimAPI.INSTANCE.getGrimPlugin(), task));
    }

    static void sendResult(Sender sender, Component successMessage, Component failureMessage, Boolean successful) {
        sender.sendMessage(Boolean.TRUE.equals(successful) ? successMessage : failureMessage);
    }

    private static void logFailure(Throwable throwable) {
        // Transport errors may contain a webhook token in their message or stack trace.
        LogUtil.error("Exception while sending a Discord webhook test alert (" + throwable.getClass().getSimpleName() + ")");
    }
}
