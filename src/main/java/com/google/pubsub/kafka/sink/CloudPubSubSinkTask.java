// Copyright 2016 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
////////////////////////////////////////////////////////////////////////////////
package com.google.pubsub.kafka.sink;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.ByteString;
import com.google.pubsub.kafka.common.ConnectorUtils;
import com.google.pubsub.v1.PublishRequest;
import com.google.pubsub.v1.PublishResponse;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.Topic;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.errors.DataException;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

/***
 * A {@link SinkTask} used by a {@link CloudPubSubSinkConnector} to write messages to
 * <a href="https://cloud.google.com/pubsub">Google Cloud Pub/Sub</a>.
 */
public class CloudPubSubSinkTask extends SinkTask {
  private static final Logger log = LoggerFactory.getLogger(CloudPubSubSinkTask.class);

  private static final int NUM_PUBLISHERS = 10;
  private static final int MAX_REQUEST_SIZE = (10<<20) - 1024; // Leave a little room for overhead.
  private static final int MAX_MESSAGES_PER_REQUEST = 1000;

  private String cpsTopic;
  private int minBatchSize;
  private CloudPubSubPublisher publisher;

  // Maps a topic to another map which contains the unpublished messages per partition
  private Map<String, Map<Integer, UnpublishedMessagesForPartition>> allUnpublishedMessages =
      new HashMap<>();

  /**
   * Holds a list of the unpublished messages for a single partition and also
   * holds the total size in bytes of the protobuf messages in the list.
   */
  private class UnpublishedMessagesForPartition {
    public List<PubsubMessage> messages = new ArrayList<>();
    public int size = 0;
  }

  public CloudPubSubSinkTask() {}

  @Override
  public String version() {
    return new CloudPubSubSinkConnector().version();
  }

  @Override
  public void start(Map<String, String> props) {
    cpsTopic =
        String.format(
            ConnectorUtils.TOPIC_FORMAT,
            props.get(ConnectorUtils.CPS_PROJECT_CONFIG),
            props.get(ConnectorUtils.CPS_TOPIC_CONFIG));
    minBatchSize = Integer.parseInt(props.get(CloudPubSubSinkConnector.CPS_MIN_BATCH_SIZE));
    log.info("Start connector task for topic " + cpsTopic + " min batch size = " + minBatchSize);
    publisher = new CloudPubSubRoundRobinPublisher(NUM_PUBLISHERS);
  }

  @Override
  public void put(Collection<SinkRecord> sinkRecords) {
    log.info("Received " + sinkRecords.size() + " messages to send to CPS.");
    PubsubMessage.Builder builder = PubsubMessage.newBuilder();
    for (SinkRecord record : sinkRecords) {
      // Verify that the schema of the data coming is of type ByteString.
      if (record.valueSchema().type() != Schema.Type.BYTES ||
          !record.valueSchema().name().equals(ConnectorUtils.SCHEMA_NAME)) {
        throw new DataException("Unexpected record of type " + record.valueSchema());
      }
      log.debug("Received record: " + record.toString());
      Map<String, String> attributes = new HashMap<>();
      // We know this can be cast to ByteString because of the schema check above.
      ByteString value = (ByteString) record.value();
      attributes.put(ConnectorUtils.PARTITION_ATTRIBUTE, record.kafkaPartition().toString());
      attributes.put(ConnectorUtils.KAFKA_TOPIC_ATTRIBUTE, record.topic());
      // Get the total number of bytes in this message.
      int messageSize = value.size() + ConnectorUtils.PARTITION_ATTRIBUTE_SIZE
          + record.kafkaPartition().toString().length() + ConnectorUtils.KAFKA_TOPIC_ATTRIBUTE_SIZE
          + record.topic().length();
      // The key could possibly be null so we add the null check.
      if (record.key() != null) {;
        attributes.put(ConnectorUtils.KEY_ATTRIBUTE, record.key().toString());
        // The maximum number of bytes to encode a character in the key string will be 2 bytes.
        messageSize += ConnectorUtils.KEY_ATTRIBUTE_SIZE + (2 * record.key().toString().length());
      }
      PubsubMessage message = builder
          .setData(value)
          .putAllAttributes(attributes)
          .build();
      // Get a map containing all the unpublished messages per partition for this topic.
      Map<Integer, UnpublishedMessagesForPartition> unpublishedMessagesForTopic =
          allUnpublishedMessages.get(record.topic());
      if (unpublishedMessagesForTopic == null) {
        unpublishedMessagesForTopic = new HashMap<>();
        allUnpublishedMessages.put(record.topic(), unpublishedMessagesForTopic);
      }
      // Get the object containing the unpublished messages for the
      // specific topic and partition this Sink Record is associated with.
      UnpublishedMessagesForPartition unpublishedMessages =
          unpublishedMessagesForTopic.get(record.kafkaPartition());
      if (unpublishedMessages == null) {
        unpublishedMessages = new UnpublishedMessagesForPartition();
        unpublishedMessagesForTopic.put(record.kafkaPartition(), unpublishedMessages);
      }
      int newUnpublishedSize = unpublishedMessages.size + messageSize;
      // Publish messages in this partition if the total number of bytes goes over limit.
      if (newUnpublishedSize > MAX_REQUEST_SIZE) {
        publishMessagesForPartition(
            record.topic(),
            record.kafkaPartition(),
            unpublishedMessages.messages);
        newUnpublishedSize = messageSize;
      }
      unpublishedMessages.size = newUnpublishedSize;
      unpublishedMessages.messages.add(message);
      // If the number of messages in this partition is greater than the batch size, then publish.
      if (unpublishedMessages.messages.size() >= minBatchSize) {
        publishMessagesForPartition(
            record.topic(),
            record.kafkaPartition(),
            unpublishedMessages.messages);
      }
    }
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> partitionOffsets) {
    // Publish all messages that have not been published yet.
    for (Map.Entry<String, Map<Integer, UnpublishedMessagesForPartition>> entry :
        allUnpublishedMessages.entrySet()) {
      for (Map.Entry<Integer,UnpublishedMessagesForPartition> innerEntry :
          entry.getValue().entrySet()) {
        publishMessagesForPartition(
            entry.getKey(),
            innerEntry.getKey(),
            innerEntry.getValue().messages);
      }
    }
    allUnpublishedMessages.clear();
  }

  @Override
  public void stop() {}

  private void publishMessagesForPartition(String topic, Integer partition, List<PubsubMessage> messages) {
    int startIndex = 0;
    int endIndex = Math.min(MAX_MESSAGES_PER_REQUEST, messages.size());
    PublishRequest.Builder builder = PublishRequest.newBuilder();
    while (startIndex < messages.size()) {
      PublishRequest request = builder
          .setTopic(cpsTopic)
          .addAllMessages(messages.subList(startIndex, endIndex))
          .build();
      builder.clear();
      log.debug("Publishing: " + (endIndex - startIndex) + " messages");
      ListenableFuture<PublishResponse> responseFuture = publisher.publish(request);
      Futures.addCallback(responseFuture, new FutureCallback<PublishResponse>() {
        @Override
        public void onSuccess(@Nullable PublishResponse result) {}

        @Override
        public void onFailure(Throwable t) {
          throw new RuntimeException(t);
        }
      });
      startIndex = endIndex;
      endIndex = Math.min(endIndex + MAX_MESSAGES_PER_REQUEST, messages.size());
    }
    messages.clear();
  }
}