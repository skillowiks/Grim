package ac.grim.grimac.utils.data.webhook.discord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebhookMessageTest {
    @Test
    void repeatedEmbedAppendKeepsEveryEmbedInOrder() {
        Embed first = new Embed("first"), second = new Embed("second"), third = new Embed("third");
        WebhookMessage message = new WebhookMessage().addEmbeds(first).addEmbeds(second, third);
        assertArrayEquals(new Embed[]{first, second, third}, message.embeds());
        assertEquals("third", message.toJson().getAsJsonArray("embeds").get(2).getAsJsonObject().get("description").getAsString());
    }

    @Test
    void repeatedFieldAppendKeepsEveryFieldInOrder() {
        EmbedField first = new EmbedField("first", "1"), second = new EmbedField("second", "2"), third = new EmbedField("third", "3");
        Embed embed = new Embed("description").addFields(first, second).addFields(third);
        assertArrayEquals(new EmbedField[]{first, second, third}, embed.fields());
        assertEquals("3", embed.toJson().getAsJsonArray("fields").get(2).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void failedAppendDoesNotReplaceOriginalArray() {
        Embed[] embeds = new Embed[WebhookMessage.MAX_EMBEDS];
        for (int i = 0; i < embeds.length; i++) embeds[i] = new Embed("embed " + i);
        WebhookMessage message = new WebhookMessage().embeds(embeds);
        assertThrows(IllegalArgumentException.class, () -> message.addEmbeds(new Embed("extra")));
        assertArrayEquals(embeds, message.embeds());

        EmbedField[] fields = new EmbedField[Embed.MAX_FIELDS];
        for (int i = 0; i < fields.length; i++) fields[i] = new EmbedField("field " + i, "value");
        Embed embed = new Embed("description").fields(fields);
        assertThrows(IllegalArgumentException.class, () -> embed.addFields(new EmbedField("extra", "value")));
        assertArrayEquals(fields, embed.fields());
    }

    @Test
    void thumbnailAndImageSurviveJsonRoundTripAsDistinctUrls() {
        Embed embed = new Embed("description")
                .imageURL("https://example.com/image.png")
                .thumbnailURL("https://example.com/thumbnail.png")
                .color(0xff00ffff);
        JsonObject json = embed.toJson();
        Embed restored = new Embed(json);
        assertEquals(embed.imageURL(), restored.imageURL());
        assertEquals(embed.thumbnailURL(), restored.thumbnailURL());
        assertEquals(0x00ffff, json.get("color").getAsInt());
        assertEquals(json, restored.toJson());
    }

    @Test
    void contentEscapesAsJsonAndAbsentOptionalPropertiesStayAbsent() {
        String content = "quoted \"value\" and backslash \\ and newline\n";
        WebhookMessage message = new WebhookMessage().content(content);
        JsonObject serialized = new JsonParser().parse(message.toJson().toString()).getAsJsonObject();
        assertEquals(content, serialized.get("content").getAsString());
        assertFalse(serialized.has("username"));
        assertFalse(serialized.has("avatar_url"));
        assertFalse(serialized.has("embeds"));
        assertEquals(content, new WebhookMessage(serialized).content());
    }

    @Test
    void acceptsExactly6000EmbedCharactersWithSeparate2000CharacterContent() {
        Embed embed = new Embed("d".repeat(4096)).footer(new EmbedFooter("f".repeat(1904)));
        WebhookMessage message = new WebhookMessage().content("c".repeat(2000)).addEmbeds(embed);
        assertEquals(6000, embed.textLength());
        assertEquals(1, message.toJson().getAsJsonArray("embeds").size());
    }

    @Test
    void checksCombinedLimitAcrossEmbedsAgainAfterTheyAreMutated() {
        Embed first = new Embed("a".repeat(3000)), second = new Embed("b".repeat(3000));
        WebhookMessage message = new WebhookMessage().addEmbeds(first, second);
        message.toJson();
        second.title("x");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, message::toJson);
        assertEquals("Webhook embed text too long, 6001 > 6000", failure.getMessage());
    }

    @Test
    void combinedLimitCountsTitleAuthorFooterAndBothFieldStrings() {
        Embed decorated = new Embed("d").title("tt").author(new EmbedAuthor("aaaa"))
                .footer(new EmbedFooter("fff")).addFields(new EmbedField("nnnnn", "vvvvvv"));
        assertEquals(21, decorated.textLength());
        WebhookMessage message = new WebhookMessage().addEmbeds(
                new Embed("a".repeat(4000)), new Embed("b".repeat(1979)), decorated);
        message.toJson();
        decorated.author().name("aaaaa");
        assertThrows(IllegalArgumentException.class, message::toJson);
    }
}
