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
package org.apache.cassandra.repair.autorepair;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.NamedThreadFactory;
import org.apache.cassandra.concurrent.ScheduledExecutorPlus;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.repair.RepairParallelism;
import org.apache.cassandra.repair.RepairRunnable;
import org.apache.cassandra.repair.messages.RepairOption;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.schema.Tables;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.streaming.PreviewKind;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.concurrent.Condition;
import org.apache.cassandra.utils.progress.ProgressEvent;
import org.apache.cassandra.utils.progress.ProgressEventType;
import org.apache.cassandra.utils.progress.ProgressListener;

import static org.apache.cassandra.concurrent.ExecutorFactory.Global.executorFactory;
import static org.apache.cassandra.repair.autorepair.AutoRepairUtils.RepairTurn.MY_TURN;
import static org.apache.cassandra.repair.autorepair.AutoRepairUtils.RepairTurn.MY_TURN_DUE_TO_PRIORITY;
import static org.apache.cassandra.repair.autorepair.AutoRepairUtils.RepairTurn.MY_TURN_FORCE_REPAIR;
import static org.apache.cassandra.utils.concurrent.Condition.newOneTimeCondition;


/**
 * This class is designed to run start automatic repair on Cassandra cluster where repair was not in past and now we
 * would like to start repair w/o impacting production workload. Here is how it works:
 * a. It sorts all the nodes's uuid in the auto repair ring and checks whose turn it is to run repair
 * 1. auto repair ring is not same as the Cassandra instance ring. It can be defined by the auto_repair_dc_groups
 * 2. The node with the longest unrepaired time within the auto repair ring will be chosen to run repair
 * b. After node makes a decision to run repair, full sub-range repair is triggered one table at a time
 * If at any point #of unrepaired sstable count goes beyond certain threshold then simply ignore such table as
 * there maybe some challenges to repair such a large table
 * <p>
 * Idea here is to not impact production workload, just try best effort full sub-range repair
 */
public class AutoRepair
{
    private static final Logger logger = LoggerFactory.getLogger(AutoRepair.class);

    static int totalTablesConsideredForRepair = 0;
    static long lastRepairTimeInMs;
    static int nodeRepairTimeInSec = 0;
    static int clusterRepairTimeInSec = 0;
    static int repairInProgress = 0;
    static int repairTableSkipCount = 0;
    static int repairTableFailureCount = 0;
    static AutoRepairUtils.AutoRepairHistory longestUnrepairedNode;

    private static ScheduledExecutorPlus repairExecutor;
    public static AutoRepair instance = new AutoRepair();

    private AutoRepair()
    {
    }

    @VisibleForTesting
    public long getLastRepairTime()
    {
        return lastRepairTimeInMs;
    }

    @VisibleForTesting
    public int getTotalTablesConsideredForRepair()
    {
        return totalTablesConsideredForRepair;
    }

    @VisibleForTesting
    public static void setLastRepairTimeInMs(long lastRepairTime)
    {
        lastRepairTimeInMs = lastRepairTime;
    }

    public static int getClusterRepairTimeInSec()
    {
        return clusterRepairTimeInSec;
    }

    public static int getNodeRepairTimeInSec()
    {
        return nodeRepairTimeInSec;
    }

    public static int isRepairInProgress()
    {
        return repairInProgress;
    }

    public static int getRepairSkippedTablesCount()
    {
        return repairTableSkipCount;
    }

    public static int getLongestUnrepairedSec()
    {
        if (longestUnrepairedNode == null)
        {
            return 0;
        }
        return (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - longestUnrepairedNode.lastRepairFinishTime);
    }

    public static int getRepairFailedTablesCount()
    {
        return repairTableFailureCount;
    }

