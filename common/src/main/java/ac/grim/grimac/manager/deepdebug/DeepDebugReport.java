package ac.grim.grimac.manager.deepdebug;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.EventManager;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a deep-debug session into a paste-ready forensic report: flag
 * summary, server interference timeline with plugin attribution, attribute
 * anomalies, foreign packet listeners / event subscribers, client-mod
 * suspects and the movement context of the most recent flags.
 */
public final class DeepDebugReport {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    /** Flags within this window after an interference event count as correlated. */
    private static final long CORRELATION_WINDOW_MS = 2000;
    private static final int MAX_MOVEMENT_CONTEXTS = 3;

    private DeepDebugReport() {
    }

    public static String build(DeepDebugSession session) {
        StringBuilder sb = new StringBuilder(8192);
        List<FlagRecord> flags = session.flagsSnapshot();
        List<InterferenceRecord> interference = session.interferenceSnapshot();

        header(sb, session, flags);
        flagSummary(sb, flags);
        interference(sb, interference, flags);
        attributeAnomalies(sb, session);
        environment(sb, session);
        clientSuspects(sb, session, flags);
        movementContexts(sb, flags);
        return sb.toString();
    }

    // ------------------------------------------------------------------ header

    private static void header(StringBuilder sb, DeepDebugSession session, List<FlagRecord> flags) {
        sb.append("=== GRIM DEEP DEBUG REPORT ===\n");
        sb.append("Target: ").append(session.targetName).append('\n');
        sb.append("Session: ").append(time(session.startedAtMs)).append(" .. ")
                .append(time(System.currentTimeMillis()))
                .append(" (").append(Math.round(session.durationMs() / 1000.0)).append("s, ")
                .append(flags.size()).append(" flags captured)\n");
        sb.append("Grim: ").append(GrimAPI.INSTANCE.getExternalAPI().getGrimVersion());
        sb.append(", Server: ").append(PacketEvents.getAPI().getServerManager().getVersion().getReleaseName()).append('\n');
        // Reuse the alert placeholder pipeline for the client/env block.
        sb.append(MessageUtil.replacePlaceholders(session.target,
                "Client: %version%, Brand: %brand%, Ping: %ping%ms, TPS: %tps%, "
                        + "fast_math: %fast_math%, sensitivity: %h_sensitivity%/%v_sensitivity%\n"));
    }

    // ------------------------------------------------------------ flag summary

    private static void flagSummary(StringBuilder sb, List<FlagRecord> flags) {
        sb.append("\n--- FLAG SUMMARY ---\n");
        if (flags.isEmpty()) {
            sb.append("(no flags captured)\n");
            return;
        }
        Map<String, List<FlagRecord>> byCheck = new LinkedHashMap<>();
        for (FlagRecord flag : flags) {
            byCheck.computeIfAbsent(flag.checkName, k -> new ArrayList<>()).add(flag);
        }
        for (Map.Entry<String, List<FlagRecord>> entry : byCheck.entrySet()) {
            List<FlagRecord> records = entry.getValue();
            FlagRecord last = records.get(records.size() - 1);
            sb.append(String.format("%-18s x%-3d (vl %d)  ", entry.getKey(), records.size(), last.vl));
            // Most movement checks put a parseable offset first in the verbose text.
            DoubleSummary offsets = parseOffsets(records);
            if (offsets != null) {
                sb.append(String.format("offset min/avg/max: %.6f / %.6f / %.6f", offsets.min, offsets.avg, offsets.max));
            } else {
                sb.append("last verbose: ").append(truncate(last.verbose, 60));
            }
            sb.append('\n');
        }
    }

