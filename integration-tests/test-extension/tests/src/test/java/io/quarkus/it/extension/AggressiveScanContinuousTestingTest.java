package io.quarkus.it.extension;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.extest.runtime.AggressiveScanHotReplacementSetup;
import io.quarkus.test.ContinuousTestingTestUtils;
import io.quarkus.test.ContinuousTestingTestUtils.TestStatus;
import io.quarkus.test.QuarkusDevModeTest;

/**
 * Test to actively trigger race conditions with aggressive periodic scanning.
 * This demonstrates the issue where HotReplacementSetup.doScan() interferes
 * with continuous testing.
 *
 * Expected behavior:
 * - testWithoutAggressiveScanning (baseline) should complete reliably
 * - testWithAggressiveScanning may experience spurious restarts
 * - We track lastRun increments to detect multiple restarts
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AggressiveScanContinuousTestingTest {

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(SimpleResource.class)
                    .add(new StringAsset(
                            ContinuousTestingTestUtils.appProperties(
                                    "test.message=first")),
                            "application.properties"))
            .setTestArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClass(SimpleResourceET.class));

    /**
     * Baseline test WITHOUT aggressive scanning.
     * This establishes expected behavior - should have exactly one restart.
     */
    @Test
    @Order(1)
    public void testWithoutAggressiveScanning() throws Exception {
        // Ensure aggressive scanning is disabled
        AggressiveScanHotReplacementSetup.setEnabled(false);
        AggressiveScanHotReplacementSetup.resetScanCount();

        ContinuousTestingTestUtils utils = new ContinuousTestingTestUtils();

        System.out.println("\n========================================");
        System.out.println("BASELINE: No aggressive scanning");
        System.out.println("========================================");

        TestStatus ts = utils.waitForNextCompletion();
        long initialRun = ts.getLastRun();
        System.out.println("Initial run: " + ts);
        System.out.println("  lastRun=" + initialRun);

        // Give a bit of time to ensure system is stable
        Thread.sleep(500);

        // Modify file - should trigger exactly ONE restart
        System.out.println("\nModifying application.properties...");
        long modifyStart = System.currentTimeMillis();

        TEST.modifyResourceFile("application.properties",
                s -> s.replace("first", "second"));

        long modifyDuration = System.currentTimeMillis() - modifyStart;
        System.out.println("Modification completed in " + modifyDuration + "ms");

        // Wait for completion
        ts = utils.waitForNextCompletion();
        long finalRun = ts.getLastRun();
        System.out.println("\nAfter modification: " + ts);
        System.out.println("  lastRun=" + finalRun);

        long restartCount = finalRun - initialRun;
        System.out.println("\n>>> BASELINE RESULT: " + restartCount + " restart(s) after file modification");
        System.out.println(">>> Expected: 1 restart");

        // Baseline should have exactly 1 restart
        Assertions.assertEquals(1, restartCount,
                "Baseline should have exactly 1 restart, but had " + restartCount);
        Assertions.assertTrue(ts.getTestsFailed() + ts.getTestsPassed() > 0);
    }

    /**
     * Test WITH aggressive scanning enabled.
     * This may trigger spurious restarts due to race conditions.
     */
    @Test
    @Order(2)
    public void testWithAggressiveScanning() throws Exception {
        // Enable aggressive scanning (50ms interval)
        AggressiveScanHotReplacementSetup.setEnabled(true);
        AggressiveScanHotReplacementSetup.resetScanCount();

        System.out.println("Aggressive scanning set to ENABLED");

        ContinuousTestingTestUtils utils = new ContinuousTestingTestUtils();

        System.out.println("\n========================================");
        System.out.println("AGGRESSIVE SCANNING: Enabled (500ms)");
        System.out.println("========================================");

        TestStatus ts = utils.waitForNextCompletion();
        long initialRun = ts.getLastRun();
        System.out.println("Initial run: " + ts);
        System.out.println("  lastRun=" + initialRun);

        // Let aggressive scanning run for a bit to ensure it's active
        System.out.println("\nWaiting 3s to let aggressive scanning activate (50ms intervals)...");
        Thread.sleep(3000);

        // Start a background thread that continuously triggers scans
        // This maximizes the chance of catching the file during timestamp updates
        AtomicInteger backgroundScans = new AtomicInteger(0);
        Thread hammerThread = new Thread(() -> {
            System.out.println("Background hammer thread starting - rapid fire scans!");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AggressiveScanHotReplacementSetup.triggerScan();
                    backgroundScans.incrementAndGet();
                    Thread.sleep(10); // Scan every 10ms!
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.println("Background hammer thread stopped after " + backgroundScans.get() + " scans");
        });
        hammerThread.setDaemon(true);
        hammerThread.start();

        // Give hammer thread a moment to start
        Thread.sleep(100);

        // Modify file while aggressive scanning AND hammer thread are active
        System.out.println("\nModifying application.properties (EXTREME concurrent scanning active)...");
        long modifyStart = System.currentTimeMillis();

        TEST.modifyResourceFile("application.properties",
                s -> s.replace("second", "third"));

        long modifyDuration = System.currentTimeMillis() - modifyStart;
        System.out.println("Modification completed in " + modifyDuration + "ms");
        System.out.println("Background scans during modification: " + backgroundScans.get());

        // Wait for completion while hammer thread is still running
        System.out.println("\nWaiting for test completion (hammer thread still active)...");
        try {
            ts = utils.waitForNextCompletion();
        } catch (Exception e) {
            System.out.println("\n!!! EXCEPTION during waitForNextCompletion !!!");
            System.out.println("Exception: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("Background scans: " + backgroundScans.get());
            hammerThread.interrupt();
            throw e;
        } finally {
            hammerThread.interrupt();
        }

        long finalRun = ts.getLastRun();
        System.out.println("\nAfter modification: " + ts);
        System.out.println("  lastRun=" + finalRun);
        System.out.println("  Total background scans: " + backgroundScans.get());

        long restartCount = finalRun - initialRun;
        System.out.println("\n>>> AGGRESSIVE SCANNING RESULT: " + restartCount + " restart(s) after file modification");
        System.out.println(">>> Expected: 1 restart");
        System.out.println(">>> If > 1: RACE CONDITION - spurious restarts detected!");

        // Log the result - we expect this might be > 1 due to race conditions
        if (restartCount > 1) {
            System.out.println("\n!!! RACE CONDITION REPRODUCED !!!");
            System.out.println("Multiple restarts detected: " + restartCount);
            System.out.println("Background scans that triggered it: " + backgroundScans.get());
            System.out.println("This demonstrates the bug!");
        } else {
            System.out.println("\nNo race condition detected this time.");
            System.out.println("(Note: This is timing-dependent. Try running multiple times.)");
        }

        // Test should complete despite any races
        Assertions.assertTrue(ts.getTestsFailed() + ts.getTestsPassed() > 0,
                "Tests should complete despite potential races");

        // Optionally assert that we detected the race
        // Uncomment to make test fail when race is NOT reproduced
        // Assertions.assertTrue(restartCount > 1, "Expected to reproduce race condition with multiple restarts");
    }

    /**
     * Crash reproducer: trigger restart lifecycle race condition.
     *
     * This reproduces the Camel Quarkus CI issue where periodic scan during restart causes:
     * NoSuchElementException: devui-continuous-testing_getResults
     *
     * ROOT CAUSE:
     * 1. Restart begins → DevConsoleManager.close() clears all actions
     * 2. RUNTIME_INIT runs → ContinuousTestingJsonRPCService listener is registered
     * 3. Listener.accept() is called immediately → tries to invoke "devui-continuous-testing_getResults"
     * 4. 💥 NoSuchElementException - build-time hasn't re-registered actions yet!
     *
     * This test uses aggressive scanning with restart hooks (addPreRestartStep/addPostRestartStep)
     * to maximize the chance of triggering scans during the critical window when
     * DevConsoleManager actions are cleared but not yet re-registered.
     */
    @Test
    @Order(3)
    public void testRestartLifecycleRaceCondition() throws Exception {
        // ENABLE aggressive scanning - this will trigger scans in restart hooks
        AggressiveScanHotReplacementSetup.setEnabled(true);
        AggressiveScanHotReplacementSetup.resetScanCount();

        System.out.println("\n========================================");
        System.out.println("RESTART LIFECYCLE RACE CONDITION TEST");
        System.out.println("========================================");
        System.out.println("Aggressive scanning: ENABLED");
        System.out.println("This will trigger scans in addPreRestartStep and addPostRestartStep");
        System.out.println("to reproduce the DevConsoleManager race condition");

        ContinuousTestingTestUtils utils = new ContinuousTestingTestUtils();

        TestStatus ts = utils.waitForNextCompletion();
        long initialRun = ts.getLastRun();
        System.out.println("\nInitial run: " + ts);
        System.out.println("  lastRun=" + initialRun);

        // Now trigger MULTIPLE rapid restarts to maximize chance of race condition
        System.out.println("\n>>> Triggering RAPID SEQUENTIAL RESTARTS to hit race condition...");

        for (int i = 1; i <= 3; i++) {
            System.out.println("\n--- Restart #" + i + " ---");
            String from = i == 1 ? "third" : "value" + (i - 1);
            String to = "value" + i;

            System.out.println("Modifying application.properties: " + from + " → " + to);
            TEST.modifyResourceFile("application.properties", s -> s.replace(from, to));

            // Don't wait - immediately trigger next restart!
            // This creates overlapping restarts which maximizes race condition chance
            if (i < 3) {
                Thread.sleep(100); // Just 100ms between restarts!
            }
        }

        // Now wait for final completion
        System.out.println("\n>>> Waiting for final test completion...");
        try {
            ts = utils.waitForNextCompletion();
            long finalRun = ts.getLastRun();

            System.out.println("\n=== FINAL RESULTS ===");
            System.out.println("Final state: " + ts);
            System.out.println("  lastRun=" + finalRun);
            System.out.println("  Total restarts: " + (finalRun - initialRun));
            System.out.println("  Aggressive scans executed: " + AggressiveScanHotReplacementSetup.getScanCount());

            // If we got here without exception, the race condition was NOT reproduced
            // (or Quarkus fix is working!)
            System.out.println("\n✅ TEST COMPLETED WITHOUT CRASH");
            System.out.println("This means either:");
            System.out.println("  1. Race condition timing was not hit (try running multiple times)");
            System.out.println("  2. Quarkus fix is working correctly!");

            // Tests should have run
            Assertions.assertTrue(ts.getTestsFailed() + ts.getTestsPassed() > 0,
                    "Tests should have executed");

        } catch (Exception e) {
            System.out.println("\n💥 APPLICATION CRASHED - RACE CONDITION REPRODUCED!");
            System.out.println("Exception: " + e.getClass().getName());
            System.out.println("Message: " + e.getMessage());
            System.out.println("Aggressive scans: " + AggressiveScanHotReplacementSetup.getScanCount());

            // Check if it's the DevConsoleManager race condition
            if (e.getMessage() != null && e.getMessage().contains("devui-continuous-testing")) {
                System.out.println("\n🎯 REPRODUCED: DevConsoleManager race condition");
                System.out.println("This is the bug that crashes Camel Quarkus CI!");
            }

            // Print stack trace for analysis
            e.printStackTrace();

            // Re-throw to fail the test
            throw e;
        } finally {
            // Disable aggressive scanning for next test
            AggressiveScanHotReplacementSetup.setEnabled(false);
        }
    }

    /**
     * Original precise race trigger test
     */
    @Test
    @Order(4)
    public void testPreciseRaceTrigger() throws Exception {
        // Keep timer disabled, but use manual triggers
        AggressiveScanHotReplacementSetup.setEnabled(false);
        AggressiveScanHotReplacementSetup.resetScanCount();

        ContinuousTestingTestUtils utils = new ContinuousTestingTestUtils();

        System.out.println("\n========================================");
        System.out.println("PRECISE RACE: Scans during wait");
        System.out.println("========================================");

        TestStatus ts = utils.waitForNextCompletion();
        long initialRun = ts.getLastRun();
        System.out.println("Initial run: " + ts);
        System.out.println("  lastRun=" + initialRun);

        // Modify the file
        System.out.println("\nModifying application.properties...");
        TEST.modifyResourceFile("application.properties",
                s -> s.replace("third", "fourth"));

        // Start a VERY aggressive thread that hammers scans during waitForNextCompletion()
        AtomicInteger triggeredScans = new AtomicInteger(0);
        CountDownLatch scannerStarted = new CountDownLatch(1);
        Thread scanThread = new Thread(() -> {
            scannerStarted.countDown();
            System.out.println("\nULTRA-AGGRESSIVE scanner starting (continuous rapid-fire scans)...");
            while (!Thread.currentThread().isInterrupted()) {
                AggressiveScanHotReplacementSetup.triggerScan();
                triggeredScans.incrementAndGet();
                try {
                    Thread.sleep(10); // Scan every 10ms!
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.println("Background scanner stopped after " + triggeredScans.get() + " scans.");
        });
        scanThread.setDaemon(true);
        scanThread.start();

        // Wait for scanner to start
        scannerStarted.await(1, TimeUnit.SECONDS);

        // Now wait for completion while scans are HAMMERING
        System.out.println("\nWaiting for completion while ULTRA-AGGRESSIVE scans are running...");
        try {
            ts = utils.waitForNextCompletion();
        } catch (Exception e) {
            System.out.println("\n!!! EXCEPTION during waitForNextCompletion !!!");
            System.out.println("Exception: " + e.getClass().getName() + ": " + e.getMessage());
            System.out.println("Scans triggered: " + triggeredScans.get());
            scanThread.interrupt();
            throw e;
        } finally {
            scanThread.interrupt();
        }

        long finalRun = ts.getLastRun();

        System.out.println("\nAfter modification: " + ts);
        System.out.println("  lastRun=" + finalRun);
        System.out.println("  Triggered " + triggeredScans.get() + " background scans");

        long restartCount = finalRun - initialRun;
        System.out.println("\n>>> PRECISE RACE RESULT: " + restartCount + " restart(s)");
        System.out.println(">>> Expected: 1 restart");

        if (restartCount > 1) {
            System.out.println("\n!!! RACE CONDITION REPRODUCED !!!");
            System.out.println("Scans during waitForNextCompletion caused spurious restarts!");
        }

        // Should complete despite concurrent scanning
        Assertions.assertTrue(ts.getTestsFailed() + ts.getTestsPassed() > 0,
                "Tests should complete despite concurrent scans");

        // Cleanup
        AggressiveScanHotReplacementSetup.setEnabled(false);
    }

    /**
     * Dedicated race condition reproducer - focuses purely on restart lifecycle.
     *
     * This test is designed to maximize the probability of reproducing the race condition
     * by enabling aggressive scanning with restart hooks and triggering rapid sequential restarts.
     *
     * Expected behavior WITHOUT Quarkus fix:
     * - High chance of NoSuchElementException crash
     *
     * Expected behavior WITH Quarkus fix:
     * - Test completes successfully (ContinuousTestingJsonRPCService handles missing actions gracefully)
     */
    @Test
    @Order(5)
    public void testDevConsoleManagerRaceCondition() throws Exception {
        System.out.println("\n========================================");
        System.out.println("DEDICATED RACE CONDITION REPRODUCER");
        System.out.println("========================================");

        // Enable aggressive scanning with restart hooks
        AggressiveScanHotReplacementSetup.setEnabled(true);
        AggressiveScanHotReplacementSetup.resetScanCount();

        ContinuousTestingTestUtils utils = new ContinuousTestingTestUtils();

        // CRITICAL: Wait for first completion to ensure lastState is populated!
        // ContinuousTestingSharedStateManager.addStateListener() calls immediate callback
        // ONLY if lastState != null. We need lastState to be populated before restart.
        TestStatus ts = utils.waitForNextCompletion();
        long initialRun = ts.getLastRun();
        System.out.println("Initial state: lastRun=" + initialRun);
        System.out.println(">>> lastState is now populated - immediate callback will trigger on restart!");

        System.out.println("\n>>> Starting ULTRA-EXTREME restart hammering...");
        System.out.println(">>> Strategy: Continuous background scanning + overlapping rapid restarts");

        // Start CONTINUOUS background scanning thread - runs throughout entire test
        // This ensures scans happen DURING BUILD phase when actions are being registered
        AtomicInteger backgroundScans = new AtomicInteger(0);
        Thread backgroundScanner = new Thread(() -> {
            System.out.println(">>> Background scanner STARTED - continuous 5ms interval scans");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    AggressiveScanHotReplacementSetup.triggerScan();
                    backgroundScans.incrementAndGet();
                    Thread.sleep(5); // Scan every 5ms to hit BUILD phase window!
                } catch (InterruptedException e) {
                    break;
                }
            }
            System.out.println(">>> Background scanner STOPPED - total scans: " + backgroundScans.get());
        }, "BackgroundScanner");
        backgroundScanner.setDaemon(true);
        backgroundScanner.start();

        // Give scanner a moment to start
        Thread.sleep(50);

        try {
            // Increase attempts and make restarts even more aggressive
            int attempts = 30; // Increased from 20 to 30
            System.out.println(">>> Running " + attempts + " attempts to trigger race condition...");
            System.out.println(">>> Background scanner running continuously at 5ms intervals");

            for (int attempt = 1; attempt <= attempts; attempt++) {
                System.out.println("\n=== ATTEMPT " + attempt + "/" + attempts + " (BG scans: "
                        + backgroundScans.get() + ") ===");

                // Trigger 5 OVERLAPPING restarts (increased from 3)
                // with NO delay - maximize chance of hitting BUILD phase
                for (int i = 1; i <= 5; i++) {
                    String from = (attempt == 1 && i == 1) ? "third" : "v" + ((attempt - 1) * 5 + i - 1);
                    String to = "v" + ((attempt - 1) * 5 + i);

                    System.out.println("  [Restart " + i + "/5] " + from + " → " + to);
                    TEST.modifyResourceFile("application.properties", s -> s.replace(from, to));

                    // NO DELAY - trigger next restart immediately!
                    // This creates maximum overlap and stress on BUILD phase
                }

                // Very brief pause between attempts to let system partially stabilize
                Thread.sleep(50); // Reduced from 100ms to 50ms
            }

            System.out.println("\n>>> All attempts completed. Waiting for final stabilization...");
            System.out.println(">>> Background scans executed: " + backgroundScans.get());
            Thread.sleep(1000);

            System.out.println(">>> Waiting for final test completion...");
            ts = utils.waitForNextCompletion();

            System.out.println("\n✅ SUCCESS - No crash detected!");
            System.out.println("Final lastRun: " + ts.getLastRun());
            System.out.println("Total restarts: " + (ts.getLastRun() - initialRun));
            System.out.println("Scans executed: " + AggressiveScanHotReplacementSetup.getScanCount());
            System.out.println("Background scans: " + backgroundScans.get());
            System.out.println("\nThis means:");
            System.out.println("  - Either race condition was not hit (low probability)");
            System.out.println("  - OR Quarkus fix is working correctly! ✅");

            Assertions.assertTrue(ts.getTestsFailed() + ts.getTestsPassed() > 0,
                    "Tests should have executed");

        } catch (Exception e) {
            System.out.println("\n💥 CRASH REPRODUCED!");
            System.out.println("Exception: " + e.getClass().getName());
            System.out.println("Message: " + e.getMessage());
            System.out.println("Background scans executed: " + backgroundScans.get());

            if (e.getMessage() != null && e.getMessage().contains("devui-continuous-testing")) {
                System.out.println("\n🎯 CONFIRMED: DevConsoleManager.invoke() race condition!");
                System.out.println("NoSuchElementException on 'devui-continuous-testing_getResults'");
                System.out.println("\nThis happens because:");
                System.out.println("1. DevConsoleManager.close() cleared actions");
                System.out.println("2. RUNTIME_INIT ran and registered listener");
                System.out.println("3. Listener tried to invoke action that doesn't exist yet");
                System.out.println("4. BUILD phase hasn't re-registered actions yet");
                System.out.println("5. Background scanner triggered additional restarts during BUILD");
                System.out.println("\nFIX: ContinuousTestingJsonRPCService.accept() should catch NoSuchElementException");
            }

            throw e;

        } finally {
            backgroundScanner.interrupt();
            try {
                backgroundScanner.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            AggressiveScanHotReplacementSetup.setEnabled(false);
        }
    }
}
