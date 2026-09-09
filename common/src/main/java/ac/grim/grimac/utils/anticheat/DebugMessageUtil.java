package ac.grim.grimac.utils.anticheat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

public final class DebugMessageUtil {
    private static final String TARGET_TOKEN = "GRIM_DEBUG_TARGET_LITERAL";

    private DebugMessageUtil() {
    }

    /** Preserve the report's target even when the sender's placeholders are resolved afterwards. */
    public static Component targeted(String template, @Nullable String target, Function<String, Component> renderer) {
        if (target == null) return renderer.apply(template);
        Component parsed = renderer.apply(template.replace("%player%", TARGET_TOKEN));
        boolean hasTarget = containsVisibleToken(parsed, TARGET_TOKEN);
        Component message = replaceMetadata(parsed.replaceText(builder -> builder.matchLiteral(TARGET_TOKEN)
                .replacement(Component.text(target))), TARGET_TOKEN, target);
        // Existing messages.yml files need a target label too, without requiring a config reset.
        return hasTarget ? message : Component.text("[" + target + "] ", NamedTextColor.AQUA).append(message);
    }

    public static Component uploaded(Component template, String url, Component copyHint, Component openLabel) {
        Component link = Component.text(url, NamedTextColor.AQUA).decorate(TextDecoration.UNDERLINED)
                .clickEvent(ClickEvent.copyToClipboard(url)).hoverEvent(HoverEvent.showText(copyHint));
        boolean hasVisibleUrl = containsVisibleToken(template, "%url%");
        Component message = replaceMetadata(template.replaceText(builder -> builder.matchLiteral("%url%")
                .replacement(link)), "%url%", url);
        if (!hasVisibleUrl) message = message.append(Component.space()).append(link);
        return message.append(Component.space()).append(openLabel.clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(url))));
    }

    private static boolean containsVisibleToken(Component component, String token) {
        if (component instanceof TextComponent text && text.content().contains(token)) return true;
        return component.children().stream().anyMatch(child -> containsVisibleToken(child, token));
    }

    /** Text replacement covers hover text, but does not rewrite click or insertion values. */
    private static Component replaceMetadata(Component component, String token, String value) {
        ClickEvent click = component.clickEvent();
        if (click != null && click.value().contains(token)) {
            component = component.clickEvent(ClickEvent.clickEvent(click.action(), click.value().replace(token, value)));
        }
        String insertion = component.insertion();
        if (insertion != null && insertion.contains(token)) {
            component = component.insertion(insertion.replace(token, value));
        }
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null) {
            component = component.hoverEvent(hover.withRenderedValue(
                    (hoverText, replacement) -> replaceMetadata(hoverText, token, replacement), value));
        }
        if (!component.children().isEmpty()) {
            component = component.children(component.children().stream()
                    .map(child -> replaceMetadata(child, token, value)).toList());
        }
        return component;
    }
}
