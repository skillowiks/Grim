package ac.grim.grimac.command.commands;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.command.BuildableCommand;
import ac.grim.grimac.manager.init.start.SuperDebug;
import ac.grim.grimac.platform.api.manager.cloud.CloudPlatformCommandArguments;
import ac.grim.grimac.platform.api.sender.Sender;
import ac.grim.grimac.utils.anticheat.DebugMessageUtil;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import ac.grim.grimac.utils.common.arguments.CommonGrimArguments;
import net.kyori.adventure.text.Component;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class GrimLog implements BuildableCommand {
    /** The consumer receives the uploaded URL, independent of chat formatting. */
    public static void sendLogAsync(Sender sender, String log, Consumer<String> consumer, String type) {
        sendLogAsync(sender, log, consumer, type, null);
    }

    public static void sendLogAsync(Sender sender, String log, Consumer<String> consumer, String type, @Nullable String targetName) {
        String success = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log", "%prefix% &fUploaded debug to: %url%");
        String failure = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log-upload-failure", "%prefix% &cSomething went wrong while uploading this log, see console for more information.");
        String uploading = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log-start", "%prefix% &fUploading log... please wait");
        sender.sendMessage(targeted(sender, uploading, targetName));
        GrimAPI.INSTANCE.getScheduler().getAsyncScheduler().runNow(GrimAPI.INSTANCE.getGrimPlugin(), () -> {
            try {
                sendLog(sender, log, success, failure, consumer, type, targetName);
            } catch (Exception e) {
                sender.sendMessage(targeted(sender, failure, targetName));
                LogUtil.error("Failed to send log", e);
            }
        });
    }

    private static void sendLog(Sender sender, String log, String success, String failure, Consumer<String> consumer, String type, @Nullable String targetName) throws IOException {
        URL mUrl = new URL(CommonGrimArguments.PASTE_URL.value() + "data/post");
        HttpURLConnection urlConn = (HttpURLConnection) mUrl.openConnection();
        try {
            urlConn.setDoOutput(true);
            urlConn.setRequestMethod("POST");
            urlConn.setConnectTimeout(CommonGrimArguments.URL_TIMEOUT.value());
            urlConn.setReadTimeout(CommonGrimArguments.URL_TIMEOUT.value());
            urlConn.addRequestProperty("User-Agent", "GrimAC/" + GrimAPI.INSTANCE.getExternalAPI().getGrimVersion());
            urlConn.addRequestProperty("Content-Type", type); // Not really yaml, but looks nicer than plaintext
            urlConn.setRequestProperty("Content-Length", Integer.toString(log.length()));
            try (OutputStream stream = urlConn.getOutputStream()) {
                stream.write(log.getBytes(StandardCharsets.UTF_8));
            }
            final int response = urlConn.getResponseCode();
            if (response == HttpURLConnection.HTTP_CREATED) {
                String responseURL = urlConn.getHeaderField("Location");
                String url = CommonGrimArguments.PASTE_URL.value() + responseURL;
                consumer.accept(url);
                sendUploadedLog(sender, success, url, targetName);
            } else {
                sender.sendMessage(targeted(sender, failure, targetName));
                LogUtil.error("Returned response code " + response + ": " + urlConn.getResponseMessage());
            }
        } finally {
            urlConn.disconnect();
        }
    }

    public static void sendUploadedLog(Sender sender, String url) {
        String success = GrimAPI.INSTANCE.getConfigManager().getConfig().getStringElse("upload-log", "%prefix% &fUploaded debug to: %url%");
        sendUploadedLog(sender, success, url, null);
    }

    private static void sendUploadedLog(Sender sender, String success, String url, @Nullable String targetName) {
        Component copyHint = MessageUtil.getParsedComponent(sender, "upload-log-copy-hint", "Click to copy the URL");
        Component openLabel = MessageUtil.getParsedComponent(sender, "upload-log-open", "&7[Open]");
        sender.sendMessage(DebugMessageUtil.uploaded(targeted(sender, success, targetName), url, copyHint, openLabel));
    }

    private static Component targeted(Sender sender, String message, @Nullable String targetName) {
        return DebugMessageUtil.targeted(message, targetName,
                raw -> MessageUtil.miniMessage(MessageUtil.replacePlaceholders(sender, raw)));
    }

    static @Nullable String playerNameFromLog(String log) {
        String header = "\nPlayer Name: ";
        int start = log.indexOf(header);
        if (start < 0) return null;
        start += header.length();
        int end = log.indexOf('\n', start);
        String name = log.substring(start, end < 0 ? log.length() : end).trim();
        return name.isEmpty() ? null : name;
    }

    @Override
    public void register(CommandManager<Sender> commandManager, CloudPlatformCommandArguments arguments) {
        Command<Sender> command = commandManager.commandBuilder("grim", "grimac")
                .literal("log", "logs")
                .permission("grim.log")
                .required("flagId", IntegerParser.integerParser())
                .handler(this::handleLog)
                .manager(commandManager)
                .build();
        commandManager
                .command(command)
                .command(commandManager.commandBuilder("gl").proxies(command));
    }

    private void handleLog(@NotNull CommandContext<Sender> context) {
        Sender sender = context.sender();
        int flagId = context.get("flagId");

        StringBuilder builder = SuperDebug.getFlag(flagId);
        if (builder == null) {
            sender.sendMessage(MessageUtil.getParsedComponent(sender, "upload-log-not-found", "%prefix% &cUnable to find that log"));
            return;
        }
        String log = builder.toString();
        sendLogAsync(sender, log, string -> {}, "text/yaml", playerNameFromLog(log));
    }
}
