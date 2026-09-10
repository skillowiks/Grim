package ac.grim.grimac.checks.impl.aim.triggerbot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TriggerBotReportSlotTest {
    @Test
    void lateCompletionCannotOverwriteDisabledStatus() {
        TriggerBotReportSlot slot = new TriggerBotReportSlot();
        long queued = slot.reserve();
        slot.replace("disabled");
        assertFalse(slot.publish(queued, "old data"));
        assertEquals("disabled", slot.text());
    }

    @Test
    void outOfOrderCompletionCannotReplaceNewerSnapshot() {
        TriggerBotReportSlot slot = new TriggerBotReportSlot();
        long first = slot.reserve();
        long second = slot.reserve();
        assertTrue(slot.publish(second, "tick 40"));
        assertFalse(slot.publish(first, "tick 20"));
        assertEquals("tick 40", slot.text());
    }

    @Test
    void skippedSubmissionRetainsLastCompletedText() {
        TriggerBotReportSlot slot = new TriggerBotReportSlot();
        assertTrue(slot.publish(slot.reserve(), "completed"));
        slot.reserve(); // Executor saturation: never run the reserved task.
        assertEquals("completed", slot.text());
        assertTrue(slot.publish(slot.reserve(), "recovered"));
        assertEquals("recovered", slot.text());
    }

    @Test
    void stopFreezesCompletedReportEvenForAlreadyRunningWorker() {
        TriggerBotReportSlot slot = new TriggerBotReportSlot();
        slot.replace("last completed");
        long running = slot.reserve();
        slot.freeze();
        assertFalse(slot.publish(running, "late completion"));
        slot.replace("late packet status");
        assertEquals(-1, slot.reserve());
        assertFalse(slot.publish(-1, "after stop"));
        assertEquals("last completed", slot.text());
    }
}
