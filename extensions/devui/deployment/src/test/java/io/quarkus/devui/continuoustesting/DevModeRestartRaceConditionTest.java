package io.quarkus.devui.continuoustesting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.dev.testing.ContinuousTestingSharedStateManager;

/**
 * Test for race condition in continuous testing during dev mode restart.
 *
 * When multiple test classes run sequentially, DevConsoleManager.close() clears actions
 * but state persists. If RUNTIME_INIT runs before BUILD phase registers actions,
 * immediate callback tries to invoke missing action causing NoSuchElementException.
 *
 * Fix: DevConsoleManager.close() resets state, and listener checks lastRun before invoke.
 */
public class DevModeRestartRaceConditionTest {

    @BeforeEach
    public void setup() {
        ContinuousTestingSharedStateManager.reset();
        DevConsoleManager.close();
    }

    @AfterEach
    public void cleanup() {
        ContinuousTestingSharedStateManager.reset();
        DevConsoleManager.close();
    }

    @Test
    public void testWithoutReset_shouldCrash() {
        assertThrows(java.util.NoSuchElementException.class, () -> {
            simulateDevModeLifecycle(false);
        });
    }

    @Test
    public void testWithReset_shouldNotCrash() {
        assertDoesNotThrow(() -> {
            simulateDevModeLifecycle(true);
        });
    }

    @Test
    public void testRealisticScenario() {
        // First test class
        DevConsoleManager.register("devui-continuous-testing_getResults", params -> "results");
        ContinuousTestingSharedStateManager.setLastState(s -> s.setLastRun(100).setPassed(5).build());

        // Dev mode restart
        DevConsoleManager.close();

        // Second test class - RUNTIME_INIT before BUILD
        AtomicBoolean crashed = new AtomicBoolean(false);
        ContinuousTestingSharedStateManager.addStateListener(state -> {
            if (state.lastRun > 0) {
                try {
                    DevConsoleManager.invoke("devui-continuous-testing_getResults");
                } catch (Exception e) {
                    crashed.set(true);
                }
            }
        });

        assertFalse(crashed.get());
        assertEquals(-1, ContinuousTestingSharedStateManager.getLastState().lastRun);
    }

    private void simulateDevModeLifecycle(boolean withFix) {
        // First test class
        DevConsoleManager.register("devui-continuous-testing_getResults", params -> "test-results");
        long testRunTime = System.currentTimeMillis();
        ContinuousTestingSharedStateManager.setLastState(s -> s
                .setLastRun(testRunTime)
                .setRunning(true)
                .setPassed(5)
                .build());

        // Dev mode restart
        DevConsoleManager.close();

        // Simulate old behavior without fix
        if (!withFix) {
            ContinuousTestingSharedStateManager.setLastState(s -> s
                    .setLastRun(testRunTime)
                    .setPassed(5)
                    .build());
        }

        // Second test class - listener fires before BUILD registers actions
        ContinuousTestingSharedStateManager.addStateListener(state -> {
            if (state.lastRun > 0) {
                DevConsoleManager.invoke("devui-continuous-testing_getResults");
            }
        });
    }
}
