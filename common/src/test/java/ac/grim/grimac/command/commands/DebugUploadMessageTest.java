package ac.grim.grimac.command.commands;

import ac.grim.grimac.utils.anticheat.DebugMessageUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebugUploadMessageTest {
    @Test
    void eachPlayersUploadCopiesOnlyItsUrlAndKeepsSeparateOpenAction() {
        String aliceUrl = "https://paste.grim.ac/AliceReport";
        String bobUrl = "https://paste.grim.ac/BobReport";
        Component alice = uploaded("Uploaded debug to: %url%", "Alice", aliceUrl);
        Component bob = uploaded("Uploaded debug to: %url%", "Bob", bobUrl);
        assertTrue(plain(alice).contains("Alice"));
        assertFalse(plain(alice).contains("Bob"));
        assertTrue(plain(bob).contains("Bob"));
        assertFalse(plain(bob).contains("Alice"));
        assertEquals(List.of(ClickEvent.copyToClipboard(aliceUrl), ClickEvent.openUrl(aliceUrl)), clicks(alice));
        assertEquals(List.of(ClickEvent.copyToClipboard(bobUrl), ClickEvent.openUrl(bobUrl)), clicks(bob));
    }

    @Test
    void targetPlaceholderDoesNotResolveToStaffAndNamesAreLiteralComponents() {
        Component message = DebugMessageUtil.targeted("<gray>Uploading %player%", "Target<red>",
                raw -> MiniMessage.miniMessage().deserialize(raw.replace("%player%", "Staff")));
        assertEquals("Uploading Target<red>", plain(message));
        assertFalse(plain(message).contains("Staff"));
        assertEquals("[Target] Upload failed", plain(DebugMessageUtil.targeted("Upload failed", "Target", Component::text)));
    }

    @Test
    void oldOpenUrlTemplateCannotOverrideCopyOnTheDisplayedUrl() {
        String url = "https://paste.grim.ac/Report";
        Component message = uploaded("<click:open_url:'https://example.invalid'>%url%</click>", "Target", url);
        List<Component> components = new ArrayList<>();
        flatten(message, components);
        Component displayedUrl = components.stream()
                .filter(component -> component instanceof TextComponent text && text.content().equals(url))
                .findFirst().orElseThrow();
        assertEquals(ClickEvent.copyToClipboard(url), displayedUrl.clickEvent());
        assertEquals(Component.text("Copy URL"), displayedUrl.hoverEvent().value());
    }

    @Test
    void urlStillAppearsWithoutPlaceholderAndGenericDumpNeedsNoPlayer() {
        String url = "https://paste.grim.ac/Dump";
        Component message = uploaded("Upload complete", null, url);
        assertEquals("Upload complete " + url + " [Open]", plain(message));
        assertEquals(List.of(ClickEvent.copyToClipboard(url), ClickEvent.openUrl(url)), clicks(message));
    }

    @Test
    void classicFlagUploadUsesTheRecordedPlayerHeader() {
        assertEquals("Target", GrimLog.playerNameFromLog("Grim Version: test\nPlayer Name: Target\nClient Version: 1.21.11\n"));
        assertEquals("Second", GrimLog.playerNameFromLog("Grim Version: test\r\nPlayer Name: Second\r\nClient Version: 1.21.11\r\n"));
    }

    @Test
    void playerOnlyInClickOrHoverStillGetsVisibleLabelAndLiteralMetadata() {
        String target = "Target<red>";
        Component message = DebugMessageUtil.targeted(
                "<click:run_command:'/grim debug %player% report'><hover:show_text:'Report for %player%'>Report</hover></click>",
                target, MiniMessage.miniMessage()::deserialize);
        assertEquals("[Target<red>] Report", plain(message));
        assertTrue(clicks(message).contains(ClickEvent.runCommand("/grim debug Target<red> report")));
        List<Component> components = new ArrayList<>();
        flatten(message, components);
        Component hover = components.stream().filter(component -> component.hoverEvent() != null)
                .findFirst().orElseThrow();
        assertEquals("Report for Target<red>", plain((Component) hover.hoverEvent().value()));
    }

    @Test
    void urlOnlyInClickOrHoverIsResolvedAndAlsoDisplayedAsCopyLink() {
        String url = "https://paste.grim.ac/Report";
        Component message = uploaded(
                "<click:open_url:'%url%'><hover:show_text:'Open %url%'>Report</hover></click>", "Target", url);
        assertEquals("[Target] Report " + url + " [Open]", plain(message));
        assertTrue(clicks(message).contains(ClickEvent.openUrl(url)));
        assertTrue(clicks(message).contains(ClickEvent.copyToClipboard(url)));
        assertFalse(clicks(message).stream().anyMatch(event -> event.value().contains("%url%")));
        List<Component> components = new ArrayList<>();
        flatten(message, components);
        Component originalHover = components.stream().filter(component -> component instanceof TextComponent text
                        && text.content().equals("Report") && component.hoverEvent() != null)
                .findFirst().orElseThrow();
        assertEquals("Open " + url, plain((Component) originalHover.hoverEvent().value()));
    }

    @Test
    void insertionPlaceholdersAlsoResolveAfterParsing() {
        Component target = DebugMessageUtil.targeted("<insert:%player%>Player</insert>", "Target<red>",
                MiniMessage.miniMessage()::deserialize);
        List<Component> components = new ArrayList<>();
        flatten(target, components);
        assertTrue(components.stream().anyMatch(component -> "Target<red>".equals(component.insertion())));
        Component url = uploaded("<insert:%url%>Report</insert>", "Target", "https://paste.grim.ac/Report");
        components.clear();
        flatten(url, components);
        assertTrue(components.stream().anyMatch(component -> "https://paste.grim.ac/Report".equals(component.insertion())));
    }

    private static Component uploaded(String template, String target, String url) {
        Component message = DebugMessageUtil.targeted(template, target, MiniMessage.miniMessage()::deserialize);
        return DebugMessageUtil.uploaded(message, url, Component.text("Copy URL"), Component.text("[Open]"));
    }

    private static String plain(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof TextComponent text) result.append(text.content());
        for (Component child : component.children()) result.append(plain(child));
        return result.toString();
    }

    private static List<ClickEvent> clicks(Component component) {
        List<Component> components = new ArrayList<>();
        flatten(component, components);
        return components.stream().map(Component::clickEvent).filter(event -> event != null).toList();
    }

    private static void flatten(Component component, List<Component> components) {
        components.add(component);
        for (Component child : component.children()) flatten(child, components);
    }
}
