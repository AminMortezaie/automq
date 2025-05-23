/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.autobalancer;

import kafka.autobalancer.common.Action;
import kafka.autobalancer.common.ActionType;
import kafka.autobalancer.executor.ControllerActionExecutorService;
import kafka.test.MockController;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.message.AlterPartitionReassignmentsRequestData;
import org.apache.kafka.common.message.AlterPartitionReassignmentsResponseData;
import org.apache.kafka.controller.Controller;
import org.apache.kafka.controller.ControllerRequestContext;
import org.apache.kafka.test.TestUtils;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Timeout(60)
@Tag("S3Unit")
public class ControllerActionExecutorServiceTest {

    private boolean checkTopicPartition(AlterPartitionReassignmentsRequestData.ReassignableTopic topic,
                                        String name, int partitionIndex, int partitionId, int nodeId) {
        if (!topic.name().equals(name)) {
            return false;
        }
        if (topic.partitions().size() <= partitionIndex) {
            return false;
        }
        AlterPartitionReassignmentsRequestData.ReassignablePartition partition = topic.partitions().get(partitionIndex);
        if (partition.partitionIndex() != partitionId) {
            return false;
        }
        return partition.replicas().size() == 1 && partition.replicas().get(0) == nodeId;
    }

    @Test
    public void testExecuteActions() throws Exception {
        Controller controller = Mockito.mock(MockController.class);

        final ArgumentCaptor<ControllerRequestContext> ctxCaptor = ArgumentCaptor.forClass(ControllerRequestContext.class);
        final ArgumentCaptor<AlterPartitionReassignmentsRequestData> reqCaptor = ArgumentCaptor.forClass(AlterPartitionReassignmentsRequestData.class);

        Mockito.doAnswer(answer -> CompletableFuture.completedFuture(new AlterPartitionReassignmentsResponseData()))
                .when(controller).alterPartitionReassignments(ctxCaptor.capture(), reqCaptor.capture());

        ControllerActionExecutorService controllerActionExecutorService = new ControllerActionExecutorService(controller);
        controllerActionExecutorService.start();

        List<Action> actionList = List.of(
                new Action(ActionType.MOVE, new TopicPartition("topic1", 0), 0, 1),
                new Action(ActionType.SWAP, new TopicPartition("topic2", 0), 0, 1, new TopicPartition("topic1", 1)));
        controllerActionExecutorService.execute(actionList);

        TestUtils.waitForCondition(() -> {
            List<AlterPartitionReassignmentsRequestData> reqs = reqCaptor.getAllValues();
            if (reqs.size() != 1) {
                return false;
            }
            AlterPartitionReassignmentsRequestData reqMove = reqs.get(0);
            if (reqMove.topics().size() != 2) {
                return false;
            }
            return checkTopicPartition(reqMove.topics().get(0), "topic1", 0, 0, 1) &&
                    checkTopicPartition(reqMove.topics().get(0), "topic1", 1, 1, 0) &&
                    checkTopicPartition(reqMove.topics().get(1), "topic2", 0, 0, 1);
        }, 5000L, 1000L, () -> "failed to meet reassign");

        controllerActionExecutorService.shutdown();
    }
}
