/**
 * Copyright (C) 2016-2019 Expedia Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hotels.bdp.circustrain.aws.sns.event;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.hotels.bdp.circustrain.aws.sns.event.SnsMessage.PROTOCOL_VERSION;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.amazonaws.services.sns.AmazonSNSAsyncClient;
import com.amazonaws.services.sns.model.PublishRequest;

import com.hotels.bdp.circustrain.api.event.EventPartition;
import com.hotels.bdp.circustrain.api.event.EventPartitions;
import com.hotels.bdp.circustrain.api.event.EventReplicaCatalog;
import com.hotels.bdp.circustrain.api.event.EventReplicaTable;
import com.hotels.bdp.circustrain.api.event.EventSourceCatalog;
import com.hotels.bdp.circustrain.api.event.EventSourceTable;
import com.hotels.bdp.circustrain.api.event.EventTableReplication;
import com.hotels.bdp.circustrain.api.metrics.Metrics;

@RunWith(MockitoJUnitRunner.class)
public class SnsListenerTest {

  private static final String SUBJECT = "choochoo";
  private static final RuntimeException ERROR = new RuntimeException("error message");
  private static final String ENDTIME = "endtime";
  private static EventPartition PARTITION_0;
  private static EventPartition PARTITION_1;

  private static final String STARTTIME = "starttime";
  private static final String EVENT_ID = "EVENT_ID";
  private static final String REPLICA_TABLE_LOCATION = "s3://bucket/path";
  private static final String REPLICA_METASTORE_URIS = "thrift://host:9083";

  static {
    try {
      PARTITION_0 = new EventPartition(Arrays.asList("2014-01-01", "0"), new URI("location_0"));
      PARTITION_1 = new EventPartition(Arrays.asList("2014-01-01", "1"), new URI("location_1"));
    } catch (URISyntaxException e) {}
  }

  private @Mock ListenerConfig config;
  private @Mock AmazonSNSAsyncClient client;
  private @Mock EventSourceCatalog sourceCatalog;
  private @Mock EventReplicaCatalog replicaCatalog;
  private @Mock EventSourceTable sourceTable;
  private @Mock EventReplicaTable replicaTable;
  private @Mock EventTableReplication tableReplication;
  private @Mock Metrics metrics;
  private @Mock Clock clock;

  private @Captor ArgumentCaptor<PublishRequest> requestCaptor;

  private final LinkedHashMap<String, String> partitionKeyTypes = new LinkedHashMap<>();

  @Before
  public void prepare() throws URISyntaxException {
    when(clock.getTime()).thenReturn(STARTTIME, ENDTIME);

    partitionKeyTypes.put("local_date", "string");
    partitionKeyTypes.put("local_hour", "int");

    Map<String, String> headers = new HashMap<>();
    headers.put("pipeline-id", "0943879438");
    when(config.getStartTopic()).thenReturn("startArn");
    when(config.getSuccessTopic()).thenReturn("successArn");
    when(config.getFailTopic()).thenReturn("failArn");
    when(config.getTopic()).thenReturn("topicArn");
    when(config.getSubject()).thenReturn(SUBJECT);
    when(config.getHeaders()).thenReturn(headers);
    when(config.getQueueSize()).thenReturn(10);

    when(replicaTable.getTableLocation()).thenReturn(REPLICA_TABLE_LOCATION);
    when(tableReplication.getSourceTable()).thenReturn(sourceTable);
    when(tableReplication.getReplicaTable()).thenReturn(replicaTable);
    when(tableReplication.getQualifiedReplicaName()).thenReturn("replicaDb.replicaTable");
    when(metrics.getBytesReplicated()).thenReturn(40L);
    when(sourceCatalog.getName()).thenReturn("sourceCatalogName");
    when(replicaCatalog.getName()).thenReturn("replicaCatalogName");
    when(replicaCatalog.getHiveMetastoreUris()).thenReturn(REPLICA_METASTORE_URIS);
    when(sourceTable.getQualifiedName()).thenReturn("srcDb.srcTable");
  }

  @Test
  public void start() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    verify(client).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getValue();
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("startArn"));
    assertThat(request.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"START\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
        + "\"startTime\":\"starttime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\","
        + "\"replicaCatalog\":\"replicaCatalogName\",\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":"
        + "\"replicaDb.replicaTable\",\"replicaTableLocation\":\"s3://bucket/path\",\"replicaMetastoreUris\":\"thrift://host:9083\"}"));
  }

  @Test
  public void successPartitionedTable() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    EventPartitions alteredPartitions = new EventPartitions(partitionKeyTypes);
    alteredPartitions.add(PARTITION_0);
    listener.partitionsToAlter(alteredPartitions);

    EventPartitions createdPartitions = new EventPartitions(partitionKeyTypes);
    createdPartitions.add(PARTITION_1);
    listener.partitionsToCreate(createdPartitions);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getAllValues().get(1);
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("successArn"));
    assertThat(request.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"partitionKeys\":{\"local_date\":\"string\",\"local_hour\":\"int\"},"
            + "\"modifiedPartitions\":"
            + "[[\"2014-01-01\",\"0\"],[\"2014-01-01\",\"1\"]],\"bytesReplicated\":40}"));
  }

  @Test
  public void successUnpartitionedTable() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getAllValues().get(1);
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("successArn"));
    assertThat(request.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"bytesReplicated\":40}"));
  }

  @Test
  public void failure() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    EventPartitions alteredPartitions = new EventPartitions(partitionKeyTypes);
    alteredPartitions.add(PARTITION_0);
    listener.partitionsToAlter(alteredPartitions);

    EventPartitions createdPartitions = new EventPartitions(partitionKeyTypes);
    createdPartitions.add(PARTITION_1);
    listener.partitionsToCreate(createdPartitions);

    listener.copierEnd(metrics);
    listener.tableReplicationFailure(tableReplication, EVENT_ID, ERROR);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getValue();
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("failArn"));
    assertThat(request.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"FAILURE\",\"headers\":"
        + "{\"pipeline-id\":\"0943879438\"},\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":"
        + "\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\","
        + "\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
        + "\"replicaTableLocation\":\""
        + REPLICA_TABLE_LOCATION
        + "\",\"replicaMetastoreUris\":\""
        + REPLICA_METASTORE_URIS
        + "\",\"partitionKeys\":{\"local_date\":\"string\",\"local_hour\":\"int\"},"
        + "\"modifiedPartitions\":[[\"2014-01-01\",\"0\"],[\"2014-01-01\",\"1\"]],\"bytesReplicated\":40,\"errorMessage\":\"error message\"}"));
  }

  @Test
  public void failureBeforeTableReplicationStartIsCalled() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationFailure(tableReplication, EVENT_ID, ERROR);

    verify(client, times(1)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getValue();
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("failArn"));
    assertThat(request.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"FAILURE\",\"headers\":"
        + "{\"pipeline-id\":\"0943879438\"},\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":"
        + "\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\","
        + "\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\",\"replicaTableLocation\":\"s3://bucket/path\",\"replicaMetastoreUris\":\"thrift://host:9083\",\"bytesReplicated\":0,\"errorMessage\":\"error message\"}"));
  }

  @Test
  public void getModifiedPartitionsTypical() throws URISyntaxException {
    List<EventPartition> created = Arrays.asList(new EventPartition(Arrays.asList("a"), new URI("location_a")));
    List<EventPartition> altered = Arrays.asList(new EventPartition(Arrays.asList("b"), new URI("location_b")));
    List<List<String>> partitions = SnsListener.getModifiedPartitions(created, altered);
    assertThat(partitions.size(), is(2));
    assertThat(partitions.get(0), is(Arrays.asList("a")));
    assertThat(partitions.get(1), is(Arrays.asList("b")));
  }

  @Test
  public void getModifiedPartitionsOneOnly() throws URISyntaxException {
    List<EventPartition> created = Arrays
        .asList(new EventPartition(Arrays.asList("a"), new URI("location_a")),
            new EventPartition(Arrays.asList("b"), new URI("location_b")));
    List<List<String>> partitions = SnsListener.getModifiedPartitions(created, null);
    assertThat(partitions.size(), is(2));
    assertThat(partitions.get(0), is(Arrays.asList("a")));
    assertThat(partitions.get(1), is(Arrays.asList("b")));
  }

  @Test
  public void getModifiedPartitionsBothNull() throws URISyntaxException {
    List<List<String>> partitions = SnsListener.getModifiedPartitions(null, null);
    assertThat(partitions, is(nullValue()));
  }

  @Test
  public void messageSizeExceeded() throws Exception {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    EventPartitions createdPartitions = new EventPartitions(partitionKeyTypes);
    LocalDate date = LocalDate.now();
    // send a large number of partitions to trigger message size exceeded
    for (int i = 0; i < 20000; i++) {
      EventPartition partition = new EventPartition(
          Arrays.asList(date.toString(DateTimeFormat.forPattern("yyyy-dd-MM")), "0"), new URI("location_" + i));
      createdPartitions.add(partition);
      date = date.plusDays(1);
    }
    listener.partitionsToCreate(createdPartitions);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getAllValues().get(1);
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("successArn"));
    assertThat(request.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"partitionKeys\":{\"local_date\":\"string\",\"local_hour\":\"int\"},"
            + "\"modifiedPartitions\":[],\"bytesReplicated\":40,"
            + "\"messageTruncated\":true}"));
  }

  @Test
  public void partitionedAndUnpartitionedReplication() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);

    // replicate partitioned table
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    EventPartitions alteredPartitions = new EventPartitions(partitionKeyTypes);
    alteredPartitions.add(PARTITION_0);
    listener.partitionsToAlter(alteredPartitions);

    EventPartitions createdPartitions = new EventPartitions(partitionKeyTypes);
    createdPartitions.add(PARTITION_1);
    listener.partitionsToCreate(createdPartitions);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getAllValues().get(1);
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("successArn"));
    assertThat(request.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"partitionKeys\":{\"local_date\":\"string\",\"local_hour\":\"int\"},"
            + "\"modifiedPartitions\":"
            + "[[\"2014-01-01\",\"0\"],[\"2014-01-01\",\"1\"]],\"bytesReplicated\":40}"));

    // replicate unpartitioned table
    when(sourceCatalog.getName()).thenReturn("sourceUnpartitioned");
    when(replicaCatalog.getName()).thenReturn("replicaUnpartitioned");
    when(clock.getTime()).thenReturn(STARTTIME, ENDTIME);

    listener.tableReplicationStart(tableReplication, EVENT_ID);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(4)).publish(requestCaptor.capture());
    PublishRequest unpartitionedRequest = requestCaptor.getAllValues().get(5);
    assertThat(unpartitionedRequest.getSubject(), is(SUBJECT));
    assertThat(unpartitionedRequest.getTopicArn(), is("successArn"));

    assertThat(unpartitionedRequest.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceUnpartitioned\",\"replicaCatalog\":\"replicaUnpartitioned\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"bytesReplicated\":40}"));
  }

  @Test
  public void partitionedAndUnpartitionedReplicationFailure() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);

    // replicate partitioned table with failure
    listener.tableReplicationStart(tableReplication, EVENT_ID);

    EventPartitions alteredPartitions = new EventPartitions(partitionKeyTypes);
    alteredPartitions.add(PARTITION_0);
    listener.partitionsToAlter(alteredPartitions);

    EventPartitions createdPartitions = new EventPartitions(partitionKeyTypes);
    createdPartitions.add(PARTITION_1);
    listener.partitionsToCreate(createdPartitions);

    listener.copierEnd(metrics);
    listener.tableReplicationFailure(tableReplication, EVENT_ID, ERROR);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getValue();
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("failArn"));
    assertThat(request.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"FAILURE\",\"headers\":"
        + "{\"pipeline-id\":\"0943879438\"},\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":"
        + "\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\","
        + "\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
        + "\"replicaTableLocation\":\""
        + REPLICA_TABLE_LOCATION
        + "\",\"replicaMetastoreUris\":\""
        + REPLICA_METASTORE_URIS
        + "\",\"partitionKeys\":{\"local_date\":\"string\",\"local_hour\":\"int\"},"
        + "\"modifiedPartitions\":[[\"2014-01-01\",\"0\"],[\"2014-01-01\",\"1\"]],\"bytesReplicated\":40,\"errorMessage\":\"error message\"}"));

    // replicate unpartitioned table
    when(sourceCatalog.getName()).thenReturn("sourceUnpartitioned");
    when(replicaCatalog.getName()).thenReturn("replicaUnpartitioned");
    when(clock.getTime()).thenReturn(STARTTIME, ENDTIME);

    listener.tableReplicationStart(tableReplication, EVENT_ID);

    listener.copierEnd(metrics);
    listener.tableReplicationSuccess(tableReplication, EVENT_ID);

    verify(client, times(4)).publish(requestCaptor.capture());
    PublishRequest unpartitionedRequest = requestCaptor.getAllValues().get(5);
    assertThat(unpartitionedRequest.getSubject(), is(SUBJECT));
    assertThat(unpartitionedRequest.getTopicArn(), is("successArn"));

    assertThat(unpartitionedRequest.getMessage(),
        is("{\"protocolVersion\":\""
            + PROTOCOL_VERSION
            + "\""
            + ",\"type\":\"SUCCESS\",\"headers\":{\"pipeline-id\":\"0943879438\"},"
            + "\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":\"EVENT_ID\",\"sourceCatalog\""
            + ":\"sourceUnpartitioned\",\"replicaCatalog\":\"replicaUnpartitioned\",\"sourceTable\":"
            + "\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\","
            + "\"replicaTableLocation\":\""
            + REPLICA_TABLE_LOCATION
            + "\",\"replicaMetastoreUris\":\""
            + REPLICA_METASTORE_URIS
            + "\",\"bytesReplicated\":40}"));
  }

  @Test
  public void noTableReplicationStartForConsecutiveReplications() {
    SnsListener listener = new SnsListener(client, config, clock);
    listener.circusTrainStartUp(new String[] {}, sourceCatalog, replicaCatalog);
    listener.tableReplicationFailure(tableReplication, EVENT_ID, ERROR);

    verify(client, times(1)).publish(requestCaptor.capture());
    PublishRequest request = requestCaptor.getValue();
    assertThat(request.getSubject(), is(SUBJECT));
    assertThat(request.getTopicArn(), is("failArn"));
    assertThat(request.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"FAILURE\",\"headers\":"
        + "{\"pipeline-id\":\"0943879438\"},\"startTime\":\"starttime\",\"endTime\":\"endtime\",\"eventId\":"
        + "\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\","
        + "\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\",\"replicaTableLocation\":\"s3://bucket/path\",\"replicaMetastoreUris\":\"thrift://host:9083\",\"bytesReplicated\":0,\"errorMessage\":\"error message\"}"));

    // the second message should contain these times
    String startTime = "differentStartTime";
    String endTime = "differentEndTime";
    when(clock.getTime()).thenReturn(startTime, endTime);

    listener.tableReplicationFailure(tableReplication, EVENT_ID, ERROR);

    verify(client, times(2)).publish(requestCaptor.capture());
    PublishRequest secondRequest = requestCaptor.getAllValues().get(2);
    assertThat(secondRequest.getSubject(), is(SUBJECT));
    assertThat(secondRequest.getTopicArn(), is("failArn"));
    assertThat(secondRequest.getMessage(), is("{\"protocolVersion\":\""
        + PROTOCOL_VERSION
        + "\""
        + ",\"type\":\"FAILURE\",\"headers\":"
        + "{\"pipeline-id\":\"0943879438\"},\"startTime\":\""
        + startTime
        + "\",\"endTime\":\""
        + endTime
        + "\",\"eventId\":"
        + "\"EVENT_ID\",\"sourceCatalog\":\"sourceCatalogName\",\"replicaCatalog\":\"replicaCatalogName\","
        + "\"sourceTable\":\"srcDb.srcTable\",\"replicaTable\":\"replicaDb.replicaTable\",\"replicaTableLocation\":\"s3://bucket/path\",\"replicaMetastoreUris\":\"thrift://host:9083\",\"bytesReplicated\":0,\"errorMessage\":\"error message\"}"));
  }

}
