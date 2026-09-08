package ac.grim.grimac.manager;

import ac.grim.grimac.api.config.ConfigManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordWebhookSettingsTest {
    // Synthetic endpoint strings only. These tests never instantiate an HTTP transport.
    private static final String DISCORD = "https://discord.com/api/webhooks/123456789012345678/synthetic_token";
    private static final String DEFAULT_THUMBNAIL = "https://crafthead.net/helm/%uuid%";
    private static final String DEFAULT_FOOTER = "https://grim.ac/images/grim.png";

    @Test
    void disabledConfigurationDoesNotValidateOrExposeEndpoint() {
        List<String> logs = new ArrayList<>();
        DiscordWebhookSettings settings = DiscordWebhookSettings.load(config(Map.of(
                "enabled", false, "webhook", "invalid synthetic_marker")), logs::add);
        assertNull(settings);
        assertTrue(logs.isEmpty());
    }

    @Test
    void blankWebhookDisablesWithoutError() {
        for (String endpoint : List.of("", "  \t\n")) {
            List<String> logs = new ArrayList<>();
            assertNull(load(Map.of("webhook", endpoint), logs));
            assertTrue(logs.isEmpty());
        }
    }

    @Test
    void trimsValidDiscordEndpointAndRequiresSavedMessageConfirmation() {
        List<String> logs = new ArrayList<>();
        DiscordWebhookSettings settings = load(Map.of("webhook", "  " + DISCORD + "\n"), logs);
        assertNotNull(settings);
        assertEquals(URI.create(DISCORD + "?wait=true"), settings.url());
        assertTrue(logs.isEmpty());
    }

    @Test
    void preservesForumThreadWhenAddingConfirmation() {
        DiscordWebhookSettings settings = load(Map.of("webhook", DISCORD + "?thread_id=987654321"), new ArrayList<>());
        assertNotNull(settings);
        assertEquals(List.of("thread_id=987654321", "wait=true"), query(settings));
    }

    @Test
    void existingWaitFlagIsOverriddenWithoutCreatingDuplicates() {
        for (String query : List.of("wait=false", "wait=true", "wait=false&thread_id=987654321",
                "thread_id=987654321&wait=false", "wait=false&wait=true&thread_id=987654321")) {
            DiscordWebhookSettings settings = load(Map.of("webhook", DISCORD + "?" + query), new ArrayList<>());
            assertNotNull(settings);
            List<String> parameters = query(settings);
            assertEquals(1, parameters.stream().filter(parameter -> parameter.equals("wait=true")).count());
            assertFalse(parameters.contains("wait=false"));
            if (query.contains("thread_id=")) assertTrue(parameters.contains("thread_id=987654321"));
        }
    }

    @Test
    void acceptsVersionedCanaryEndpoint() {
        String endpoint = "https://canary.discord.com/api/v10/webhooks/123456789012345678/synthetic_token";
        DiscordWebhookSettings settings = load(Map.of("webhook", endpoint), new ArrayList<>());
        assertNotNull(settings);
        assertEquals(endpoint + "?wait=true", settings.url().toString());
    }

    @Test
    void customHttpsEndpointKeepsItsQueryExactly() {
        String endpoint = "https://relay.example.org/webhook?wait=false&api_key=synthetic_query_marker";
        List<String> logs = new ArrayList<>();
        DiscordWebhookSettings settings = load(Map.of(
                "webhook", endpoint, "disable-webhook-validation", true), logs);
        assertNotNull(settings);
        assertEquals(endpoint, settings.url().toString());
        assertTrue(logs.isEmpty());
    }

    @Test
    void strictValidationRejectsCustomEndpointWithoutLoggingItsSecret() {
        String endpoint = "https://relay.example.org/synthetic_marker";
        List<String> logs = new ArrayList<>();
        assertNull(load(Map.of("webhook", endpoint), logs));
        assertRedacted(logs);
    }

    @Test
    void customValidationRejectsMalformedHttpUserInfoAndFragmentsWithoutLeakingValues() {
        for (String endpoint : List.of(
                "https://relay.example.org/%synthetic_marker",
                "http://relay.example.org/synthetic_marker",
                "https://synthetic_marker:synthetic_password@relay.example.org/webhook",
                "https://relay.example.org/webhook#synthetic_marker")) {
            List<String> logs = new ArrayList<>();
            assertNull(load(Map.of("webhook", endpoint, "disable-webhook-validation", true), logs));
            assertRedacted(logs);
        }
    }

    @Test
    void imageThumbnailAndFooterReadTheirOwnConfigurationKeys() {
        DiscordWebhookSettings settings = load(Map.of(
                "embed-image-url", "https://example.com/image.png",
                "embed-thumbnail-url", "https://example.com/thumbnail.png",
                "embed-footer-url", "https://example.com/footer.png"), new ArrayList<>());
        assertNotNull(settings);
        assertEquals("https://example.com/image.png", settings.imageUrl());
        assertEquals("https://example.com/thumbnail.png", settings.thumbnailUrl());
        assertEquals("https://example.com/footer.png", settings.footerUrl());
    }

    @Test
    void missingImageKeysUseIndependentDefaultsAndBlankKeysDisableImages() {
        DiscordWebhookSettings defaults = load(Map.of(), new ArrayList<>());
        assertNotNull(defaults);
        assertNull(defaults.imageUrl());
        assertEquals(DEFAULT_THUMBNAIL, defaults.thumbnailUrl());
        assertEquals(DEFAULT_FOOTER, defaults.footerUrl());

        DiscordWebhookSettings blank = load(Map.of(
                "embed-image-url", " ", "embed-thumbnail-url", "", "embed-footer-url", "\t"), new ArrayList<>());
        assertNotNull(blank);
        assertNull(blank.imageUrl());
        assertNull(blank.thumbnailUrl());
        assertNull(blank.footerUrl());
    }

    @Test
    void invalidImageKeysFallBackIndependentlyWithoutLoggingTheirValues() {
        List<String> logs = new ArrayList<>();
        DiscordWebhookSettings settings = load(Map.of(
                "embed-image-url", "synthetic_marker image",
                "embed-thumbnail-url", "synthetic_marker thumbnail",
                "embed-footer-url", "synthetic_marker footer"), logs);
        assertNotNull(settings);
        assertNull(settings.imageUrl());
        assertEquals(DEFAULT_THUMBNAIL, settings.thumbnailUrl());
        assertEquals(DEFAULT_FOOTER, settings.footerUrl());
        assertEquals(3, logs.size());
        assertRedacted(logs);
        assertTrue(logs.stream().anyMatch(message -> message.contains("embed-thumbnail-url")));
    }

    @Test
    void invalidColorUsesDefaultAndDoesNotCarryPreviousSettingsColor() {
        DiscordWebhookSettings first = load(Map.of("embed-color", "#FF0000"), new ArrayList<>());
        assertNotNull(first);
        assertEquals(0xFF0000, first.color() & 0xFFFFFF);
        List<String> logs = new ArrayList<>();
        DiscordWebhookSettings reloaded = load(Map.of("embed-color", "synthetic_marker"), logs);
        assertNotNull(reloaded);
        assertEquals(0x00FFFF, reloaded.color() & 0xFFFFFF);
        assertRedacted(logs);
    }

    private static List<String> query(DiscordWebhookSettings settings) {
        return Arrays.asList(settings.url().getRawQuery().split("&"));
    }

    private static void assertRedacted(List<String> logs) {
        assertFalse(logs.isEmpty());
        for (String message : logs) {
            assertFalse(message.contains("synthetic_marker"));
            assertFalse(message.contains("synthetic_password"));
            assertFalse(message.contains("relay.example.org"));
        }
    }

    private static DiscordWebhookSettings load(Map<String, Object> overrides, List<String> logs) {
        Map<String, Object> settings = new HashMap<>(Map.of("enabled", true, "webhook", DISCORD));
        settings.putAll(overrides);
        return DiscordWebhookSettings.load(config(settings), logs::add);
    }

    private static ConfigManager config(Map<String, Object> settings) {
        return (ConfigManager) Proxy.newProxyInstance(ConfigManager.class.getClassLoader(), new Class<?>[]{ConfigManager.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStringElse", "getBooleanElse", "getStringListElse" -> settings.getOrDefault(arguments[0], arguments[1]);
                    default -> throw new AssertionError("Unexpected config access: " + method.getName());
                });
    }
}
