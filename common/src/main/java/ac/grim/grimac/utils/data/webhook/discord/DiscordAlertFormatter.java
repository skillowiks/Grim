package ac.grim.grimac.utils.data.webhook.discord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Builds the text portion of one alert within Discord's individual and combined limits. */
public final class DiscordAlertFormatter {
    private static final String VERBOSE_FIELD_NAME = "Verbose";
    private static final String EMPTY_ALERT_DESCRIPTION = "Grim alert";
    private static final String ELLIPSIS = "\u2026";

    private DiscordAlertFormatter() {
    }

    /** The optional verbose text must already be Markdown-escaped by the caller. */
    public static @NotNull Embed create(String description, String title, String footerText,
                                        @Nullable String verbose) {
        String safeDescription = truncate(emptyIfBlank(description), Embed.MAX_DESCRIPTION_LENGTH);
        String safeTitle = truncate(emptyIfBlank(title), Embed.MAX_TITLE_LENGTH);
        String safeVerbose = truncate(emptyIfBlank(verbose), EmbedField.MAX_VALUE_LENGTH);

        int used = safeDescription.length() + safeTitle.length();
        if (!safeVerbose.isEmpty()) {
            used += VERBOSE_FIELD_NAME.length() + safeVerbose.length();
        }
        // The main alert and its diagnostic detail take priority over footer decoration.
        int footerBudget = Math.min(EmbedFooter.MAX_TEXT_LENGTH, WebhookMessage.MAX_EMBED_TEXT_LENGTH - used);
        String safeFooter = truncate(emptyIfBlank(footerText), footerBudget);

        if (safeDescription.isEmpty() && safeTitle.isEmpty() && safeVerbose.isEmpty() && safeFooter.isEmpty()) {
            safeDescription = EMPTY_ALERT_DESCRIPTION;
        }

        Embed embed = new Embed(safeDescription);
        if (!safeTitle.isEmpty()) embed.title(safeTitle);
        if (!safeVerbose.isEmpty()) embed.addFields(new EmbedField(VERBOSE_FIELD_NAME, safeVerbose, true));
        if (!safeFooter.isEmpty()) embed.footer(new EmbedFooter(safeFooter));
        return embed;
    }

    private static @NotNull String emptyIfBlank(@Nullable String text) {
        return text == null || text.isBlank() ? "" : text;
    }

    /** Counts UTF-16 units like the embed validators, without splitting a supplementary character. */
    private static @NotNull String truncate(@NotNull String text, int limit) {
        if (limit <= 0) return "";
        if (text.length() <= limit) return text;

        int end = limit - ELLIPSIS.length();
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }
}
