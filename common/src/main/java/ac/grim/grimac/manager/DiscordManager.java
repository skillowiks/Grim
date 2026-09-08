package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.GrimUser;
import ac.grim.grimac.manager.init.ReloadableInitable;
import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import ac.grim.grimac.utils.data.webhook.discord.CompiledDiscordTemplate;
import ac.grim.grimac.utils.data.webhook.discord.DiscordAlertFormatter;
import ac.grim.grimac.utils.data.webhook.discord.DiscordWebhookQueue;
import ac.grim.grimac.utils.data.webhook.discord.Embed;
import ac.grim.grimac.utils.data.webhook.discord.WebhookMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

public class DiscordManager implements StartableInitable, ReloadableInitable {
    private static final Duration TIMEOUT = Duration.ofMillis(CommonGrimArguments.URL_TIMEOUT.value());
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final DiscordWebhookQueue queue = new DiscordWebhookQueue(
            request -> CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()),
            System::currentTimeMillis, LogUtil::warn);
    private final AtomicBoolean taskStarted = new AtomicBoolean();
    private final Object configLock = new Object();
    private volatile @Nullable DiscordWebhookSettings settings;

    @Override
    public void start() {
        reload();
    }

    @Override
    public void reload() {
        synchronized (configLock) {
            settings = null;
            queue.clear();
            try {
                settings = DiscordWebhookSettings.load(GrimAPI.INSTANCE.getConfigManager().getConfig(), LogUtil::warn);
            } catch (RuntimeException exception) {
                // Configuration and URI exceptions may include the webhook token in their message.
                LogUtil.error("Failed to load Discord webhook configuration (" + exception.getClass().getSimpleName() + ").");
            }
        }
    }

    public void sendAlert(@NotNull GrimPlayer player, String verbose, String checkName, int violations) {
        DiscordWebhookSettings current = settings;
        if (current == null) return;

        Map<String, String> statics = new HashMap<>(GrimAPI.INSTANCE.getExternalAPI().getStaticReplacements());
        statics.put("%check%", checkName);
        statics.put("%violations%", Integer.toString(violations));
        Map<String, Function<GrimUser, String>> dynamics = GrimAPI.INSTANCE.getExternalAPI().getVariableReplacements();

        String content = current.content().render(player, statics, dynamics, current.backtickReplacement());
        String verboseContent = current.includeVerbose() && verbose != null && !verbose.isEmpty()
                ? CompiledDiscordTemplate.escapeMarkdown(verbose) : null;
        Embed embed = DiscordAlertFormatter.create(content, current.title(),
                        MessageUtil.replacePlaceholders(player, current.footerText(), false), verboseContent)
                .color(current.color())
                .imageURL(MessageUtil.replacePlaceholders(player, current.imageUrl(), false))
                .thumbnailURL(MessageUtil.replacePlaceholders(player, current.thumbnailUrl(), false));
        if (embed.footer() != null) {
            embed.footer().icon(MessageUtil.replacePlaceholders(player, current.footerUrl(), false));
        }
        if (current.includeTimestamp()) embed.timestamp(Instant.now());

        sendWebhookMessage(new WebhookMessage().addEmbeds(embed), current);
    }

    public CompletableFuture<Boolean> sendWebhookMessage(WebhookMessage message) {
        return sendWebhookMessage(message, settings);
    }

    private CompletableFuture<Boolean> sendWebhookMessage(WebhookMessage message, @Nullable DiscordWebhookSettings current) {
        if (current == null) return CompletableFuture.completedFuture(false);

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(current.url())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toJson().toString()))
                    .timeout(TIMEOUT)
                    .build();
        } catch (RuntimeException exception) {
            LogUtil.error("Cannot create Discord webhook message (" + exception.getClass().getSimpleName() + ").");
            return CompletableFuture.completedFuture(false);
        }

        synchronized (configLock) {
            // An alert rendered before reload must not enter the new endpoint's queue.
            if (settings != current) return CompletableFuture.completedFuture(false);
            CompletableFuture<Boolean> result = queue.enqueue(request);
            if (taskStarted.compareAndSet(false, true)) {
                GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runAtFixedRate(
                        GrimAPI.INSTANCE.getGrimPlugin(), queue::tick, 0, 1);
            }
            return result;
        }
    }

    public boolean isDisabled() {
        return settings == null;
    }
}
