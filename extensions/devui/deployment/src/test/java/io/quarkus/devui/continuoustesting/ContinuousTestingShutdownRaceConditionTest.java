package io.quarkus.devui.continuoustesting;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.dev.testing.ContinuousTestingSharedStateManager;
import io.quarkus.runtime.ShutdownContext;

/**
 * Unit test that simulates the race condition described in:
 * https://github.com/apache/camel-quarkus/issues/8318
 *
 * Race condition scenario:
 * 1. First test class runs - state is set, DevConsoleManager actions are registered
 * 2. Test ends - shutdown runs, DevConsoleManager.close() clears all actions
 * 3. Second test class starts - RUNTIME_INIT adds state listener
 * 4. Immediate callback fires because lastState != null from previous test
 * 5. Callback tries to invoke DevConsoleManager action
 * 6. BUILD phase hasn't registered actions yet → NoSuchElementException
 *
 * Fix:
 * ContinuousTestingRecorder shutdown task now calls ContinuousTestingSharedStateManager.reset()
 * to reset lastState to INITIAL_STATE, preventing immediate callback with stale state.
 */
public class ContinuousTestingShutdownRaceConditionTest {

    @BeforeEach
    public void setup() {
        // Clean slate before each test
        ContinuousTestingSharedStateManager.reset();
        DevConsoleManager.close();
    }

    @AfterEach
    public void cleanup() {
        ContinuousTestingSharedStateManager.reset();
        DevConsoleManager.close();
    }

    /**
     * Test that WITH the fix (shutdown calls reset()), the race condition does not occur.
     */
    @Test
    public void testShutdownResetsStateToPreventRaceCondition() {
        System.out.println("\n=== Testing Race Condition Fix ===");

        // === FIRST TEST CLASS EXECUTION ===
        System.out.println("\n1. Simulating first test class execution:");

        // Register action (simulates BUILD phase registering DevConsoleManager actions)
        DevConsoleManager.register("devui-continuous-testing_getResults", params -> {
            return "mock-test-results";
        });
        System.out.println("   ✓ Registered DevConsoleManager action 'devui-continuous-testing_getResults'");

        // Set state with actual test data (simulates continuous testing running)
        long testRunTime = System.currentTimeMillis();
        ContinuousTestingSharedStateManager.setLastState(s -> s.setLastRun(testRunTime)
                .setRunning(true)
                .setPassed(5)
                .setFailed(0)
                .build());
        System.out.println("   ✓ Set lastState with test results (lastRun=" + testRunTime + ", passed=5)");

        // Add a listener to verify it receives immediate callback
        AtomicInteger firstPhaseCallbackCount = new AtomicInteger(0);
        ContinuousTestingSharedStateManager.addStateListener(state -> {
            firstPhaseCallbackCount.incrementAndGet();
        });

        assertEquals(1, firstPhaseCallbackCount.get(), "Should receive immediate callback with existing state");
        System.out.println("   ✓ Listener received immediate callback");

        // === END OF FIRST TEST CLASS - SHUTDOWN ===
        System.out.println("\n2. Simulating test class shutdown:");

        // Simulate the ContinuousTestingRecorder shutdown task (WITH THE FIX)
        ShutdownContext shutdownContext = new ShutdownContext() {
            @Override
            public void addShutdownTask(Runnable runnable) {
                runnable.run(); // Execute immediately in test
            }

            @Override
            public void addLastShutdownTask(Runnable runnable) {
                runnable.run();
            }
        };

        shutdownContext.addShutdownTask(() -> {
            // This simulates what ContinuousTestingRecorder.createContinuousTestingSharedStateManager does
            System.out.println("   - Shutdown task: removing listener and resetting state");
            ContinuousTestingSharedStateManager.reset(); // THE FIX
        });

        // Clear DevConsoleManager actions (simulates DevConsoleManager.close())
        DevConsoleManager.close();
        System.out.println("   ✓ DevConsoleManager actions cleared");

        // Verify state is reset to INITIAL_STATE
        ContinuousTestingSharedStateManager.State stateAfterShutdown = ContinuousTestingSharedStateManager
                .getLastState();
        assertEquals(-1, stateAfterShutdown.lastRun,
                "State should be reset to INITIAL_STATE with lastRun=-1");
        System.out.println("   ✓ State reset to INITIAL_STATE (lastRun=" + stateAfterShutdown.lastRun + ")");

        // === SECOND TEST CLASS EXECUTION - RUNTIME_INIT (before BUILD) ===
        System.out.println("\n3. Simulating second test class RUNTIME_INIT (BUILD phase hasn't run yet):");
        System.out.println("   - Note: DevConsoleManager actions NOT registered yet (BUILD phase pending)");

        // Track if immediate callback fires and if it tries to invoke action
        AtomicBoolean immediateCallbackFired = new AtomicBoolean(false);
        AtomicBoolean exceptionThrown = new AtomicBoolean(false);
        AtomicReference<Long> callbackLastRun = new AtomicReference<>(-1L);

        // Add listener during RUNTIME_INIT (simulates ContinuousTestingRecorder adding listener)
        ContinuousTestingSharedStateManager.addStateListener(state -> {
            immediateCallbackFired.set(true);
            callbackLastRun.set(state.lastRun);
            System.out.println("   - Immediate callback fired with lastRun=" + state.lastRun);

            // Simulate what ContinuousTestingJsonRPCService.accept() does
            if (state.lastRun > 0) {
                // This would happen if state wasn't reset - tries to invoke action
                System.out.println("   - lastRun > 0, would try to invoke DevConsoleManager action...");
                try {
                    DevConsoleManager.invoke("devui-continuous-testing_getResults", null);
                    System.out.println("   ✓ Action invoked successfully");
                } catch (java.util.NoSuchElementException e) {
                    System.out.println("   ✗ CRASH - NoSuchElementException!");
                    exceptionThrown.set(true);
                }
            } else {
                System.out.println("   - lastRun=-1 (INITIAL_STATE), skipping action invoke");
            }
        });

        // === VERIFY THE FIX ===
        System.out.println("\n4. Verification:");

        assertTrue(immediateCallbackFired.get(),
                "Immediate callback should fire (lastState is not null, it's INITIAL_STATE)");
        System.out.println("   ✓ Immediate callback fired as expected");

        assertEquals(-1L, callbackLastRun.get().longValue(),
                "Callback should receive INITIAL_STATE with lastRun=-1");
        System.out.println("   ✓ Callback received INITIAL_STATE (lastRun=-1)");

        assertFalse(exceptionThrown.get(),
                "Should NOT throw NoSuchElementException because state.lastRun=-1 prevents action invoke");
        System.out.println("   ✓ No exception thrown - race condition prevented!");

        System.out.println("\n=== TEST PASSED: Fix prevents race condition ===\n");
    }

