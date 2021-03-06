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
package com.hotels.bdp.circustrain.core.replica;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static com.hotels.bdp.circustrain.core.replica.TableType.PARTITIONED;
import static com.hotels.bdp.circustrain.core.replica.TableType.UNPARTITIONED;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import com.hotels.bdp.circustrain.api.SourceLocationManager;
import com.hotels.bdp.circustrain.api.event.ReplicaCatalogListener;

@RunWith(MockitoJUnitRunner.class)
public class FullReplicationReplicaLocationManagerTest {

  private static final String TABLE_PATH = "tablePath";
  private static final String EVENT_ID = "eventId";

  @Mock
  private CleanupLocationManager cleanupLocationManager;
  @Mock
  private ReplicaCatalogListener replicaCatalogListener;
  @Mock
  private SourceLocationManager sourceLocationManager;
  @Mock
  private Partition sourcePartition;
  @Mock
  private StorageDescriptor sd;

  @Test
  public void getTableOnUnpartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, UNPARTITIONED, cleanupLocationManager, replicaCatalogListener);
    Path path = manager.getTableLocation();
    assertThat(path, is(new Path(TABLE_PATH, new Path(EVENT_ID))));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void getPartitionBaseOnUnpartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, UNPARTITIONED, cleanupLocationManager, replicaCatalogListener);
    manager.getPartitionBaseLocation();
  }

  @Test(expected = UnsupportedOperationException.class)
  public void getPartitionLocationOnUnpartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, UNPARTITIONED, cleanupLocationManager, replicaCatalogListener);
    manager.getPartitionLocation(sourcePartition);
  }

  @Test
  public void getPartitionLocationOnPartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, PARTITIONED, cleanupLocationManager, replicaCatalogListener);
    when(sourcePartition.getSd()).thenReturn(sd);
    String partitionLocation = TABLE_PATH + "/" + EVENT_ID + "/partitionKey1=value";
    when(sd.getLocation()).thenReturn(partitionLocation);
    when(sourceLocationManager.getPartitionSubPath(new Path(partitionLocation)))
        .thenReturn(new Path("partitionKey1=value"));

    Path path = manager.getPartitionLocation(sourcePartition);
    assertThat(path, is(new Path(partitionLocation)));
  }

  @Test
  public void getTableOnPartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, PARTITIONED, cleanupLocationManager, replicaCatalogListener);
    Path path = manager.getTableLocation();
    assertThat(path, is(new Path(TABLE_PATH)));
  }

  @Test
  public void getPartitionBaseOnPartitionedTable() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, PARTITIONED, cleanupLocationManager, replicaCatalogListener);
    Path path = manager.getPartitionBaseLocation();
    assertThat(path, is(new Path(TABLE_PATH, new Path(EVENT_ID))));
  }

  @Test
  public void cleanUp() throws Exception {
    FullReplicationReplicaLocationManager manager = new FullReplicationReplicaLocationManager(sourceLocationManager,
        TABLE_PATH, EVENT_ID, UNPARTITIONED, cleanupLocationManager, replicaCatalogListener);
    manager.addCleanUpLocation("pev1", new Path("path1"));
    manager.addCleanUpLocation("pev2", new Path("path2"));
    manager.cleanUpLocations();

    verify(cleanupLocationManager).addCleanupLocation("pev1", new Path("path1"));
    verify(cleanupLocationManager).addCleanupLocation("pev2", new Path("path2"));
  }
}
