package io.quarkus.devui.runtime.continuoustesting;

import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;

import org.jboss.logging.Logger;

import io.quarkus.dev.console.DevConsoleManager;
import io.quarkus.dev.testing.ContinuousTestingSharedStateManager;
import io.quarkus.dev.testing.results.TestResultInterface;
import io.quarkus.dev.testing.results.TestRunResultsInterface;
import io.quarkus.devui.runtime.continuoustesting.ContinuousTestingJsonRPCState.Config;
import io.quarkus.devui.runtime.continuoustesting.ContinuousTestingJsonRPCState.Result;
import io.quarkus.devui.runtime.continuoustesting.ContinuousTestingJsonRPCState.Result.Counts;
import io.quarkus.devui.runtime.continuoustesting.ContinuousTestingJsonRPCState.Result.Item;
import io.smallrye.common.annotation.NonBlocking;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;

@ApplicationScoped
public class ContinuousTestingJsonRPCService implements Consumer<ContinuousTestingSharedStateManager.State> {

    private static final Logger LOG = Logger.getLogger(ContinuousTestingJsonRPCService.class);

    private final BroadcastProcessor<ContinuousTestingJsonRPCState> stateBroadcaster = BroadcastProcessor.create();

    private ContinuousTestingJsonRPCState currentState = new ContinuousTestingJsonRPCState();

    @Override
    public void accept(final ContinuousTestingSharedStateManager.State state) {
        // During restart, build-time actions may be temporarily unavailable due to the lifecycle:
        // 1. DevConsoleManager.close() clears all registered actions
        // 2. RUNTIME_INIT phase runs (this listener may be invoked)
        // 3. BUILD phase completes and re-registers actions
        // This is a race condition that can occur when restarts are triggered from background threads
        // (e.g., periodic file scanning). The listener will be invoked again when state changes.
        TestRunResultsInterface results = null;
        try {
            results = DevConsoleManager.<TestRunResultsInterface> invoke("devui-continuous-testing_getResults");
        } catch (NoSuchElementException e) {
            // Expected during restart - build-time infrastructure not ready yet
            LOG.trace("Build-time action 'devui-continuous-testing_getResults' not available yet, skipping state update");
            return;
        }

        final List<Item> passedTests = new LinkedList<>();
        final List<Item> failedTests = new LinkedList<>();
        final List<Item> skippedTests = new LinkedList<>();
        final Set<String> tags = new TreeSet<>();
        if (null != results) {
            results
                    .getResults()
                    .values()
                    .stream()
                    .flatMap(result -> result.getResults().stream())
                    .filter(TestResultInterface::isTest)
                    .sorted(
                            Comparator
                                    .comparing(TestResultInterface::getTestClass)
                                    .thenComparing(TestResultInterface::getDisplayName)
                                    .thenComparing(TestResultInterface::getDisplayName))
                    .forEach(
                            result -> {
                                (switch (result.getState()) {
                                    case PASSED -> passedTests;
                                    case FAILED -> failedTests;
                                    case SKIPPED -> skippedTests;
                                })
                                        .add(
                                                new Item()
                                                        .setClassName(result.getTestClass())
                                                        .setDisplayName(result.getDisplayName())
                                                        .setProblems(result.getProblems().toArray(new Throwable[0]))
                                                        .setTime(result.getTime())
                                                        .setTags(result.getTags().toArray(new String[0])));
                                tags.addAll(result.getTags());
                            });
        }
        this.currentState = new ContinuousTestingJsonRPCState()
                .setInProgress(state.inProgress)
                .setConfig(
                        new Config()
                                .setEnabled(state.running)
                                .setBrokenOnly(state.isBrokenOnly))
                .setResult(
                        new Result()
                                .setCounts(
                                        new Counts()
                                                .setPassed(state.passed)
                                                .setFailed(state.failed)
                                                .setSkipped(state.skipped))
                                .setTags(tags.toArray(new String[0]))
                                .setTotalTime(results == null ? 0L : results.getTotalTime())
                                .setPassed(passedTests.toArray(new Item[0]))
                                .setFailed(failedTests.toArray(new Item[0]))
                                .setSkipped(skippedTests.toArray(new Item[0])));
        this.stateBroadcaster.onNext(this.currentState);
    }

    public Multi<ContinuousTestingJsonRPCState> streamState() {
        return stateBroadcaster;
    }

    @NonBlocking
    public ContinuousTestingJsonRPCState currentState() {
        return this.currentState;
    }

}
