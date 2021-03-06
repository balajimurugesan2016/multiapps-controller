package org.cloudfoundry.multiapps.controller.process.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.cloudfoundry.client.lib.domain.CloudEvent;
import org.cloudfoundry.client.lib.domain.ImmutableCloudMetadata;
import org.cloudfoundry.multiapps.controller.client.lib.domain.CloudServiceInstanceExtended;
import org.cloudfoundry.multiapps.controller.core.cf.clients.EventsGetter;
import org.cloudfoundry.multiapps.controller.core.cf.clients.ServiceGetter;
import org.cloudfoundry.multiapps.controller.core.model.ServiceOperation;
import org.cloudfoundry.multiapps.controller.process.steps.ProcessContext;
import org.flowable.engine.delegate.DelegateExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class ServiceOperationGetterTest {

    @Mock
    private ServiceGetter serviceGetter;
    @Mock
    private EventsGetter eventsGetter;
    @Mock
    private ProcessContext context;
    @Mock
    private CloudServiceInstanceExtended service;

    private ServiceOperationGetter serviceOperationGetter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.initMocks(this);
        serviceOperationGetter = new ServiceOperationGetter(serviceGetter, eventsGetter);
    }

    static Stream<Arguments> testGetLastServiceOperation() {
        return Stream.of(
                         // (1) Test with create succeeded operation
                         Arguments.of(ServiceOperation.Type.CREATE, ServiceOperation.State.SUCCEEDED, "created", false, false,
                                      new ServiceOperation(ServiceOperation.Type.CREATE, "created", ServiceOperation.State.SUCCEEDED)),
                         // (2) Test with delete service in progress operation
                         Arguments.of(ServiceOperation.Type.DELETE, ServiceOperation.State.IN_PROGRESS, null, false, false,
                                      new ServiceOperation(ServiceOperation.Type.DELETE, null, ServiceOperation.State.IN_PROGRESS)),
                         // (3) Test with missing service entity and missing service metadata
                         Arguments.of(null, null, null, true, true, null),
                         // (4) Test with missing service entity and delete event
                         Arguments.of(null, null, null, true, false,
                                      new ServiceOperation(ServiceOperation.Type.DELETE,
                                                           ServiceOperation.Type.DELETE.name(),
                                                           ServiceOperation.State.SUCCEEDED)),
                         // (5) Test with missing service entity and missing event
                         Arguments.of(null, null, null, false, false,
                                      new ServiceOperation(ServiceOperation.Type.DELETE,
                                                           ServiceOperation.Type.DELETE.name(),
                                                           ServiceOperation.State.IN_PROGRESS)));
    }

    @ParameterizedTest
    @MethodSource
    void testGetLastServiceOperation(ServiceOperation.Type serviceOperationType, ServiceOperation.State serviceOperationState,
                                     String description, boolean isDeletedService, boolean isMissingServiceMetadata,
                                     ServiceOperation expectedServiceOperation) {
        Map<String, Object> serviceInstanceEntity = generateServiceInstanceEntity(serviceOperationType, serviceOperationState, description);
        prepareServiceGetter(serviceInstanceEntity);
        prepareEventsGetter(isDeletedService);
        prepareService(isMissingServiceMetadata);
        prepareExecution();

        ServiceOperation serviceOperation = serviceOperationGetter.getLastServiceOperation(context, service);

        assertServiceOperation(expectedServiceOperation, serviceOperation);
    }

    private Map<String, Object> generateServiceInstanceEntity(ServiceOperation.Type serviceOperationType,
                                                              ServiceOperation.State serviceOperationState, String description) {
        if (serviceOperationType != null && serviceOperationState != null) {
            Map<String, Object> serviceOperationAsMap = new HashMap<>();
            serviceOperationAsMap.put(ServiceOperation.SERVICE_OPERATION_TYPE, serviceOperationType.toString());
            serviceOperationAsMap.put(ServiceOperation.SERVICE_OPERATION_STATE, serviceOperationState.toString());
            serviceOperationAsMap.put(ServiceOperation.SERVICE_OPERATION_DESCRIPTION, description);
            return Map.of(ServiceOperation.LAST_SERVICE_OPERATION, serviceOperationAsMap);
        }
        return null;
    }

    private void prepareServiceGetter(Map<String, Object> serviceInstanceEntity) {
        when(serviceGetter.getServiceInstanceEntity(any(), any(), any())).thenReturn(serviceInstanceEntity);
    }

    private void prepareEventsGetter(boolean wasDeletedService) {
        when(eventsGetter.getEvents(any(), any())).thenReturn(Collections.singletonList(mock(CloudEvent.class)));
        when(eventsGetter.isDeleteEvent(any())).thenReturn(wasDeletedService);
    }

    private void prepareService(boolean isMissingServiceMetadata) {
        if (!isMissingServiceMetadata) {
            when(service.getMetadata()).thenReturn(ImmutableCloudMetadata.builder()
                                                                         .guid(UUID.randomUUID())
                                                                         .build());
        }
    }

    private void prepareExecution() {
        when(context.getExecution()).thenReturn(mock(DelegateExecution.class));
    }

    private void assertServiceOperation(ServiceOperation expectedServiceOperation, ServiceOperation serviceOperation) {
        if (expectedServiceOperation != null) {
            assertEquals(expectedServiceOperation.getState(), serviceOperation.getState());
            assertEquals(expectedServiceOperation.getType(), serviceOperation.getType());
            assertEquals(expectedServiceOperation.getDescription(), serviceOperation.getDescription());
        } else {
            assertNull(serviceOperation);
        }
    }

}
