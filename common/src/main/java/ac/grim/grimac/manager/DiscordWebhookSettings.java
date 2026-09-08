package ac.grim.grimac.manager;

import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.utils.data.webhook.discord.CompiledDiscordTemplate;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.net.URI;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Published as one snapshot so reload cannot mix an endpoint with another configuration's template. */
record DiscordWebhookSettings(URI url, int color, CompiledDiscordTemplate content, char backtickReplacement,
                              String title, boolean includeTimestamp, boolean includeVerbose,
                              @Nullable String imageUrl, @Nullable String thumbnailUrl,
                              @Nullable String footerUrl, String footerText) {
    private static final Pattern DISCORD_URL = Pattern.compile(
            "^https://(?:canary\\.)?discord\\.com/api(?:/v\\d+)?/webhooks/\\d+/[\\w-]+"
                    + "(?:\\?(?:thread_id=\\d+|wait=(?:true|false))(?:&(?:thread_id=\\d+|wait=(?:true|false)))*)?$");
    private static final Pattern EMBED_URL = Pattern.compile(
            "^https?://(?:www\\.)?[-a-z0-9@:%._+~#=]{1,256}\\.[a-z0-9()]{1,6}\\b[-a-z0-9()@:%_+.~#?&/=]*$",
            Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_COLOR = 0x00FFFF;

    static @Nullable DiscordWebhookSettings load(ConfigManager config, Consumer<String> logger) {
        if (!config.getBooleanElse("enabled", false)) return null;
        String webhook = config.getStringElse("webhook", "").trim();
        if (webhook.isEmpty()) return null;
        boolean discord = DISCORD_URL.matcher(webhook).matches();
        if (!discord && !config.getBooleanElse("disable-webhook-validation", false)) {
            logger.accept("Discord webhook URL does not match the expected format. For a custom HTTPS endpoint,"
                    + " set 'disable-webhook-validation: true' in discord.yml.");
            return null;
        }

        URI url;
        try {
            url = URI.create(webhook);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                    || url.getRawUserInfo() != null || url.getRawFragment() != null) {
                logger.accept("Discord webhook URL must be a valid HTTPS URL without user information or a fragment.");
                return null;
            }
        } catch (IllegalArgumentException exception) {
            logger.accept("Discord webhook URL is malformed.");
            return null;
        }
        if (discord) {
            // Require Discord's confirmation that it saved the message; preserve forum thread routing.
            StringBuilder confirmed = new StringBuilder(webhook.split("\\?", 2)[0]).append('?');
            if (url.getRawQuery() != null) {
                for (String parameter : url.getRawQuery().split("&")) {
                    if (!parameter.startsWith("wait=")) confirmed.append(parameter).append('&');
                }
            }
            url = URI.create(confirmed.append("wait=true").toString());
        }

        int color = DEFAULT_COLOR;
        try {
            color = Color.decode(config.getStringElse("embed-color", "#00FFFF")).getRGB();
        } catch (NumberFormatException exception) {
            logger.accept("Discord embed color is invalid; using #00FFFF.");
        }
        String replacement = config.getStringElse("backtick-replacement-char", "\u02CB");
        String content = String.join("\n", config.getStringListElse("violation-content", List.of(
                "**Player**: `%player%`", "**Check**: %check%", "**Violations**: %violations%",
                "**Client Version**: %version%", "**Brand**: `%brand%`", "**Ping**: %ping%", "**TPS**: %tps%"))) + "\n";
        return new DiscordWebhookSettings(url, color, CompiledDiscordTemplate.compile(content),
                replacement.isEmpty() ? '\u02CB' : replacement.charAt(0),
                config.getStringElse("embed-title", "**Grim Alert**"),
                config.getBooleanElse("include-timestamp", true), config.getBooleanElse("include-verbose", true),
                embedUrl(config, "embed-image-url", null, logger),
                embedUrl(config, "embed-thumbnail-url", "https://crafthead.net/helm/%uuid%", logger),
                embedUrl(config, "embed-footer-url", "https://grim.ac/images/grim.png", logger),
                config.getStringElse("embed-footer-text", "v%grim_version%"));
    }

    private static @Nullable String embedUrl(ConfigManager config, String path, @Nullable String fallback,
                                              Consumer<String> logger) {
        String value = config.getStringElse(path, fallback);
        if (value == null || value.isBlank()) return null;
        if (EMBED_URL.matcher(value).matches()) return value;
        logger.accept("Invalid Discord embed URL for config path " + path + "; using its default.");
        return fallback;
    }
}
