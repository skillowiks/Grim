package ac.grim.grimac.utils.data.webhook.discord;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordAlertFormatterTest {
    @Test
    void ordinaryAlertPreservesTextAndAlreadyEscapedVerbose() {
        String verbose = "offset \\*0.003\\* \\`value\\`";
        Embed embed = DiscordAlertFormatter.create("Player: Example\nCheck: Simulation", "Grim Alert", "v2.3", verbose);

        assertEquals("Player: Example\nCheck: Simulation", embed.description());
        assertEquals("Grim Alert", embed.title());
        assertEquals("v2.3", embed.footer().text());
        assertNull(embed.footer().icon());
        assertEquals(1, embed.fields().length);
        assertEquals("Verbose", embed.fields()[0].name());
        assertEquals(verbose, embed.fields()[0].value());
        assertTrue(embed.fields()[0].inline());
        assertNull(embed.color());
        assertNull(embed.timestamp());
        assertNull(embed.imageURL());
    }

    @Test
    void allOversizedInputsKeepMainTextAndVerboseBeforeAllocatingFooter() {
        Embed embed = DiscordAlertFormatter.create("d".repeat(8000), "t".repeat(8000),
                "f".repeat(8000), "v".repeat(8000));

        assertEquals("d".repeat(4095) + "\u2026", embed.description());
        assertEquals("t".repeat(255) + "\u2026", embed.title());
        assertEquals("v".repeat(1023) + "\u2026", embed.fields()[0].value());
        // 6000 - 4096 - 256 - 1024 - the seven characters in "Verbose".
        assertEquals("f".repeat(616) + "\u2026", embed.footer().text());
        assertEquals(6000, embed.textLength());
        assertDoesNotThrow(() -> new WebhookMessage().addEmbeds(embed).toJson());
    }

    @Test
    void absentVerboseReturnsItsBudgetToFooter() {
        for (String verbose : new String[]{null, "", " \n\t"}) {
            Embed embed = DiscordAlertFormatter.create("d".repeat(4096), "t".repeat(256),
                    "f".repeat(3000), verbose);
            assertNull(embed.fields());
            assertEquals("f".repeat(1647) + "\u2026", embed.footer().text());
            assertEquals(6000, embed.textLength());
        }
    }

    @Test
    void footerStillRespectsItsOwnLimitWhenCombinedBudgetHasRoom() {
        Embed embed = DiscordAlertFormatter.create("Alert", "", "f".repeat(3000), null);
        assertEquals("f".repeat(2047) + "\u2026", embed.footer().text());
        assertEquals(2053, embed.textLength());
        assertNull(embed.title());
    }

    @Test
    void exactIndividualLimitsAreNotEllipsized() {
        Embed main = DiscordAlertFormatter.create("d".repeat(4096), "t".repeat(256), "", "v".repeat(1024));
        assertEquals("d".repeat(4096), main.description());
        assertEquals("t".repeat(256), main.title());
        assertEquals("v".repeat(1024), main.fields()[0].value());
        assertNull(main.footer());

        Embed footer = DiscordAlertFormatter.create("", "", "f".repeat(2048), null);
        assertEquals("f".repeat(2048), footer.footer().text());
        assertEquals("", footer.description());
    }

    @Test
    void emptyOrBlankTextAlwaysProducesAnAlert() {
        for (String blank : new String[]{null, "", " \r\n\t"}) {
            Embed embed = DiscordAlertFormatter.create(blank, blank, blank, blank);
            assertEquals("Grim alert", embed.description());
            assertNull(embed.title());
            assertNull(embed.fields());
            assertNull(embed.footer());
            assertDoesNotThrow(() -> new WebhookMessage().addEmbeds(embed).toJson());
        }
    }

    @Test
    void nonemptyOtherTextDoesNotCauseUnnecessaryFallbackDescription() {
        for (Embed embed : List.of(
                DiscordAlertFormatter.create("", "Title", "", null),
                DiscordAlertFormatter.create("", "", "Footer", null),
                DiscordAlertFormatter.create("", "", "", "detail"))) {
            assertEquals("", embed.description());
            assertTrue(embed.textLength() > 0);
        }
    }

    @Test
    void clippingNeverLeavesHalfAnEmojiAtAnyIndividualBoundary() {
        String emoji = "\uD83D\uDE00";
        Embed embed = DiscordAlertFormatter.create("d".repeat(4094) + emoji + "tail",
                "t".repeat(254) + emoji + "tail", "", "v".repeat(1022) + emoji + "tail");
        assertEquals("d".repeat(4094) + "\u2026", embed.description());
        assertEquals("t".repeat(254) + "\u2026", embed.title());
        assertEquals("v".repeat(1022) + "\u2026", embed.fields()[0].value());
        assertValidSurrogates(embed.description());
        assertValidSurrogates(embed.title());
        assertValidSurrogates(embed.fields()[0].value());

        Embed footer = DiscordAlertFormatter.create("", "", "f".repeat(2046) + emoji + "tail", null);
        assertEquals("f".repeat(2046) + "\u2026", footer.footer().text());
        assertValidSurrogates(footer.footer().text());
    }

    @Test
    void remainingCombinedBudgetAlsoPreservesWholeEmoji() {
        String emoji = "\uD83D\uDE00";
        Embed embed = DiscordAlertFormatter.create("d".repeat(4096), "t".repeat(256),
                "f".repeat(615) + emoji + "tail", "v".repeat(1024));
        assertEquals("f".repeat(615) + "\u2026", embed.footer().text());
        assertEquals(5999, embed.textLength());
        assertValidSurrogates(embed.footer().text());
        assertDoesNotThrow(() -> new WebhookMessage().addEmbeds(embed).toJson());
    }

    @Test
    void emojiAtExactLimitIsPreservedWithoutAnEllipsis() {
        String description = "d".repeat(4094) + "\uD83D\uDE00";
        Embed embed = DiscordAlertFormatter.create(description, "", "", null);
        assertEquals(description, embed.description());
        assertValidSurrogates(embed.description());
    }

    private static void assertValidSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(++i)),
                        "high surrogate must be followed by a low surrogate");
            } else {
                assertFalse(Character.isLowSurrogate(current), "unexpected lone low surrogate");
            }
        }
    }
}
