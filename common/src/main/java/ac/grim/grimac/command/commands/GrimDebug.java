package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.manager.deepdebug.DeepDebugManager;
import ac.grim.grimac.manager.deepdebug.DeepDebugReport;
import ac.grim.grimac.manager.deepdebug.DeepDebugSession;
import ac.grim.grimac.manager.deepdebug.FlagRecord;
import ac.grim.grimac.platform.api.command.PlayerSelector;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GrimDebug implements BuildableCommand {

    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command.Builder<Sender> grimCommand = commandManager.commandBuilder("grim", "grimac");

        // /grim debug [target] — toggle the deep-debug session (live flag feed + forensics capture)
        Command.Builder<Sender> debugCommand = grimCommand
                .literal("debug", Description.of("Toggle deep-debug forensics for a player"))
                .permission("grim.debug")
                .optional("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleDebugToggle);

        // /grim debug <target> report — build and upload the forensic report
        Command.Builder<Sender> debugReportCommand = grimCommand
                .literal("debug", Description.of("Toggle deep-debug forensics for a player"))
                .permission("grim.debug")
                .required("target", arguments.singlePlayerSelectorParser())
                .literal("report", Description.of("Build the deep-debug forensic report"))
                .handler(this::handleDebugReport);

        // /grim debug <target> stop — stop the session without a report
        Command.Builder<Sender> debugStopCommand = grimCommand
                .literal("debug", Description.of("Toggle deep-debug forensics for a player"))
                .permission("grim.debug")
                .required("target", arguments.singlePlayerSelectorParser())
                .literal("stop", Description.of("Stop the deep-debug session"))
                .handler(this::handleDebugStop);

        // Register "consoledebug" subcommand
        Command.Builder<Sender> consoleDebugCommand = grimCommand
                .literal("consoledebug", Description.of("Toggle console debug output for a player"))
                .permission("grim.consoledebug")
                .required("target", arguments.singlePlayerSelectorParser())
                .handler(this::handleConsoleDebug);

        // Register command
        commandManager.command(debugCommand);
        commandManager.command(debugReportCommand);
        commandManager.command(debugStopCommand);
        commandManager.command(consoleDebugCommand);
    }

    private void handleDebugToggle(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.getOrDefault("target", null);

        GrimPlayer targetGrimPlayer = parseTarget(sender, playerSelector == null ? sender : playerSelector.getSinglePlayer());
        if (targetGrimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        String targetName = playerName(targetGrimPlayer);

        DeepDebugSession existing = manager.getSession(targetGrimPlayer.uuid);
        if (existing != null) {
            int flags = existing.flagsSnapshot().size();
            manager.stopSession(targetGrimPlayer.uuid, "toggled");
            sendKey(sender, "deep-debug-stopped",
                    "%prefix% &bDeep debug disabled for &f%player%&b. &7(%flags% flags captured)",
                    targetName, flags, -1);
            return;
        }

        manager.startSession(targetGrimPlayer, sender);

        sendKey(sender, "deep-debug-started",
                "%prefix% &bDeep debug enabled for &f%player%&b. Live flag feed active — full forensic report: "
                        + "&7/grim debug %player% report&b. Auto-off after 10 minutes.",
                targetName, -1, -1);
    }

    private void handleDebugReport(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.get("target");

        GrimPlayer targetGrimPlayer = parseTarget(sender, playerSelector.getSinglePlayer());
        if (targetGrimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        DeepDebugSession session = manager.getSession(targetGrimPlayer.uuid);
        if (session == null) {
            sendKey(sender, "deep-debug-no-session",
                    "%prefix% &cNo deep-debug session is running for &f%player%&c. Start one with &7/grim debug %player%",
                    playerName(targetGrimPlayer), -1, -1);
            return;
        }

        List<FlagRecord> flags = session.flagsSnapshot();
        Map<String, Integer> byCheck = new LinkedHashMap<>();
        for (FlagRecord flag : flags) {
            byCheck.merge(flag.checkName, 1, Integer::sum);
        }
        sendKey(sender, "deep-debug-report-summary",
                "%prefix% &bDeep debug report for &f%player%&b: &f%flags%&b flags across &f%checks%&b unique checks. Uploading...",
                playerName(targetGrimPlayer), flags.size(), byCheck.size());

        String report = DeepDebugReport.build(session);
        GrimLog.sendLogAsync(sender, report, url -> { }, "text/yaml");
    }

    private void handleDebugStop(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector playerSelector = context.get("target");

        GrimPlayer targetGrimPlayer = parseTarget(sender, playerSelector.getSinglePlayer());
        if (targetGrimPlayer == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));
            return;
        }

        DeepDebugManager manager = GrimAPI.INSTANCE.getDeepDebugManager();
        DeepDebugSession session = manager.getSession(targetGrimPlayer.uuid);
        if (session == null) {
            sendKey(sender, "deep-debug-no-session",
                    "%prefix% &cNo deep-debug session is running for &f%player%&c. Start one with &7/grim debug %player%",
                    playerName(targetGrimPlayer), -1, -1);
            return;
        }

        int flags = session.flagsSnapshot().size();
        manager.stopSession(targetGrimPlayer.uuid, "stopped");
        sendKey(sender, "deep-debug-stopped",
                "%prefix% &bDeep debug disabled for &f%player%&b. &7(%flags% flags captured)",
                playerName(targetGrimPlayer), flags, -1);
    }

    private void handleConsoleDebug(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        PlayerSelector targetName = context.getOrDefault("target", null);

        GrimPlayer grimPlayer = parseTarget(sender, targetName.getSinglePlayer());
        if (grimPlayer == null) return;

        boolean isOutput = grimPlayer.checkManager.getDebugHandler().toggleConsoleOutput();
        String playerName = grimPlayer.user.getProfile().getName(); // Use user profile for name

        Component message = Component.text()
                .append(Component.text("Console output for ", NamedTextColor.GRAY))
                .append(Component.text(playerName, NamedTextColor.WHITE))
                .append(Component.text(" is now ", NamedTextColor.GRAY))
                .append(Component.text(isOutput ? "enabled" : "disabled", NamedTextColor.WHITE))
                .build();

        sender.sendMessage(message);
    }

    private static String playerName(GrimPlayer player) {
        String name = player.user.getName();
        return name == null ? player.uuid.toString() : name;
    }

    private static void sendKey(Sender sender, String key, String fallback, String targetName, int flags, int checks) {
        String raw = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse(key, fallback)
                .replace("%player%", targetName)
                .replace("%flags%", String.valueOf(Math.max(0, flags)))
                .replace("%checks%", String.valueOf(Math.max(0, checks)));
        sender.sendMessage(MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, raw)));
    }

    private @Nullable GrimPlayer parseTarget(@NotNull Sender sender, @Nullable Sender t) {
        if (sender.isConsole() && t == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "console-specify-target", "%prefix% &cYou must specify a target as the console!"));
            return null;
        }
        Sender target = t == null ? sender : t;

        GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(target.getUniqueId());
        if (grimPlayer == null) {
            // Console senders have no platform player — the PacketEvents lookup below is player-only.
            User user = sender.getPlatformPlayer() == null ? null
                    : PacketEvents.getAPI().getPlayerManager().getUser(sender.getPlatformPlayer().getNative());
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "player-not-found", "%prefix% &cPlayer is exempt or offline!"));

            if (user == null) {
                sender.sendMessage(Component.text("Unknown PacketEvents user", NamedTextColor.RED));
            } else {
                boolean isExempt = GrimAPI.INSTANCE.getPlayerDataManager().shouldCheck(user);
                if (!isExempt) {
                    sender.sendMessage(Component.text("User connection state: " + user.getConnectionState(), NamedTextColor.RED));
                }
            }
        }

        return grimPlayer;
    }
}
