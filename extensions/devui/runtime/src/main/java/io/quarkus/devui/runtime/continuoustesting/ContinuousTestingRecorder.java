package io.quarkus.devui.runtime.continuoustesting;

import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.dev.testing.ContinuousTestingSharedStateManager;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class ContinuousTestingRecorder {

    public RuntimeValue<Boolean> createContinuousTestingSharedStateManager(BeanContainer beanContainer,
            ShutdownContext context) {
        ContinuousTestingJsonRPCService continuousTestingJsonRPCService = beanContainer
                .beanInstance(ContinuousTestingJsonRPCService.class);
        ContinuousTestingSharedStateManager.addStateListener(continuousTestingJsonRPCService);
        context.addShutdownTask(new Runnable() {
            @Override
            public void run() {
                ContinuousTestingSharedStateManager.removeStateListener(continuousTestingJsonRPCService);
                // Note: State reset is handled in DevConsoleManager.close() to ensure it happens
                // only during actual dev mode restarts between test classes, not during normal shutdowns.
            }
        });
        return new RuntimeValue<>(continuousTestingJsonRPCService != null);
    }

}