package ac.grim.grimac.manager;

import ac.grim.grimac.api.AbstractCheck;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertOnlyPunishmentGroupTest {
    @Test
    void triggerBotCannotPrimeBanOnNextReachFlagInMixedGroup() {
        AbstractCheck reach = check("Reach");
        AbstractCheck triggerBot = check("TriggerBot");
        ParsedCommand ban = new ParsedCommand(2, 0, "ban %player%");
        List<PunishGroup> groups = PunishGroup.partition(List.of(reach, triggerBot),
                List.of(new ParsedCommand(1, 1, "[alert]"), ban), 300_000,
                check -> check == triggerBot);

        PunishGroup regular = groupFor(groups, reach);
        PunishGroup notification = groupFor(groups, triggerBot);
        for (int i = 0; i < 100; i++) record(groups, triggerBot, i);

        assertEquals(100, notification.violations.size());
        assertEquals(0, regular.violations.size());
        assertEquals(List.of("[alert]"), notification.commands.stream().map(command -> command.command).toList());
        assertFalse(ban.canExecute(regular.violations.size()));

        record(groups, reach, 100);
        assertEquals(1, regular.violations.size());
        assertEquals(0, regular.violations.count(triggerBot));
        assertFalse(ban.canExecute(regular.violations.size()));

        record(groups, reach, 101);
        assertTrue(ban.canExecute(regular.violations.size()));
        assertEquals(100, notification.violations.size());
    }

    @Test
    void triggerBotCannotConsumeOtherChecksIntervalOrOneShotCommands() {
        AbstractCheck reach = check("Reach");
        AbstractCheck triggerBot = check("TriggerBot");
        ParsedCommand alert = new ParsedCommand(5, 3, "[alert]");
        ParsedCommand webhook = new ParsedCommand(5, 0, "[webhook]");
        List<PunishGroup> groups = PunishGroup.partition(List.of(reach, triggerBot),
                List.of(alert, webhook), 12_345, check -> check == triggerBot);
        PunishGroup regular = groupFor(groups, reach);
        PunishGroup notification = groupFor(groups, triggerBot);

        assertSame(alert, regular.commands.get(0));
        assertSame(webhook, regular.commands.get(1));
        assertNotSame(alert, notification.commands.get(0));
        assertNotSame(webhook, notification.commands.get(1));
        assertEquals(12_345, notification.removeViolationsAfter);
        assertEquals(5, notification.commands.get(0).threshold);
        assertEquals(3, notification.commands.get(0).interval);

        notification.commands.get(0).nextBoundary = 8;
        notification.commands.get(1).executeCount = 1;
        assertTrue(alert.canExecute(5));
        assertTrue(webhook.canExecute(5));
        assertFalse(notification.commands.get(0).canExecute(5));
        assertFalse(notification.commands.get(1).canExecute(5));
    }

    @Test
    void onlyExactNotificationBuiltinsSurviveInAlertOnlyGroups() {
        AbstractCheck triggerBot = check("TriggerBot");
        List<String> configured = List.of("[alert]", "[log]", "[webhook]", "[proxy]",
                "ban %player%", "kick %player%", "tp %player% 0 0 0", "[alert] ban %player%",
                " [alert]", "[alert] ", "[ALERT]", "say [alert]", "[setback]");
        List<ParsedCommand> commands = configured.stream().map(command -> new ParsedCommand(1, 1, command)).toList();
        List<PunishGroup> groups = PunishGroup.partition(List.of(triggerBot), commands, 300_000, check -> true);

        assertEquals(1, groups.size());
        assertEquals(List.of("[alert]", "[log]", "[webhook]", "[proxy]"),
                groups.get(0).commands.stream().map(command -> command.command).toList());
    }

    @Test
    void distinctAlertOnlyChecksAndRepeatedAliasesHaveIndependentState() {
        AbstractCheck first = check("TriggerBot");
        AbstractCheck second = check("AnotherAlertOnlyCheck");
        List<PunishGroup> groups = PunishGroup.partition(List.of(first, second, first),
                List.of(new ParsedCommand(2, 1, "[alert]")), 1000, check -> true);
        assertEquals(2, groups.size());
        record(groups, first, 0);
        record(groups, first, 1);

        PunishGroup firstGroup = groupFor(groups, first);
        PunishGroup secondGroup = groupFor(groups, second);
        assertEquals(2, firstGroup.violations.size());
        assertEquals(0, secondGroup.violations.size());
        assertTrue(firstGroup.commands.get(0).canExecute(firstGroup.violations.size()));
        assertFalse(secondGroup.commands.get(0).canExecute(secondGroup.violations.size()));
        assertNotSame(firstGroup.commands.get(0), secondGroup.commands.get(0));
    }

    @Test
    void unmodifiedGroupsPreserveMembershipCommandObjectsAndSharedViolationCounts() {
        AbstractCheck reach = check("Reach");
        AbstractCheck hitboxes = check("Hitboxes");
        List<AbstractCheck> checks = new ArrayList<>(List.of(reach, hitboxes));
        List<ParsedCommand> commands = new ArrayList<>(List.of(new ParsedCommand(2, 0, "ban %player%")));
        List<PunishGroup> groups = PunishGroup.partition(checks, commands, 1000, check -> false);
        assertEquals(1, groups.size());
        assertSame(checks, groups.get(0).checks);
        assertSame(commands, groups.get(0).commands);

        record(groups, reach, 0);
        record(groups, hitboxes, 1);
        assertEquals(2, groups.get(0).violations.size());
        assertTrue(commands.get(0).canExecute(groups.get(0).violations.size()));
    }

    @Test
    void alertOnlyGroupWithNoNotificationCommandsCannotDispatchAnything() {
        AbstractCheck triggerBot = check("TriggerBot");
        List<PunishGroup> groups = PunishGroup.partition(List.of(triggerBot),
                List.of(new ParsedCommand(1, 1, "ban %player%")), 1000, check -> true);
        assertEquals(1, groups.size());
        assertEquals(List.of(triggerBot), groups.get(0).checks);
        assertTrue(groups.get(0).commands.isEmpty());
    }

    private static PunishGroup groupFor(List<PunishGroup> groups, AbstractCheck check) {
        return groups.stream().filter(group -> group.checks.contains(check)).findFirst().orElseThrow();
    }

    private static void record(List<PunishGroup> groups, AbstractCheck check, long time) {
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) group.violations.record(time, check, group.removeViolationsAfter);
        }
    }

    private static AbstractCheck check(String name) {
        return (AbstractCheck) Proxy.newProxyInstance(AbstractCheck.class.getClassLoader(),
                new Class<?>[]{AbstractCheck.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString", "getCheckName" -> name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
