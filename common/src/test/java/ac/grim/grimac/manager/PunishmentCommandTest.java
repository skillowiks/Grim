package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PunishmentCommandTest {
    @Test
    void everyFlagCommandRunsAgainAfterAllPreviousViolationsExpire() {
        ParsedCommand command = new ParsedCommand(1, 1, "[log]");
        ViolationHistory<String> history = new ViolationHistory<>();
        history.record(0, "Reach", 1000);
        assertTrue(command.canExecute(history.size()));
        command.nextBoundary = 2; // Boundary after the first execution.

        history.record(1001, "Reach", 1000);
        assertEquals(1, history.size());
        assertTrue(command.canExecute(history.size()));
    }

    @Test
    void everyFlagCommandRunsAtAPlateauAndAfterRollingCountFalls() {
        ParsedCommand command = new ParsedCommand(1, 1, "[log]");
        ViolationHistory<String> history = new ViolationHistory<>();
        history.record(0, "Reach", 1000);
        history.record(100, "Reach", 1000);
        history.record(200, "Reach", 1000);
        command.nextBoundary = 4; // Three previous executions.

        history.record(1001, "Reach", 1000);
        assertEquals(3, history.size());
        assertTrue(command.canExecute(history.size()));

        history.record(2200, "Reach", 1000);
        assertEquals(1, history.size());
        assertTrue(command.canExecute(history.size()));
    }

    @Test
    void everyFlagCommandStillRequiresAnActiveViolation() {
        ParsedCommand command = new ParsedCommand(1, 1, "[alert]");
        assertFalse(command.canExecute(0));
        assertTrue(command.canExecute(1));
        command.nextBoundary = 100;
        assertTrue(command.canExecute(1));
    }

    @Test
    void otherIntervalsKeepTheirActiveCountBoundary() {
        ParsedCommand command = new ParsedCommand(5, 3, "[webhook]");
        assertFalse(command.canExecute(4));
        assertTrue(command.canExecute(5));
        command.nextBoundary = 8;
        assertFalse(command.canExecute(5));
        assertFalse(command.canExecute(7));
        assertTrue(command.canExecute(8));
        command.nextBoundary = 11;
        assertFalse(command.canExecute(5));
        assertFalse(command.canExecute(10));
        assertTrue(command.canExecute(11));
    }

    @Test
    void specialCaseDoesNotApplyToOnlyOneMatchingNumber() {
        ParsedCommand everyOtherFlag = new ParsedCommand(1, 2, "[log]");
        everyOtherFlag.nextBoundary = 3;
        assertFalse(everyOtherFlag.canExecute(1));
        assertTrue(everyOtherFlag.canExecute(3));

        ParsedCommand higherThreshold = new ParsedCommand(2, 1, "[log]");
        higherThreshold.nextBoundary = 4;
        assertFalse(higherThreshold.canExecute(2));
        assertTrue(higherThreshold.canExecute(4));
    }

    @Test
    void oneShotCommandRemainsSpentAfterItsFirstExecution() {
        ParsedCommand command = new ParsedCommand(5, 0, "[webhook]");
        assertFalse(command.canExecute(4));
        assertTrue(command.canExecute(5));
        command.executeCount = 1;
        assertFalse(command.canExecute(0));
        assertFalse(command.canExecute(5));
        assertFalse(command.canExecute(100));
    }
}
