/**
 * Copyright 2017 Netflix, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 *
 */
package com.netflix.conductor.core.events;

import com.google.common.util.concurrent.Uninterruptibles;
import com.netflix.conductor.common.metadata.events.EventExecution;
import com.netflix.conductor.common.metadata.events.EventHandler;
import com.netflix.conductor.common.metadata.events.EventHandler.Action;
import com.netflix.conductor.common.metadata.events.EventHandler.Action.Type;
import com.netflix.conductor.common.metadata.events.EventHandler.StartWorkflow;
import com.netflix.conductor.common.metadata.events.EventHandler.TaskDetails;
import com.netflix.conductor.common.metadata.tasks.Task;
import com.netflix.conductor.common.metadata.workflow.WorkflowDef;
import com.netflix.conductor.common.run.Workflow;
import com.netflix.conductor.core.events.queue.Message;
import com.netflix.conductor.core.events.queue.ObservableQueue;
import com.netflix.conductor.core.execution.ApplicationException;
import com.netflix.conductor.core.execution.ParametersUtils;
import com.netflix.conductor.core.execution.TestConfiguration;
import com.netflix.conductor.core.execution.WorkflowExecutor;
import com.netflix.conductor.service.ExecutionService;
import com.netflix.conductor.service.MetadataService;
import org.junit.Before;
import org.junit.Test;
import org.mockito.stubbing.Answer;
import rx.Observable;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static junit.framework.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Viren
 */
public class TestEventProcessor {

    private String event;
    private String queueURI;

    private ObservableQueue queue;
    private MetadataService metadataService;
    private ExecutionService executionService;
    private WorkflowExecutor workflowExecutor;
    private ActionProcessor actionProcessor;
    private EventQueues eventQueues;
    private ParametersUtils parametersUtils;

    @Before
    public void setup() {
        event = "sqs:arn:account090:sqstest1";
        queueURI = "arn:account090:sqstest1";

        metadataService = mock(MetadataService.class);
        executionService = mock(ExecutionService.class);
        workflowExecutor = mock(WorkflowExecutor.class);
        actionProcessor = mock(ActionProcessor.class);
        parametersUtils = new ParametersUtils();

        EventQueueProvider provider = mock(EventQueueProvider.class);
        queue = mock(ObservableQueue.class);
        Message[] messages = new Message[1];
        messages[0] = new Message("t0", "{\"Type\":\"Notification\",\"MessageId\":\"7e4e6415-01e9-5caf-abaa-37fd05d446ff\",\"Message\":\"{\\n    \\\"testKey1\\\": \\\"level1\\\",\\n    \\\"metadata\\\": {\\n      \\\"testKey2\\\": 123456 }\\n  }\",\"Timestamp\":\"2018-08-10T21:22:05.029Z\",\"SignatureVersion\":\"1\"}", "t0");

        Observable<Message> msgObservable = Observable.from(messages);
        when(queue.observe()).thenReturn(msgObservable);
        when(queue.getURI()).thenReturn(queueURI);
        when(queue.getName()).thenReturn(queueURI);
        when(queue.getType()).thenReturn("sqs");
        when(provider.getQueue(queueURI)).thenReturn(queue);

        Map<String, EventQueueProvider> providers = new HashMap<>();
        providers.put("sqs", provider);
        eventQueues = new EventQueues(providers, parametersUtils);
    }

