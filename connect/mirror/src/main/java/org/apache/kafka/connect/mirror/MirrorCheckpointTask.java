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
package org.apache.kafka.connect.mirror;

import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.connect.source.SourceTask;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.RecordMetadata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutionException;
import java.time.Duration;

/** Emits checkpoints for upstream consumer groups. */
public class MirrorCheckpointTask extends SourceTask {

    private static final Logger log = LoggerFactory.getLogger(MirrorCheckpointTask.class);

    private Admin sourceAdminClient;
    private Admin targetAdminClient;
    private String sourceClusterAlias;
    private String targetClusterAlias;
    private String checkpointsTopic;
    private Duration interval;
    private Duration pollTimeout;
    private TopicFilter topicFilter;
    private Set<String> consumerGroups;
    private ReplicationPolicy replicationPolicy;
    private OffsetSyncStore offsetSyncStore;
    private boolean stopping;
    private MirrorMetrics metrics;
    private Scheduler scheduler;
    private Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset;
    private Map<String, List<Checkpoint>> checkpointsPerConsumerGroup;
    private KafkaConsumer<byte[], byte[]> sourceConsumer;
    private KafkaConsumer<byte[], byte[]> targetConsumer;
    public MirrorCheckpointTask() {}

    // for testing
    MirrorCheckpointTask(String sourceClusterAlias, String targetClusterAlias,
            ReplicationPolicy replicationPolicy, OffsetSyncStore offsetSyncStore,
            Map<String, Map<TopicPartition, OffsetAndMetadata>> idleConsumerGroupsOffset,
            Map<String, List<Checkpoint>> checkpointsPerConsumerGroup) {
        this.sourceClusterAlias = sourceClusterAlias;
        this.targetClusterAlias = targetClusterAlias;
        this.replicationPolicy = replicationPolicy;
        this.offsetSyncStore = offsetSyncStore;
        this.idleConsumerGroupsOffset = idleConsumerGroupsOffset;
        this.checkpointsPerConsumerGroup = checkpointsPerConsumerGroup;
    }

    @Override
    public void start(Map<String, String> props) {
        MirrorTaskConfig config = new MirrorTaskConfig(props);
        stopping = false;
        sourceClusterAlias = config.sourceClusterAlias();
        targetClusterAlias = config.targetClusterAlias();
        consumerGroups = config.taskConsumerGroups();
        checkpointsTopic = config.checkpointsTopic();
        topicFilter = config.topicFilter();
        replicationPolicy = config.replicationPolicy();
        interval = config.emitCheckpointsInterval();
        pollTimeout = config.consumerPollTimeout();
        offsetSyncStore = new OffsetSyncStore(config);
        sourceAdminClient = AdminClient.create(config.sourceAdminConfig());
        targetAdminClient = AdminClient.create(config.targetAdminConfig());
        sourceConsumer = MirrorUtils.newConsumer(config.sourceConsumerConfig());
        targetConsumer = MirrorUtils.newConsumer(config.targetConsumerConfig());
        metrics = config.metrics();
        idleConsumerGroupsOffset = new HashMap<>();
        checkpointsPerConsumerGroup = new HashMap<>();
        scheduler = new Scheduler(MirrorCheckpointTask.class, config.adminTimeout());
        scheduler.scheduleRepeating(this::refreshIdleConsumerGroupOffset, config.syncGroupOffsetsInterval(),
                                    "refreshing idle consumers group offsets at target cluster");
        scheduler.scheduleRepeatingDelayed(this::syncGroupOffset, config.syncGroupOffsetsInterval(),
                                          "sync idle consumer group offset from source to target");
        scheduler.scheduleRepeatingDelayed(this::sendConsumerGroupsMetrics, config.sendConsumerGroupsMetricsInterval(),
                "Send metrics about consumer group replications");
    }

    @Override
    public void commit() throws InterruptedException {
        // nop
    }

