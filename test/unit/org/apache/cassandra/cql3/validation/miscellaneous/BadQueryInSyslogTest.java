/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.validation.miscellaneous;

import org.apache.cassandra.schema.ColumnMetadata;
import org.apache.cassandra.schema.Schema;
import org.apache.cassandra.schema.TableMetadata;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import junit.framework.Assert;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.cql3.ColumnIdentifier;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.marshal.IntegerType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.monitoring.BadQueriesInSystemLog;
import org.apache.cassandra.db.monitoring.BadQuery;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.service.MonitoringService;

public class BadQueryInSyslogTest extends CQLTester
{
    private static final String KEYSPACE = "ks";
    private static final String TABLE = "tbl";

    private static TableMetadata tableMetadata;
    private static ColumnMetadata v;
    private static ColumnMetadata s;
    private static BadQueriesInSystemLog bq;
    ColumnFamilyStore cfs;

    public BadQueryInSyslogTest()
    {
        requireNetwork();
        BadQuery.setup();
    }

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        tableMetadata = TableMetadata.builder(KEYSPACE, TABLE)
                                .addPartitionKeyColumn("k", UTF8Type.instance)
                                .addStaticColumn("s", UTF8Type.instance)
                                .addClusteringColumn("i", IntegerType.instance)
                                .addRegularColumn("v", UTF8Type.instance)
                                .build();

        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE, KeyspaceParams.simple(1), tableMetadata);
        tableMetadata = Schema.instance.getTableMetadata(KEYSPACE, TABLE);

        v = tableMetadata.getColumn(new ColumnIdentifier("v", true));
        s = tableMetadata.getColumn(new ColumnIdentifier("s", true));
        DatabaseDescriptor.setBadQueryReporter("BadQueriesInSystemLog");
        bq = (BadQueriesInSystemLog) DatabaseDescriptor.getBadQueryReporter();
    }

    @Before
    public void truncate()
    {
        MonitoringService.instance.setBadQueryTracingFraction(1.0);
        cfs = Keyspace.open(KEYSPACE).getColumnFamilyStore(TABLE);
        cfs.truncateBlocking();
        bq.clear();
    }

    private void executeCQL()
    {
        QueryProcessor.executeInternal("INSERT INTO ks.tbl (k, s) VALUES ('k', 's')");
        QueryProcessor.executeInternal("SELECT s FROM ks.tbl WHERE k='k'");
        cfs.forceBlockingFlush();
    }

    @Test
    public void testLargeWritesWithTracingEnabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(true);

        MonitoringService.instance.setBadQueryWriteMaxPartitionSizeInbytes(0);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.LARGE_PARTITION_WRITE).size() > 0);
    }

    @Test
    public void testLargeReadsWithTracingEnabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(true);

        MonitoringService.instance.setBadQueryReadMaxPartitionSizeInbytes(0);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.LARGE_PARTITION_READ).size() > 0);
    }

    @Test
    public void testSlowReadWithTracingEnabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(true);

        MonitoringService.instance.setBadQueryReadSlowLocalLatencyInms(Integer.MIN_VALUE);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.SLOW_READ_LOCAL).size() > 0);
    }

    @Test
    public void testTooManyTombstonesWithTracingEnabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(true);

        MonitoringService.instance.setBadQueryTombstoneLimit(Integer.MIN_VALUE);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.TOO_MANY_TOMBSTONES).size() > 0);
    }

    @Test
    public void testLargeWritesWithTracingDisabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(false);

        MonitoringService.instance.setBadQueryWriteMaxPartitionSizeInbytes(0);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.LARGE_PARTITION_WRITE).size() == 0);
    }

    @Test
    public void testLargeReadsWithTracingDisabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(false);

        MonitoringService.instance.setBadQueryReadMaxPartitionSizeInbytes(0);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.LARGE_PARTITION_READ).size() == 0);
    }

    @Test
    public void testSlowReadWithTracingDisabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(false);

        MonitoringService.instance.setBadQueryReadSlowLocalLatencyInms(Integer.MIN_VALUE);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.SLOW_READ_LOCAL).size() == 0);
    }

    @Test
    public void testTooManyTombstonesWithTracingDisabled() throws Throwable
    {
        DatabaseDescriptor.setBadQueryTracingStatus(false);

        MonitoringService.instance.setBadQueryTombstoneLimit(Integer.MIN_VALUE);
        executeCQL();
        Assert.assertTrue(bq.getBadQueryCategoryQueues().get(BadQuery.BadQueryCategory.TOO_MANY_TOMBSTONES).size() == 0);
    }
}