    @Test
    public void testEventProcessor() {
        // setup event handler
        EventHandler eventHandler = new EventHandler();
        eventHandler.setName(UUID.randomUUID().toString());
        eventHandler.setActive(true);

        Action startWorkflowAction = new Action();
        startWorkflowAction.setAction(Type.START_WORKFLOW);
        startWorkflowAction.setStartWorkflow(new StartWorkflow());
        startWorkflowAction.getStartWorkflow().setName("workflow_x");
        startWorkflowAction.getStartWorkflow().setVersion(1);
        eventHandler.getActions().add(startWorkflowAction);

        Action completeTaskAction = new Action();
        completeTaskAction.setAction(Type.COMPLETE_TASK);
        completeTaskAction.setCompleteTask(new TaskDetails());
        completeTaskAction.getCompleteTask().setTaskRefName("task_x");
        completeTaskAction.getCompleteTask().setWorkflowId(UUID.randomUUID().toString());
        completeTaskAction.getCompleteTask().setOutput(new HashMap<>());
        eventHandler.getActions().add(completeTaskAction);

        eventHandler.setEvent(event);

        when(metadataService.getEventHandlers()).thenReturn(Collections.singletonList(eventHandler));
        when(metadataService.getEventHandlersForEvent(event, true)).thenReturn(Collections.singletonList(eventHandler));
        when(executionService.addEventExecution(any())).thenReturn(true);
        when(queue.rePublishIfNoAck()).thenReturn(false);

        String id = UUID.randomUUID().toString();
        AtomicBoolean started = new AtomicBoolean(false);
        doAnswer((Answer<String>) invocation -> {
            started.set(true);
            return id;
        }).when(workflowExecutor).startWorkflow(startWorkflowAction.getStartWorkflow().getName(), startWorkflowAction.getStartWorkflow().getVersion(), startWorkflowAction.getStartWorkflow().getCorrelationId(), startWorkflowAction.getStartWorkflow().getInput(), event);

        AtomicBoolean completed = new AtomicBoolean(false);
        doAnswer((Answer<String>) invocation -> {
            completed.set(true);
            return null;
        }).when(workflowExecutor).updateTask(any());

        Task task = new Task();
        task.setReferenceTaskName(completeTaskAction.getCompleteTask().getTaskRefName());
        Workflow workflow = new Workflow();
        workflow.setTasks(Collections.singletonList(task));
        when(workflowExecutor.getWorkflow(completeTaskAction.getCompleteTask().getWorkflowId(), true)).thenReturn(workflow);

        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setVersion(startWorkflowAction.getStartWorkflow().getVersion());
        workflowDef.setName(startWorkflowAction.getStartWorkflow().getName());
        when(metadataService.getWorkflowDef(any(), any())).thenReturn(workflowDef);

        ActionProcessor actionProcessor = new ActionProcessor(workflowExecutor, parametersUtils);

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        assertNotNull(eventProcessor.getQueues());
        assertEquals(1, eventProcessor.getQueues().size());

        String queueEvent = eventProcessor.getQueues().keySet().iterator().next();
        assertEquals(eventHandler.getEvent(), queueEvent);

        String eventProcessorQueue = eventProcessor.getQueues().values().iterator().next();
        assertEquals(queueURI, eventProcessorQueue);

        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        assertTrue(started.get());
        assertTrue(completed.get());

        verify(queue, atMost(1)).ack(any());
        verify(queue, never()).publish(any());
    }