    public void setup()
    {
        repairExecutor = executorFactory().scheduled(false, "AutoRepair-Repair", Thread.NORM_PRIORITY);
        AutoRepairMetrics.setup();
        AutoRepairService.instance.setRepairSubRangeNum(DatabaseDescriptor.getAutoRepairOptions().getAutoRepairNumberOfSubRanges());
        AutoRepairService.instance.setRepairThreads(DatabaseDescriptor.getAutoRepairOptions().getAutoRepairNumberOfRepairThreads());
        AutoRepairService.instance.setRepairSSTableCountHigherThreshold(DatabaseDescriptor.getAutoRepairOptions()
                                                                                          .getAutoRepairSSTableUpperThreshold());
        AutoRepairService.instance.setRepairMinFrequencyInHours(DatabaseDescriptor.getAutoRepairOptions()
                                                                                  .getAutoRepairMinRepairFrequencyInHours());
        AutoRepairService.instance.setAutoRepairTableMaxRepairTimeInSec(DatabaseDescriptor.getAutoRepairOptions()
                                                                                          .getAutoRepairTableMaxRepairTimeInSec());
        AutoRepairService.instance.setAutoRepairHistoryClearDeleteHostsBufferInSec(DatabaseDescriptor.getAutoRepairOptions().getAutoRepairHistoryClearDeleteHostsBufferInSec());

        if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairIgnoreKeyspaces().length() > 0)
        {
            AutoRepairService.instance.setRepairIgnoreKeyspaces(Pattern.compile(DatabaseDescriptor.getAutoRepairOptions()
                                                                                                  .getAutoRepairIgnoreKeyspaces()));
        }
        if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairOnlyKeyspaces().length() > 0)
        {
            AutoRepairService.instance.setRepairOnlyKeyspaces(Pattern.compile(DatabaseDescriptor.getAutoRepairOptions()
                                                                                                .getAutoRepairOnlyKeyspaces()));
        }
        Set<String> ignoreDCs = new HashSet<>();
        if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairIgnoreDC().length() > 0)
        {
            for (String dcToIgnore : DatabaseDescriptor.getAutoRepairOptions().getAutoRepairIgnoreDC().split(","))
            {
                ignoreDCs.add(dcToIgnore);
            }
        }
        AutoRepairService.instance.setIgnoreDCs(ignoreDCs);
        AutoRepairService.instance.setPrimaryTokenRangeOnly(DatabaseDescriptor.getAutoRepairOptions().getPrimaryTokenRangeOnly());
        AutoRepairService.instance.setParallelRepairPercentageInGroup(DatabaseDescriptor.getAutoRepairOptions().getAutoRepairParallelRepairPercentageInGroup());
        AutoRepairService.instance.setParallelRepairCountInGroup(DatabaseDescriptor.getAutoRepairOptions().getAutoRepairParallelRepairCountInGroup());

        Set<Set<String>> DCGroups = new HashSet<>();
        if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairDCGroups().length() > 0)
        {
            for (String group : DatabaseDescriptor.getAutoRepairOptions().getAutoRepairDCGroups().split("\\|"))
            {
                Set<String> dataCenters = new HashSet<>();
                for (String dc : group.split(","))
                {
                    dataCenters.add(dc);
                }
                DCGroups.add(dataCenters);
            }
        }

        AutoRepairService.instance.setDCGourps(DCGroups);

        AutoRepairUtils.setup();

        if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairAutoSchedule())
            repairExecutor.scheduleWithFixedDelay(() -> repair(60000),
                                                  30,
                                                  DatabaseDescriptor.getAutoRepairOptions().getAutoRepairCheckInterval(),
                                                  TimeUnit.SECONDS);
    }

    public static void runRepair(long millisToWait)
    {
        AutoRepair.repairExecutor.submit(() -> repair(millisToWait));
    }

    @VisibleForTesting
    public static void repair(long millisToWait)
    {
        if (!AutoRepairService.instance.isAutoRepairEnabled())
        {
            logger.info("AutoRepair is stopped hence not running repair");
            return;
        }

        try
        {
            String localDC = DatabaseDescriptor.getLocalDataCenter();
            if (AutoRepairService.instance.getIgnoreDCs().contains(localDC))
            {
                logger.info("Not running repair as this node belongs to datacenter {}", localDC);
                return;
            }

            // refresh the longest unrepaired node
            longestUnrepairedNode = AutoRepairUtils.getHostIDWithLongestUnrepairTime();

            //consistency level to use for local query
            UUID myId = Gossiper.instance.getHostId(FBUtilities.getBroadcastAddressAndPort());
            AutoRepairUtils.RepairTurn turn = AutoRepairUtils.myTurnToRunRepair(myId);
            if (turn == MY_TURN || turn == MY_TURN_DUE_TO_PRIORITY || turn == MY_TURN_FORCE_REPAIR)
            {
                AutoRepairMetrics.recordTurn(turn);
                // For normal auto repair, we will use primary range only repairs (Repair with -pr option).
                // For some cases, we may set the auto_repair_primary_token_range_only flag to false then we will do repair
                // without -pr. We may also do force repair for certain node that we want to repair all the data on one node
                // When doing force repair, we want to repair without -pr.
                boolean primaryRangeOnly = AutoRepairService.instance.getRepairPrimaryTokenRangeOnly() && turn != MY_TURN_FORCE_REPAIR;
                totalTablesConsideredForRepair = 0;
                if (lastRepairTimeInMs != 0)
                {
                    /** check if it is too soon to run repair. one of the reason we
                     * should not run frequent repair is because repair triggers
                     * memtable flush
                     */
                    long timeElapsedSinceLastRepairInHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - lastRepairTimeInMs);
                    if (timeElapsedSinceLastRepairInHours < AutoRepairService.instance.getRepairMinFrequencyInHours())
                    {
                        logger.info("Too soon to run repair, last repair was done {} hour(s) ago",
                                    timeElapsedSinceLastRepairInHours);
                        return;
                    }
                }

                Stopwatch stopWatch = Stopwatch.createStarted();
                logger.info("My host id: {}, my turn to run repair...repair primary-ranges only? {}", myId, AutoRepairService.instance.getRepairPrimaryTokenRangeOnly());
                AutoRepairUtils.updateStartAutoRepairHistory(myId, System.currentTimeMillis(), turn);

                int repairKeyspaceCount = 0;
                int repairTableSuccessCount = 0;
                repairTableFailureCount = 0;
                repairTableSkipCount = 0;
                repairInProgress = 1;
                for (Keyspace keyspace : Keyspace.all())
                {
                    Tables tables = keyspace.getMetadata().tables;
                    Iterator<TableMetadata> iter = tables.iterator();
                    String keyspaceName = keyspace.getName();
                    if (!AutoRepairUtils.shouldRepair(keyspaceName) ||
                        !AutoRepairUtils.checkNodeContainsKeyspaceReplica(keyspace))
                    {
                        continue;
                    }

                    repairKeyspaceCount++;
                    List<String> tablesToBeRepaired = new ArrayList<>();
                    while (iter.hasNext())
                    {
                        totalTablesConsideredForRepair++;
                        String tableName = iter.next().name;

                        ColumnFamilyStore columnFamilyStore = keyspace.getColumnFamilyStore(tableName);
                        // this is done to make autorepair safe as running repair on table with more sstables
                        // may have its own challenges
                        if (columnFamilyStore.getLiveSSTables().size() > AutoRepairService.instance.getRepairSSTableCountHigherThreshold())
                        {
                            logger.info("Too many SSTables for repair, not doing repair on table {}.{} " +
                                        "totalSSTables {}", keyspaceName, tableName, columnFamilyStore.getLiveSSTables().size());
                            repairTableSkipCount++;
                            continue;
                        }
                        tablesToBeRepaired.add(tableName);
                    }
                    for (String tableName : tablesToBeRepaired)
                    {
                        try
                        {
                            if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                            {
                                logger.info("Repair keyspace {} for tables: {}", keyspaceName, tablesToBeRepaired);
                            }
                            else
                            {
                                logger.info("Repair table {}.{}", keyspaceName, tableName);
                            }
                            long startTime = System.currentTimeMillis();
                            //now run full repair on this table
                            boolean repairSuccess = true;
                            Set<Range<Token>> ranges = new HashSet<>();
                            int totalProcessedSubRanges = 0;
                            List<Pair<Token, Token>> subRangesToBeRepaired = new AutoRepairTokenRangeDefault().getRange(primaryRangeOnly, keyspaceName);
                            int totalSubRanges = subRangesToBeRepaired.size();
                            for (Pair<Token, Token> token : subRangesToBeRepaired)
                            {
                                if (!AutoRepairService.instance.isAutoRepairEnabled())
                                {
                                    logger.error("AutoRepair is disabled hence not running repair");
                                    repairInProgress = 0;
                                    return;
                                }

                                if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                                {
                                    if (AutoRepairUtils.keyspaceMaxRepairTimeExceeded(startTime, tablesToBeRepaired.size()))
                                    {
                                        repairTableSkipCount += tablesToBeRepaired.size();
                                        logger.info("Keyspace took too much time to repair hence skipping it {}",
                                                    keyspaceName);
                                        break;
                                    }
                                }
                                else
                                {
                                    if (AutoRepairUtils.tableMaxRepairTimeExceeded(startTime))
                                    {
                                        repairTableSkipCount++;
                                        logger.info("Table took too much time to repair hence skipping it {}.{}",
                                                    keyspaceName, tableName);
                                        break;
                                    }
                                }

                                Token childStartToken = token.left;
                                Token childEndToken = token.right;

                                logger.debug("Current Token Left side {}, right side {}", childStartToken
                                                                                          .toString(), childEndToken.toString());
                                ranges.add(new Range<>(childStartToken, childEndToken));
                                totalProcessedSubRanges++;
                                if ((totalProcessedSubRanges % AutoRepairService.instance.getRepairThreads() == 0) ||
                                    (totalProcessedSubRanges == totalSubRanges))
                                {
                                    RepairOption options = new RepairOption(RepairParallelism.PARALLEL, primaryRangeOnly, false,
                                                                            false, AutoRepairService.instance.getRepairThreads(), ranges, !ranges.isEmpty(), false,
                                                                            false, PreviewKind.NONE, false, true,
                                                                            false, false);
                                    if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                                    {
                                        // repair all tables in one go
                                        options.getColumnFamilies().addAll(tablesToBeRepaired);
                                    }
                                    else
                                    {
                                        options.getColumnFamilies().add(tableName);
                                    }
                                    int repairCmdId = StorageService.instance.nextRepairCommand.incrementAndGet();
                                    RepairRunnable task = new RepairRunnable(StorageService.instance, repairCmdId, options, keyspaceName);
                                    RepairStatus rs = new RepairStatus();
                                    task.addProgressListener(rs);
                                    new Thread(NamedThreadFactory.createThread(task, "auto-repair-thread")).start();
                                    try
                                    {
                                        rs.waitForRepairToComplete();
                                    }
                                    catch (InterruptedException e)
                                    {
                                        logger.error("Exception in cond await:", e);
                                    }

                                    //check repair status
                                    if (rs.success)
                                    {
                                        logger.info("Repair completed for range {}-{} for {}.{}, total subranges: {}," +
                                                    "processed subranges: {}", childStartToken.toString(), childEndToken.toString(),
                                                    keyspaceName, DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace() ? tablesToBeRepaired : tableName, totalSubRanges, totalProcessedSubRanges);
                                    }
                                    else
                                    {
                                        repairSuccess = false;
                                        //in future we can add retry, etc.
                                        logger.info("Repair failed for range {}-{} for {}.{} total subranges: {}," +
                                                    "processed subranges: {}", childStartToken.toString(), childEndToken.toString(),
                                                    keyspaceName, DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace() ? tablesToBeRepaired : tableName, totalSubRanges, totalProcessedSubRanges);
                                    }
                                    ranges.clear();
                                }
                            }
                            if (repairSuccess)
                            {
                                if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                                {
                                    repairTableSuccessCount += tablesToBeRepaired.size();
                                }
                                else
                                {
                                    repairTableSuccessCount++;
                                }
                            }
                            else
                            {
                                if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                                {
                                    repairTableFailureCount += tablesToBeRepaired.size();
                                }
                                else
                                {
                                    repairTableFailureCount++;
                                }
                            }
                            if (DatabaseDescriptor.getAutoRepairOptions().getAutoRepairByKeyspace())
                            {
                                logger.info("Repair completed for keyspace {}, tables: {}", keyspaceName, tablesToBeRepaired);
                                break;
                            }
                            else
                            {
                                logger.info("Repair completed for {}.{}", keyspaceName, tableName);
                            }
                        }
                        catch (Exception e)
                        {
                            logger.error("Exception while repairing keyspace {}:", keyspaceName, e);
                        }
                    }
                }

                //if it was due to priority then remove it now
                if (turn == MY_TURN_DUE_TO_PRIORITY)
                {
                    logger.info("Remove current host from priority list");
                    AutoRepairUtils.removePriorityStatus(myId);
                }

                nodeRepairTimeInSec = (int) stopWatch.elapsed(TimeUnit.SECONDS);
                long timeInHours = TimeUnit.SECONDS.toHours(nodeRepairTimeInSec);
                logger.info("Local repair time {} hour(s), stats: repairKeyspaceCount {}, " +
                            "repairTableSuccessCount {}, repairTableFailureCount {}, " +
                            "repairTableSkipCount {}", timeInHours,
                            repairKeyspaceCount,
                            repairTableSuccessCount,
                            repairTableFailureCount,
                            repairTableSkipCount);
                if (lastRepairTimeInMs != 0)
                {
                    clusterRepairTimeInSec = (int) TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() -
                                                                                   lastRepairTimeInMs);
                    logger.info("Cluster repair time {} day(s)", TimeUnit.SECONDS.toDays(clusterRepairTimeInSec));
                }
                lastRepairTimeInMs = System.currentTimeMillis();
                if (timeInHours == 0 && millisToWait > 0)
                {
                    //If repair finished quickly, happens for an empty instance, in such case
                    //wait for a minute so that the JMX metrics can detect the repairInProgress
                    logger.info("Wait for {} milliseconds.", millisToWait);
                    Thread.sleep(millisToWait);
                }
                repairInProgress = 0;
                AutoRepairUtils.updateFinishAutoRepairHistory(myId, System.currentTimeMillis());
            }
            else
            {
                logger.info("Waiting for my turn...");
            }
        }
        catch (Exception e)
        {
            logger.error("Exception in autorepair:", e);
        }
    }
}

class RepairStatus implements ProgressListener
{
    private static final Logger logger = LoggerFactory.getLogger(RepairStatus.class);
    private final Condition condition = newOneTimeCondition();
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss,SSS");
    boolean success = true;

    public void waitForRepairToComplete() throws InterruptedException
    {
        //if for some reason we don't hear back on repair progress for sometime
        success = condition.await(12, TimeUnit.HOURS);
    }

    @Override
    public void progress(String tag, ProgressEvent event)
    {
        ProgressEventType type = event.getType();
        String message = String.format("[%s] %s", format.format(System.currentTimeMillis()), event.getMessage());
        if (type == ProgressEventType.ERROR)
        {
            logger.error("Repair failure {}", message);
            success = false;
            condition.signalAll();
        }
        if (type == ProgressEventType.PROGRESS)
        {
            message = message + " (progress: " + (int) event.getProgressPercentage() + "%)";
            logger.debug("Repair progress {}", message);
        }
        if (type == ProgressEventType.COMPLETE)
        {
            condition.signalAll();
        }
    }
}