    /**
     * Test that WITHOUT the fix (shutdown doesn't call reset()), the race condition occurs.
     */
    @Test
    public void testWithoutFixCausesRaceCondition() {
        System.out.println("\n=== Demonstrating Race Condition WITHOUT Fix ===");

        // Setup: register action and set state
        DevConsoleManager.register("devui-continuous-testing_getResults", params -> "mock-results");

        long testRunTime = System.currentTimeMillis();
        ContinuousTestingSharedStateManager.setLastState(s -> s.setLastRun(testRunTime)
                .setPassed(5)
                .build());

        System.out.println("\n1. State set with lastRun=" + testRunTime);

        // Shutdown WITHOUT calling reset (simulates old broken behavior)
        System.out.println("2. Shutdown WITHOUT reset (old broken behavior)");
        DevConsoleManager.close();
        System.out.println("   - DevConsoleManager actions cleared");

        ContinuousTestingSharedStateManager.State stateAfterBrokenShutdown = ContinuousTestingSharedStateManager
                .getLastState();
        System.out.println("   - lastState STILL has lastRun=" + stateAfterBrokenShutdown.lastRun + " (NOT reset!)");

        // Try to add listener - should crash because state has lastRun > 0
        System.out.println("3. Adding listener in RUNTIME_INIT...");

        assertThrows(java.util.NoSuchElementException.class, () -> {
            ContinuousTestingSharedStateManager.addStateListener(state -> {
                System.out.println("   - Immediate callback fired with lastRun=" + state.lastRun);
                if (state.lastRun > 0) {
                    System.out.println("   - Trying to invoke action (0 actions available)...");
                    // This will throw NoSuchElementException because actions were cleared
                    DevConsoleManager.invoke("devui-continuous-testing_getResults", null);
                }
            });
        }, "Should throw NoSuchElementException without the fix");

        System.out.println("   ✓ Confirmed: WITHOUT fix, NoSuchElementException occurs!");
        System.out.println("\n=== TEST PASSED: Demonstrated race condition without fix ===\n");
    }
}