    @Test
    public void testEventHandlerWithCondition() {
        EventHandler eventHandler = new EventHandler();
        eventHandler.setName("cms_intermediate_video_ingest_handler");
        eventHandler.setActive(true);
        eventHandler.setEvent("sqs:dev_cms_asset_ingest_queue");
        eventHandler.setCondition("$.Message.testKey1 == 'level1' && $.Message.metadata.testKey2 == 123456");

        Map<String, Object> startWorkflowInput = new LinkedHashMap<>();
        startWorkflowInput.put("param1", "${Message.metadata.testKey2}");
        startWorkflowInput.put("param2", "SQS-${MessageId}");

        Action startWorkflowAction = new Action();
        startWorkflowAction.setAction(Type.START_WORKFLOW);
        startWorkflowAction.setStartWorkflow(new StartWorkflow());
        startWorkflowAction.getStartWorkflow().setName("cms_artwork_automation");
        startWorkflowAction.getStartWorkflow().setVersion(1);
        startWorkflowAction.getStartWorkflow().setInput(startWorkflowInput);
        startWorkflowAction.setExpandInlineJson(true);
        eventHandler.getActions().add(startWorkflowAction);

        eventHandler.setEvent(event);

        when(metadataService.getEventHandlers()).thenReturn(Collections.singletonList(eventHandler));
        when(metadataService.getEventHandlersForEvent(event, true)).thenReturn(Collections.singletonList(eventHandler));
        when(executionService.addEventExecution(any())).thenReturn(true);
        when(queue.rePublishIfNoAck()).thenReturn(false);

        String id = UUID.randomUUID().toString();
        AtomicBoolean started = new AtomicBoolean(false);
        doAnswer((Answer<String>) invocation -> {
            started.set(true);
            return id;
        }).when(workflowExecutor).startWorkflow(startWorkflowAction.getStartWorkflow().getName(), startWorkflowAction.getStartWorkflow().getVersion(), startWorkflowAction.getStartWorkflow().getCorrelationId(), startWorkflowAction.getStartWorkflow().getInput(), event);

        WorkflowDef workflowDef = new WorkflowDef();
        workflowDef.setName(startWorkflowAction.getStartWorkflow().getName());
        when(metadataService.getWorkflowDef(any(), any())).thenReturn(workflowDef);

        ActionProcessor actionProcessor = new ActionProcessor(workflowExecutor, parametersUtils);

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        assertNotNull(eventProcessor.getQueues());
        assertEquals(1, eventProcessor.getQueues().size());

        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);
        assertTrue(started.get());
    }

    @Test
    public void testEventProcessorWithRetriableError() {
        EventHandler eventHandler = new EventHandler();
        eventHandler.setName(UUID.randomUUID().toString());
        eventHandler.setActive(true);
        eventHandler.setEvent(event);

        Action completeTaskAction = new Action();
        completeTaskAction.setAction(Type.COMPLETE_TASK);
        completeTaskAction.setCompleteTask(new TaskDetails());
        completeTaskAction.getCompleteTask().setTaskRefName("task_x");
        completeTaskAction.getCompleteTask().setWorkflowId(UUID.randomUUID().toString());
        completeTaskAction.getCompleteTask().setOutput(new HashMap<>());
        eventHandler.getActions().add(completeTaskAction);

        when(queue.rePublishIfNoAck()).thenReturn(false);
        when(metadataService.getEventHandlers()).thenReturn(Collections.singletonList(eventHandler));
        when(metadataService.getEventHandlersForEvent(event, true)).thenReturn(Collections.singletonList(eventHandler));
        when(executionService.addEventExecution(any())).thenReturn(true);
        when(actionProcessor.execute(any(), any(), any(), any())).thenThrow(new ApplicationException(ApplicationException.Code.BACKEND_ERROR, "some retriable error"));

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        assertNotNull(eventProcessor.getQueues());
        assertEquals(1, eventProcessor.getQueues().size());

        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);

        verify(queue, never()).ack(any());
        verify(queue, never()).publish(any());
    }

    @Test
    public void testEventProcessorWithNonRetriableError() {
        EventHandler eventHandler = new EventHandler();
        eventHandler.setName(UUID.randomUUID().toString());
        eventHandler.setActive(true);
        eventHandler.setEvent(event);

        Action completeTaskAction = new Action();
        completeTaskAction.setAction(Type.COMPLETE_TASK);
        completeTaskAction.setCompleteTask(new TaskDetails());
        completeTaskAction.getCompleteTask().setTaskRefName("task_x");
        completeTaskAction.getCompleteTask().setWorkflowId(UUID.randomUUID().toString());
        completeTaskAction.getCompleteTask().setOutput(new HashMap<>());
        eventHandler.getActions().add(completeTaskAction);

        when(metadataService.getEventHandlers()).thenReturn(Collections.singletonList(eventHandler));
        when(metadataService.getEventHandlersForEvent(event, true)).thenReturn(Collections.singletonList(eventHandler));
        when(executionService.addEventExecution(any())).thenReturn(true);

        when(actionProcessor.execute(any(), any(), any(), any())).thenThrow(new ApplicationException(ApplicationException.Code.INVALID_INPUT, "some non-retriable error"));

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        assertNotNull(eventProcessor.getQueues());
        assertEquals(1, eventProcessor.getQueues().size());

        Uninterruptibles.sleepUninterruptibly(1, TimeUnit.SECONDS);

        verify(queue, atMost(1)).ack(any());
        verify(queue, never()).publish(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testExecuteInvalidAction() {
        AtomicInteger executeInvoked = new AtomicInteger(0);
        doAnswer((Answer<Map<String, Object>>) invocation -> {
            executeInvoked.incrementAndGet();
            throw new UnsupportedOperationException("error");
        }).when(actionProcessor).execute(any(), any(), any(), any());

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        EventExecution eventExecution = new EventExecution("id", "messageId");
        eventExecution.setStatus(EventExecution.Status.IN_PROGRESS);
        eventExecution.setEvent("event");
        Action action = new Action();

        eventProcessor.execute(eventExecution, action, "payload");
        assertEquals(1, executeInvoked.get());
        assertEquals(EventExecution.Status.FAILED, eventExecution.getStatus());
        assertNotNull(eventExecution.getOutput().get("exception"));
    }

    @Test
    public void testExecuteNonRetriableApplicationException() {
        AtomicInteger executeInvoked = new AtomicInteger(0);
        doAnswer((Answer<Map<String, Object>>) invocation -> {
            executeInvoked.incrementAndGet();
            throw new ApplicationException(ApplicationException.Code.INVALID_INPUT, "some non-retriable error");
        }).when(actionProcessor).execute(any(), any(), any(), any());

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        EventExecution eventExecution = new EventExecution("id", "messageId");
        eventExecution.setStatus(EventExecution.Status.IN_PROGRESS);
        eventExecution.setEvent("event");
        Action action = new Action();
        action.setAction(Type.START_WORKFLOW);

        eventProcessor.execute(eventExecution, action, "payload");
        assertEquals(1, executeInvoked.get());
        assertEquals(EventExecution.Status.FAILED, eventExecution.getStatus());
        assertNotNull(eventExecution.getOutput().get("exception"));
    }

    @Test
    public void testExecuteRetriableApplicationException() {
        AtomicInteger executeInvoked = new AtomicInteger(0);
        doAnswer((Answer<Map<String, Object>>) invocation -> {
            executeInvoked.incrementAndGet();
            throw new ApplicationException(ApplicationException.Code.BACKEND_ERROR, "some retriable error");
        }).when(actionProcessor).execute(any(), any(), any(), any());

        EventProcessor eventProcessor = new EventProcessor(executionService, metadataService, actionProcessor, eventQueues, new TestConfiguration());
        EventExecution eventExecution = new EventExecution("id", "messageId");
        eventExecution.setStatus(EventExecution.Status.IN_PROGRESS);
        eventExecution.setEvent("event");
        Action action = new Action();
        action.setAction(Type.START_WORKFLOW);

        eventProcessor.execute(eventExecution, action, "payload");
        assertEquals(3, executeInvoked.get());
        assertNull(eventExecution.getOutput().get("exception"));
    }
}

