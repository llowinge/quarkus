package io.quarkus.devui.runtime.continuoustesting;

import org.jboss.logging.Logger;

import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.dev.testing.ContinuousTestingSharedStateManager;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;

@Recorder
public class ContinuousTestingRecorder {
    private static final Logger LOG = Logger.getLogger(ContinuousTestingRecorder.class);

    public RuntimeValue<Boolean> createContinuousTestingSharedStateManager(BeanContainer beanContainer,
            ShutdownContext context) {
        LOG.info("[ContinuousTestingRecorder] CREATE_SHARED_STATE_MANAGER START [" + Thread.currentThread().getName()
                + "] - RUNTIME_INIT phase");
        ContinuousTestingJsonRPCService continuousTestingJsonRPCService = beanContainer
                .beanInstance(ContinuousTestingJsonRPCService.class);
        LOG.info("[ContinuousTestingRecorder] ABOUT TO ADD STATE LISTENER [" + Thread.currentThread().getName()
                + "] service=" + continuousTestingJsonRPCService);
        ContinuousTestingSharedStateManager.addStateListener(continuousTestingJsonRPCService);
        LOG.info("[ContinuousTestingRecorder] STATE LISTENER ADDED [" + Thread.currentThread().getName() + "]");
        context.addShutdownTask(new Runnable() {
            @Override
            public void run() {
                ContinuousTestingSharedStateManager.removeStateListener(continuousTestingJsonRPCService);
            }
        });
        LOG.info(
                "[ContinuousTestingRecorder] CREATE_SHARED_STATE_MANAGER COMPLETE [" + Thread.currentThread().getName() + "]");
        return new RuntimeValue<>(continuousTestingJsonRPCService != null);
    }

}