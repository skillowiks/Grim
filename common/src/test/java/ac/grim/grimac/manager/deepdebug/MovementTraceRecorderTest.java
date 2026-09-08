package ac.grim.grimac.manager.deepdebug;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementTraceRecorderTest {
    @Test
    void keepsBoundedEventsWithTheirMovementAndClearsPendingEvents() {
        MovementTraceRecorder recorder = new MovementTraceRecorder();
        for (int i = 0; i < 10; i++) recorder.event("attack=" + i);
        recorder.movement("before");
        recorder.flag("Simulation");
        recorder.movement("trigger");
        String trace = recorder.snapshot().get(0);
        assertTrue(trace.contains("attack=0"));
        assertTrue(trace.contains("attack=7"));
        assertTrue(trace.contains("omitted=2"));
        assertEquals("FLAG [Simulation] trigger", trace.split("\n")[1]);
        recorder.event("late-event");
        recorder.movement("after");
        assertEquals(trace, recorder.snapshot().get(0).substring(0, trace.length()));
    }

    @Test
    void capturesHistoryTriggerAndFollowingMovements() {
        MovementTraceRecorder recorder = new MovementTraceRecorder();
        for (int i = 0; i < 70; i++) recorder.movement("tick=" + i);
        recorder.flag("Simulation");
        recorder.flag("NoSlow");
        recorder.movement("tick=70");
        for (int i = 71; i < 120; i++) recorder.movement("tick=" + i);
        String[] lines = recorder.snapshot().get(0).split("\n");
        assertEquals(101, lines.length);
        assertEquals("tick=10", lines[0]);
        assertEquals("FLAG [Simulation, NoSlow] tick=70", lines[60]);
        assertEquals("tick=110", lines[100]);
    }

    @Test
    void boundsFlagStormAndKeepsPublishedSnapshotsImmutable() {
        MovementTraceRecorder recorder = new MovementTraceRecorder();
        recorder.flag("Simulation");
        recorder.movement("first");
        var snapshot = recorder.snapshot();
        for (int i = 0; i < 1000; i++) {
            recorder.flag("Simulation");
            recorder.movement("tick=" + i);
        }
        assertEquals("FLAG [Simulation] first", snapshot.get(0));
        assertEquals(3, recorder.snapshot().size());
        assertTrue(recorder.snapshot().stream().allMatch(trace -> trace.split("\n").length <= 101));
    }

    @Test
    void doesNotExportUnflaggedHistory() {
        MovementTraceRecorder recorder = new MovementTraceRecorder();
        for (int i = 0; i < 1000; i++) recorder.movement("tick=" + i);
        assertTrue(recorder.snapshot().isEmpty());
    }
}
