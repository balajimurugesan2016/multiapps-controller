package org.cloudfoundry.multiapps.controller.process.util;

import java.sql.Timestamp;
import java.text.MessageFormat;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.cloudfoundry.multiapps.common.ContentException;
import org.cloudfoundry.multiapps.controller.core.model.HistoricOperationEvent.EventType;
import org.cloudfoundry.multiapps.controller.core.model.ImmutableHistoricOperationEvent;
import org.cloudfoundry.multiapps.controller.core.persistence.service.HistoricOperationEventService;
import org.cloudfoundry.multiapps.controller.core.persistence.service.ProgressMessageService;
import org.cloudfoundry.multiapps.controller.persistence.model.ImmutableProgressMessage;
import org.cloudfoundry.multiapps.controller.persistence.model.ProgressMessage;
import org.cloudfoundry.multiapps.controller.persistence.model.ProgressMessage.ProgressMessageType;
import org.cloudfoundry.multiapps.controller.process.Messages;
import org.cloudfoundry.multiapps.controller.process.flowable.FlowableFacade;
import org.flowable.common.engine.api.delegate.event.FlowableEngineEvent;
import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.impl.context.Context;
import org.flowable.engine.runtime.Execution;

@Named
public class OperationInErrorStateHandler {

    private ProgressMessageService progressMessageService;
    private FlowableFacade flowableFacade;
    private HistoricOperationEventService historicOperationEventService;
    private ClientReleaser clientReleaser;

    @Inject
    public OperationInErrorStateHandler(ProgressMessageService progressMessageService, FlowableFacade flowableFacade,
                                        HistoricOperationEventService historicOperationEventService, ClientReleaser clientReleaser) {
        this.progressMessageService = progressMessageService;
        this.flowableFacade = flowableFacade;
        this.historicOperationEventService = historicOperationEventService;
        this.clientReleaser = clientReleaser;
    }

    public void handle(FlowableEngineEvent event, String errorMessage) {
        handle(event, EventType.FAILED_BY_INFRASTRUCTURE_ERROR, errorMessage);
    }

    public void handle(FlowableEngineEvent event, Throwable throwable) {
        EventType eventType = toEventType(throwable);
        handle(event, eventType, throwable.getMessage());
    }

    private void handle(FlowableEngineEvent event, EventType eventType, String errorMessage) {
        persistEvent(event, eventType);
        persistError(event, errorMessage);
        releaseCloudControllerClient(event);
    }

    EventType toEventType(Throwable throwable) {
        return hasCause(throwable, ContentException.class) ? EventType.FAILED_BY_CONTENT_ERROR : EventType.FAILED_BY_INFRASTRUCTURE_ERROR;
    }

    private <T extends Throwable> boolean hasCause(Throwable throwable, Class<T> clazz) {
        return ExceptionUtils.getThrowableList(throwable)
                             .stream()
                             .anyMatch(clazz::isInstance);
    }

    private void persistEvent(FlowableEngineEvent event, EventType eventType) {
        historicOperationEventService.add(ImmutableHistoricOperationEvent.of(flowableFacade.getProcessInstanceId(event.getExecutionId()),
                                                                             eventType));
    }

    private void persistError(FlowableEngineEvent event, String flowableExceptionMessage) {
        String processInstanceId = flowableFacade.getProcessInstanceId(event.getExecutionId());
        if (isErrorProgressMessagePersisted(processInstanceId)) {
            return;
        }

        String taskId = getCurrentTaskId(event);
        String errorMessage = MessageFormat.format(Messages.UNEXPECTED_ERROR, flowableExceptionMessage);

        progressMessageService.add(ImmutableProgressMessage.builder()
                                                           .processId(processInstanceId)
                                                           .taskId(taskId)
                                                           .type(ProgressMessageType.ERROR)
                                                           .text(errorMessage)
                                                           .timestamp(getCurrentTimestamp())
                                                           .build());
    }

    private boolean isErrorProgressMessagePersisted(String processInstanceId) {
        List<ProgressMessage> progressMessages = progressMessageService.createQuery()
                                                                       .processId(processInstanceId)
                                                                       .list();
        return progressMessages.stream()
                               .anyMatch(this::isErrorMessage);
    }

    private boolean isErrorMessage(ProgressMessage message) {
        return message.getType() == ProgressMessageType.ERROR;
    }

    private String getCurrentTaskId(FlowableEngineEvent flowableEngineEvent) {
        Execution currentExecutionForProcess = findCurrentExecution(flowableEngineEvent);

        return currentExecutionForProcess != null ? currentExecutionForProcess.getActivityId()
            : flowableFacade.getCurrentTaskId(flowableEngineEvent.getExecutionId());
    }

    private Execution findCurrentExecution(FlowableEngineEvent flowableEngineEvent) {
        try {
            // This is needed because when there are parallel CallActivity, the query will return multiple results for just one Execution
            List<Execution> currentExecutionsForProcess = getProcessEngineConfiguration().getRuntimeService()
                                                                                         .createExecutionQuery()
                                                                                         .executionId(flowableEngineEvent.getExecutionId())
                                                                                         .processInstanceId(flowableEngineEvent.getProcessInstanceId())
                                                                                         .list();

            // Based on the above comment, one of the executions will have null activityId(because it will be the monitoring one) and thus
            // should be excluded from the list of executions
            return ObjectUtils.isEmpty(currentExecutionsForProcess) ? null : findCurrentExecution(currentExecutionsForProcess);
        } catch (Exception e) {
            return null;
        }
    }

    protected ProcessEngineConfiguration getProcessEngineConfiguration() {
        return Context.getProcessEngineConfiguration();
    }

    private Execution findCurrentExecution(List<Execution> currentExecutionsForProcess) {
        return currentExecutionsForProcess.stream()
                                          .filter(execution -> execution.getActivityId() != null)
                                          .findFirst()
                                          .orElse(null);
    }

    protected Date getCurrentTimestamp() {
        return new Timestamp(System.currentTimeMillis());
    }

    private void releaseCloudControllerClient(FlowableEngineEvent event) {
        HistoryService historyService = getProcessEngineConfiguration().getHistoryService();
        clientReleaser.releaseClientFor(historyService, event.getProcessInstanceId());
    }

}
