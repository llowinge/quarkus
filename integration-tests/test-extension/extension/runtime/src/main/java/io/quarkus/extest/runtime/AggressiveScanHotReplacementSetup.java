package io.quarkus.extest.runtime;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.logging.Logger;

import io.quarkus.dev.spi.HotReplacementContext;
import io.quarkus.dev.spi.HotReplacementSetup;

/**
 * Aggressive periodic scanning to reproduce race conditions in continuous testing.
 * This implementation intentionally ignores restart lifecycle to trigger the bug.
 */
public class AggressiveScanHotReplacementSetup implements HotReplacementSetup {
    private static final Logger LOG = Logger.getLogger(AggressiveScanHotReplacementSetup.class);

    // EXTREMELY aggressive scanning - every 50ms to maximize race conditions
    private static final long SCAN_INTERVAL_MS = 50;

    private static volatile HotReplacementContext context;
    private static volatile Timer timer;
    private static final AtomicInteger scanCount = new AtomicInteger(0);

    // System property to control scanning (works across classloaders)
    private static final String ENABLED_PROPERTY = "aggressive.scan.enabled";

    public static void setEnabled(boolean enabled) {
        System.setProperty(ENABLED_PROPERTY, String.valueOf(enabled));
        LOG.infof("Aggressive scanning %s via system property", enabled ? "ENABLED" : "DISABLED");
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
    }

    @Override
    public void setupHotDeployment(HotReplacementContext ctx) {
        context = ctx;

        LOG.infof("AggressiveScanHotReplacementSetup.setupHotDeployment called, enabled=%s", isEnabled());

        // Hook into restart lifecycle to trigger scans at CRITICAL moments
        // This reproduces the race condition where doScan() is called during restart
        // when DevConsoleManager actions are cleared but not yet re-registered
        ctx.addPreRestartStep(() -> {
            if (isEnabled()) {
                LOG.info("!!! PRE-RESTART HOOK - triggering scan to create race condition !!!");
                // Trigger scan RIGHT BEFORE restart completes
                // This maximizes chance of hitting the race condition
                try {
                    context.doScan(false);
                } catch (Exception e) {
                    LOG.infof("Pre-restart scan failed: %s", e.getMessage());
                }
            }
        });

        ctx.addPostRestartStep(() -> {
            if (isEnabled()) {
                LOG.info("!!! POST-RESTART HOOK - triggering scan immediately after restart !!!");
                // Trigger scan RIGHT AFTER restart completes
                // At this point RUNTIME_INIT has run but build-time might not be complete
                try {
                    context.doScan(false);
                } catch (Exception e) {
                    LOG.infof("Post-restart scan failed: %s", e.getMessage());
                }
            }
        });

        // Always create the timer, but only scan when enabled
        // This allows tests to enable/disable scanning dynamically
        LOG.infof("Creating aggressive scan timer (will scan every %dms when enabled)", SCAN_INTERVAL_MS);

        timer = new Timer("aggressive-scan", true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (!isEnabled()) {
                    // Silently skip when disabled (would be too noisy to log every 50ms)
                    return;
                }
                try {
                    int count = scanCount.incrementAndGet();
                    LOG.infof("Aggressive scan #%d - calling doScan()", count);
                    // Intentionally don't check restart state - we want to cause problems!
                    boolean restartTriggered = context.doScan(false);
                    if (restartTriggered) {
                        LOG.infof("!!! Aggressive scan #%d TRIGGERED A RESTART !!!", count);
                    }
                } catch (Exception e) {
                    LOG.infof("Aggressive scan #%d failed: %s", scanCount.get(), e.getMessage());
                }
            }
        }, SCAN_INTERVAL_MS, SCAN_INTERVAL_MS);
    }

    @Override
    public void close() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        context = null;
    }

    /**
     * Trigger a manual scan - useful for testing at specific moments
     */
    public static void triggerScan() {
        if (context != null) {
            try {
                LOG.debug("Manual scan triggered");
                context.doScan(false);
            } catch (Exception e) {
                LOG.debugf("Manual scan failed: %s", e.getMessage());
            }
        }
    }

    public static int getScanCount() {
        return scanCount.get();
    }

    public static void resetScanCount() {
        scanCount.set(0);
    }
}