    private static @Nullable DoubleSummary parseOffsets(List<FlagRecord> records) {
        double min = Double.MAX_VALUE, max = Double.MIN_VALUE, sum = 0;
        int count = 0;
        for (FlagRecord record : records) {
            String verbose = record.verbose.split(" ")[0];
            try {
                double offset = Double.parseDouble(verbose);
                min = Math.min(min, offset);
                max = Math.max(max, offset);
                sum += offset;
                count++;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return count == 0 ? null : new DoubleSummary(min, sum / count, max);
    }

    private record DoubleSummary(double min, double avg, double max) {
    }

    // ----------------------------------------------------------- interference

    private static void interference(StringBuilder sb, List<InterferenceRecord> interference, List<FlagRecord> flags) {
        sb.append("\n--- SERVER INTERFERENCE (velocity/teleport/potion/gamemode, with source) ---\n");
        if (interference.isEmpty()) {
            sb.append("(none captured)\n");
            return;
        }
        for (InterferenceRecord record : interference) {
            int correlated = 0;
            for (FlagRecord flag : flags) {
                long delta = flag.timeMs - record.timeMs();
                if (delta >= 0 && delta <= CORRELATION_WINDOW_MS) correlated++;
            }
            sb.append('[').append(time(record.timeMs())).append("] ")
                    .append(record.kind())
                    .append(record.cancelled() ? " (CANCELLED by another plugin)" : "")
                    .append(' ').append(record.detail())
                    .append("\n    source: ").append(record.source())
                    .append(correlated > 0 ? "\n    -> " + correlated + " flag(s) within " + CORRELATION_WINDOW_MS + "ms" : "")
                    .append('\n');
        }
    }

    // ------------------------------------------------------ attribute anomalies

    private static void attributeAnomalies(StringBuilder sb, DeepDebugSession session) {
        sb.append("\n--- NON-VANILLA MOVEMENT_SPEED MODIFIERS ---\n");
        List<String> anomalies = session.attributeAnomaliesSnapshot();
        if (anomalies.isEmpty()) {
            sb.append("(none captured)\n");
            return;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String anomaly : anomalies) {
            counts.merge(anomaly, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            sb.append("x").append(entry.getValue()).append("  ").append(entry.getKey()).append('\n');
        }
    }

    // ------------------------------------------------------------- environment

    private static void environment(StringBuilder sb, DeepDebugSession session) {
        sb.append("\n--- ENVIRONMENT ATTRIBUTION ---\n");

        List<String> foreignListeners = foreignPacketListeners();
        sb.append("Foreign packet listeners (packetevents): ").append(foreignListeners.size()).append('\n');
        for (String line : foreignListeners) {
            sb.append("  ").append(line).append('\n');
        }

        List<String> subscribers = GrimAPI.INSTANCE.getDeepDebugManager()
                .getAttributionProvider().movementEventHandlerSubscribers();
        sb.append("Movement-relevant Bukkit event subscribers:\n");
        if (subscribers.isEmpty()) {
            sb.append("  (platform does not expose handler lists, or none registered)\n");
        } else {
            for (String line : subscribers) {
                sb.append("  ").append(line).append('\n');
            }
        }

        String pipeline = nettyPipeline(session);
        sb.append("Netty pipeline: ").append(pipeline == null ? "(channel unavailable)" : pipeline).append('\n');
    }

    /** Reflection over packetevents' EventManager: there is no public enumeration API. */
    private static List<String> foreignPacketListeners() {
        List<String> out = new ArrayList<>();
        try {
            EventManager manager = PacketEvents.getAPI().getEventManager();
            Field field = EventManager.class.getDeclaredField("listeners");
            field.setAccessible(true);
            PacketListenerCommon[] listeners = (PacketListenerCommon[]) field.get(manager);
            if (listeners == null) return out;
            for (PacketListenerCommon listener : listeners) {
                String className = listener.getClass().getName();
                if (className.startsWith("ac.grim.grimac.")) continue;
                String plugin = GrimAPI.INSTANCE.getDeepDebugManager()
                        .getAttributionProvider().pluginForClass(className);
                out.add("priority=" + listener.getPriority() + " preVia=" + listener.isPreVia()
                        + " class=" + className + (plugin != null ? " plugin=" + plugin : ""));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            out.add("(enumeration unavailable: " + e + ")");
        }
        return out;
    }

    private static @Nullable String nettyPipeline(DeepDebugSession session) {
        try {
            Object channel = session.target.user.getChannel();
            if (channel == null) return null;
            return com.github.retrooper.packetevents.netty.channel.ChannelHelper.pipelineHandlerNamesAsString(channel);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------- client suspects

    private static void clientSuspects(StringBuilder sb, DeepDebugSession session, List<FlagRecord> flags) {
        sb.append("\n--- CLIENT SUSPECTS ---\n");
        SprintChurn churn = session.sprintChurn;
        long minutes = Math.max(1, Math.round(session.durationMs() / 60000.0));
        long togglesPerMinute = (churn.starts() + churn.stops()) / minutes;
        long fastReEnables = churn.fastReEnables();

        long sprintFlagsWhileSprinting = flags.stream()
                .filter(f -> f.sprinting && (f.checkName.startsWith("Sprint") || f.checkName.equals("BadPacketsF") || f.checkName.equals("BadPacketsX")))
                .count();
        long movementFlagsSprinting = flags.stream()
                .filter(f -> f.sprinting && (f.checkName.equals("Simulation") || f.checkName.equals("Timer")))
                .count();

        sb.append("Sprint input: ").append(churn.starts()).append(" on / ").append(churn.stops()).append(" off")
                .append(" (~").append(togglesPerMinute).append(" toggles/min), fast re-enables (<= ")
                .append(SprintChurn.FAST_RE_ENABLE_WINDOW_MS).append("ms after off): ").append(fastReEnables).append('\n');
        sb.append("Sprint-related flags while sprinting: ").append(sprintFlagsWhileSprinting)
                .append("; Simulation/Timer flags while sprinting: ").append(movementFlagsSprinting).append('\n');

        if (fastReEnables >= 3 || togglesPerMinute >= 30) {
            sb.append("=> auto-sprint / ToggleSprint mod: LIKELY (instant re-enables + churn)\n");
        } else if (fastReEnables > 0 || sprintFlagsWhileSprinting > 0) {
            sb.append("=> auto-sprint / ToggleSprint mod: POSSIBLE (see churn + Sprint flags)\n");
        } else {
            sb.append("=> auto-sprint / ToggleSprint mod: no signal\n");
        }
        sb.append("Brand note: many modded clients (lunar, feather, essential...) ship toggle-sprint by default.\n");
    }

    // -------------------------------------------------------- movement contexts

    private static void movementContexts(StringBuilder sb, List<FlagRecord> flags) {
        sb.append("\n--- MOVEMENT CONTEXT (most recent flagged movements) ---\n");
        List<FlagRecord> withContext = new ArrayList<>();
        for (FlagRecord flag : flags) {
            if (flag.movement != null) withContext.add(flag);
        }
        if (withContext.isEmpty()) {
            sb.append("(no movement context captured)\n");
            return;
        }
        int from = Math.max(0, withContext.size() - MAX_MOVEMENT_CONTEXTS);
        for (int i = from; i < withContext.size(); i++) {
            FlagRecord flag = withContext.get(i);
            MovementContext m = flag.movement;
            sb.append("\n[").append(time(flag.timeMs)).append("] ").append(flag.checkName)
                    .append(" vl ").append(flag.vl).append(" verbose: ").append(truncate(flag.verbose, 80)).append('\n');
            sb.append(String.format("  pos %.3f %.3f %.3f yaw %.1f pitch %.1f world %s offset %.6f%n",
                    m.x, m.y, m.z, m.yaw, m.pitch, m.world, m.offset));
            sb.append("  predicted ").append(m.predicted).append("  actual ").append(m.actual)
                    .append("  startVel ").append(m.startTickVelocity).append('\n');
            sb.append(String.format("  speed %.4f gravity %.4f friction %.4f sprintAttr=%s food=%d fall=%.2f%n",
                    m.speed, m.gravity, m.friction, m.sprintAttributeEnabled, m.food, m.fallDistance));
            sb.append("  state: sprint=").append(m.sprinting).append(" sneak=").append(m.sneaking)
                    .append(" glide=").append(m.gliding).append(" swim=").append(m.swimming)
                    .append(" riptide=").append(m.riptidePose).append(" water=").append(m.touchingWater)
                    .append(" lava=").append(m.touchingLava).append(" vehicle=").append(m.inVehicle)
                    .append(" ground=").append(m.onGround).append("/").append(m.clientClaimsGround)
                    .append(" 0.03skip=").append(m.skippedTick).append('\n');
            sb.append("  vector provenance: ").append(m.vectorProvenance).append('\n');
            if (m.pendingKnockbackDetail != null) {
                sb.append("  pending knockback: ").append(m.pendingKnockbackDetail).append('\n');
            }
            if (m.pendingExplosionDetail != null) {
                sb.append("  pending explosion: ").append(m.pendingExplosionDetail).append('\n');
            }
            sb.append("  uncertainty: ").append(m.uncertaintySummary).append('\n');
        }
    }

    // ----------------------------------------------------------------- helpers

    private static String time(long epochMs) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault()).format(TIME);
    }

    private static String truncate(String text, int maxLength) {
        String flat = text.replace('\n', ' ');
        return flat.length() <= maxLength ? flat : flat.substring(0, maxLength) + "...";
    }
}