    @Override
    public void stop() {
        long start = System.currentTimeMillis();
        stopping = true;
        Utils.closeQuietly(offsetSyncStore, "offset sync store");
        Utils.closeQuietly(sourceAdminClient, "source admin client");
        Utils.closeQuietly(targetAdminClient, "target admin client");
        Utils.closeQuietly(metrics, "metrics");
        Utils.closeQuietly(scheduler, "scheduler");
        log.info("Stopping {} took {} ms.", Thread.currentThread().getName(), System.currentTimeMillis() - start);
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public List<SourceRecord> poll() throws InterruptedException {
        try {
            long deadline = System.currentTimeMillis() + interval.toMillis();
            while (!stopping && System.currentTimeMillis() < deadline) {
                offsetSyncStore.update(pollTimeout);
            }
            List<SourceRecord> records = new ArrayList<>();
            for (String group : consumerGroups) {
                records.addAll(sourceRecordsForGroup(group));
            }
            if (records.isEmpty()) {
                // WorkerSourceTask expects non-zero batches or null
                return null;
            } else {
                return records;
            }
        } catch (Throwable e) {
            log.warn("Failure polling consumer state for checkpoints.", e);
            return null;
        }
    }


    private List<SourceRecord> sourceRecordsForGroup(String group) throws InterruptedException {
        try {
            long timestamp = System.currentTimeMillis();
            List<Checkpoint> checkpoints = checkpointsForGroup(group);
            checkpointsPerConsumerGroup.put(group, checkpoints);
            return checkpoints.stream()
                .map(x -> checkpointRecord(x, timestamp))
                .collect(Collectors.toList());
        } catch (ExecutionException e) {
            log.error("Error querying offsets for consumer group {} on cluster {}.",  group, sourceClusterAlias, e);
            return Collections.emptyList();
        }
    }

    private List<Checkpoint> checkpointsForGroup(String group) throws ExecutionException, InterruptedException {
        return listConsumerGroupOffsets(group).entrySet().stream()
            .filter(x -> shouldCheckpointTopic(x.getKey().topic()))
            .map(x -> checkpoint(group, x.getKey(), x.getValue()))
            .filter(x -> x != null)
            .filter(x -> x.downstreamOffset() >= 0)  // ignore offsets we cannot translate accurately
            .collect(Collectors.toList());
    }

    private Map<TopicPartition, OffsetAndMetadata> listConsumerGroupOffsets(String group)
            throws InterruptedException, ExecutionException {
        if (stopping) {
            // short circuit if stopping
            return Collections.emptyMap();
        }
        return sourceAdminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
    }

    Checkpoint checkpoint(String group, TopicPartition topicPartition,
            OffsetAndMetadata offsetAndMetadata) {
        try {
            long upstreamOffset = offsetAndMetadata.offset();
            long downstreamOffset = offsetSyncStore.translateDownstream(topicPartition, upstreamOffset);
            Checkpoint tmpCheckpoint = new Checkpoint(group, renameTopicPartition(topicPartition),
                upstreamOffset, downstreamOffset, offsetAndMetadata.metadata());
            log.trace("Creating checkpoint group({}) topicPartition({}) upstreamOffset({}) downstreamOffset({})", group, topicPartition, upstreamOffset, downstreamOffset);
            return tmpCheckpoint;
        } catch (NullPointerException e) {
            log.error("Error creating checkpoint group({}) topicPartition({}) offsetAndMetadata({}) {}", group, topicPartition, offsetAndMetadata, e);
            return null;
        }
    }

    SourceRecord checkpointRecord(Checkpoint checkpoint, long timestamp) {
        return new SourceRecord(
            checkpoint.connectPartition(), MirrorUtils.wrapOffset(0),
            checkpointsTopic, 0,
            Schema.BYTES_SCHEMA, checkpoint.recordKey(),
            Schema.BYTES_SCHEMA, checkpoint.recordValue(),
            timestamp);
    }

    TopicPartition renameTopicPartition(TopicPartition upstreamTopicPartition) {
        if (targetClusterAlias.equals(replicationPolicy.topicSource(upstreamTopicPartition.topic()))) {
            // this topic came from the target cluster, so we rename like us-west.topic1 -> topic1
            return new TopicPartition(replicationPolicy.originalTopic(upstreamTopicPartition.topic()),
                upstreamTopicPartition.partition());
        } else {
            // rename like topic1 -> us-west.topic1
            return new TopicPartition(replicationPolicy.formatRemoteTopic(sourceClusterAlias,
                upstreamTopicPartition.topic()), upstreamTopicPartition.partition());
        }
    }

    boolean shouldCheckpointTopic(String topic) {
        return topicFilter.shouldReplicateTopic(topic);
    }

    @Override
    public void commitRecord(SourceRecord record, RecordMetadata metadata) {
        metrics.checkpointLatency(MirrorUtils.unwrapPartition(record.sourcePartition()),
            Checkpoint.unwrapGroup(record.sourcePartition()),
            System.currentTimeMillis() - record.timestamp());
    }

    private void refreshIdleConsumerGroupOffset() {
        Map<String, KafkaFuture<ConsumerGroupDescription>> consumerGroupsDesc = targetAdminClient
            .describeConsumerGroups(consumerGroups).describedGroups();

        for (String group : consumerGroups) {
            try {
                ConsumerGroupDescription consumerGroupDesc = consumerGroupsDesc.get(group).get();
                ConsumerGroupState consumerGroupState = consumerGroupDesc.state();
                // sync offset to the target cluster only if the state of current consumer group is:
                // (1) idle: because the consumer at target is not actively consuming the mirrored topic
                // (2) dead: the new consumer that is recently created at source and never exist at target
                if (consumerGroupState.equals(ConsumerGroupState.EMPTY)) {
                    idleConsumerGroupsOffset.put(group, targetAdminClient.listConsumerGroupOffsets(group)
                        .partitionsToOffsetAndMetadata().get().entrySet().stream().collect(
                            Collectors.toMap(e -> e.getKey(), e -> e.getValue())));
                }
                // new consumer upstream has state "DEAD" and will be identified during the offset sync-up
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error querying for consumer group {} on cluster {}.", group, targetClusterAlias, e);
            }
        }
    }

    Map<String, Map<TopicPartition, OffsetAndMetadata>> syncGroupOffset() {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> offsetToSyncAll = new HashMap<>();

        // first, sync offsets for the idle consumers at target
        for (Entry<String, Map<TopicPartition, OffsetAndMetadata>> group : getConvertedUpstreamOffset().entrySet()) {
            String consumerGroupId = group.getKey();
            // for each idle consumer at target, read the checkpoints (converted upstream offset)
            // from the pre-populated map
            Map<TopicPartition, OffsetAndMetadata> convertedUpstreamOffset = group.getValue();

            Map<TopicPartition, OffsetAndMetadata> offsetToSync = new HashMap<>();
            Map<TopicPartition, OffsetAndMetadata> targetConsumerOffset = idleConsumerGroupsOffset.get(consumerGroupId);
            if (targetConsumerOffset == null) {
                // this is a new consumer, just sync the offset to target
                syncGroupOffset(consumerGroupId, convertedUpstreamOffset);
                offsetToSyncAll.put(consumerGroupId, convertedUpstreamOffset);
                continue;
            }

            for (Entry<TopicPartition, OffsetAndMetadata> convertedEntry : convertedUpstreamOffset.entrySet()) {

                TopicPartition topicPartition = convertedEntry.getKey();
                OffsetAndMetadata convertedOffset = convertedUpstreamOffset.get(topicPartition);
                if (!targetConsumerOffset.containsKey(topicPartition)) {
                    // if is a new topicPartition from upstream, just sync the offset to target
                    offsetToSync.put(topicPartition, convertedOffset);
                    continue;
                }

                // if translated offset from upstream is smaller than the current consumer offset
                // in the target, skip updating the offset for that partition
                long latestDownstreamOffset = targetConsumerOffset.get(topicPartition).offset();
                if (latestDownstreamOffset >= convertedOffset.offset()) {
                    log.trace("latestDownstreamOffset {} is larger than or equal to convertedUpstreamOffset {} for "
                        + "TopicPartition {}", latestDownstreamOffset, convertedOffset.offset(), topicPartition);
                    continue;
                }
                offsetToSync.put(topicPartition, convertedOffset);
            }

            if (offsetToSync.size() == 0) {
                log.trace("skip syncing the offset for consumer group: {}", consumerGroupId);
                continue;
            }
            syncGroupOffset(consumerGroupId, offsetToSync);

            offsetToSyncAll.put(consumerGroupId, offsetToSync);
        }
        idleConsumerGroupsOffset.clear();
        return offsetToSyncAll;
    }

    void syncGroupOffset(String consumerGroupId, Map<TopicPartition, OffsetAndMetadata> offsetToSync) {
        if (targetAdminClient != null) {
            targetAdminClient.alterConsumerGroupOffsets(consumerGroupId, offsetToSync);
            log.trace("sync-ed the offset for consumer group: {} with {} number of offset entries",
                      consumerGroupId, offsetToSync.size());
        }
    }

    Map<String, Map<TopicPartition, OffsetAndMetadata>> getConvertedUpstreamOffset() {
        Map<String, Map<TopicPartition, OffsetAndMetadata>> result = new HashMap<>();

        for (Entry<String, List<Checkpoint>> entry : checkpointsPerConsumerGroup.entrySet()) {
            String consumerId = entry.getKey();
            Map<TopicPartition, OffsetAndMetadata> convertedUpstreamOffset = new HashMap<>();
            for (Checkpoint checkpoint : entry.getValue()) {
                convertedUpstreamOffset.put(checkpoint.topicPartition(), checkpoint.offsetAndMetadata());
            }
            result.put(consumerId, convertedUpstreamOffset);
        }
        return result;
    }

    private void sendConsumerGroupsMetrics() {
        log.trace("sendConsumerGroupsMetrics for consumerGroups({})", consumerGroups);

        for (String group : consumerGroups) {
            try {
                // Find source current and end offsets for all consumers groups
                Map<TopicPartition, OffsetAndMetadata> sourceConsumerGroupOffsets = listConsumerGroupOffsets(group).entrySet().stream()
                        .filter(x -> shouldCheckpointTopic(x.getKey().topic())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                List<TopicPartition> topicPartitions = new ArrayList<TopicPartition>(sourceConsumerGroupOffsets.keySet());
                // Map<TopicPartition, OffsetSpec> sourceTopicPartitionOffsets = topicPartitions.stream().collect(Collectors.toMap(e -> e, e -> OffsetSpec.latest()));
                // Map<TopicPartition, Long> sourceTopicPartitionEndOffsets = sourceAdminClient.listOffsets(sourceTopicPartitionOffsets).all().get().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
                Map<TopicPartition, Long> sourceTopicPartitionEndOffsets = sourceConsumer.endOffsets(topicPartitions);

                // Find target current and end offsets for all consumers groups
                Map<TopicPartition, OffsetAndMetadata> targetConsumerGroupOffsets = listTargetConsumerGroupOffsets(group).entrySet().stream()
                        .filter(x -> shouldCheckpointTopic(x.getKey().topic())).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                // Map<TopicPartition, OffsetSpec> targetTopicPartitionOffsets = topicPartitions.stream().collect(Collectors.toMap(e -> e, e -> OffsetSpec.latest()));
                // Map<TopicPartition, Long> targetTopicPartitionEndOffsets = targetAdminClient.listOffsets(sourceTopicPartitionOffsets).all().get().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().offset()));
                Map<TopicPartition, Long> targetTopicPartitionEndOffsets = targetConsumer.endOffsets(topicPartitions);

                for (TopicPartition topicPartition : topicPartitions) {
                    long upstreamOffset = sourceConsumerGroupOffsets.containsKey(topicPartition) ? sourceConsumerGroupOffsets.get(topicPartition).offset() : 0;
                    long lastUpstreamOffset = sourceTopicPartitionEndOffsets.containsKey(topicPartition) ? sourceTopicPartitionEndOffsets.get(topicPartition) : 0;
                    long downstreamOffset = targetConsumerGroupOffsets.containsKey(topicPartition) ? targetConsumerGroupOffsets.get(topicPartition).offset() : 0;
                    long lastDownstreamOffset = targetTopicPartitionEndOffsets.containsKey(topicPartition) ? targetTopicPartitionEndOffsets.get(topicPartition) : 0;

                    log.trace("recordConsumerGroupLag for group({}) topicPartition({}) upstreamOffset({}) lastUpstreamOffset({}) downstreamOffset({}) lastDownstreamOffset({})",
                            group, topicPartition, upstreamOffset, lastUpstreamOffset, downstreamOffset, lastDownstreamOffset);
                    metrics.recordConsumerGroupLag(topicPartition, group, upstreamOffset, lastUpstreamOffset, downstreamOffset, lastDownstreamOffset);
                }
            } catch (InterruptedException | ExecutionException e) {
                log.error("Error querying for consumer group {} on cluster {}.", group, targetClusterAlias, e);
            }
        }
    }

    private Map<TopicPartition, OffsetAndMetadata> listTargetConsumerGroupOffsets(String group)
            throws InterruptedException, ExecutionException {
        if (stopping) {
            // short circuit if stopping
            return Collections.emptyMap();
        }
        return targetAdminClient.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
    }

}
