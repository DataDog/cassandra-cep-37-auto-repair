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

import java.lang.management.ManagementFactory;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.cassandra.locator.InetAddressAndPort;

public class AutoRepairService implements AutoRepairServiceMBean
{
    public static final String MBEAN_NAME = "org.apache.cassandra.db:type=AutoRepairService";

    private boolean autoRepairEnabled;
    private int repairThreads;
    private int repairSubRangeNum;
    private int repairMinFrequencyInHours;
    private int sstableCountHigherThreshold;
    private Pattern ignoreKeyspaces;
    private Pattern repairOnlyKeyspaces;
    private long autoRepairTableMaxRepairTimeInSec;
    private Set<String> autoRepairIgnoreDCs;
    private Set<Set<String>> autoRepairDCGroups = new HashSet<>();
    private int autoRepairHistoryClearDeleteHostsBufferInSec;
    private boolean primaryTokenRangeOnly;
    private int parallelRepairPercentageInGroup;
    private int parallelRepairCountInGroup;

    public static final AutoRepairService instance = new AutoRepairService();

    private AutoRepairService()
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        try
        {
            mbs.registerMBean(this, new ObjectName(MBEAN_NAME));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isAutoRepairEnabled()
    {
        return autoRepairEnabled;
    }

    @Override
    public void setAutoRepairEnable(boolean autoRepairEnabled)
    {
        this.autoRepairEnabled = autoRepairEnabled;
    }

    @Override
    public void setRepairThreads(int repairThreads)
    {
        this.repairThreads = repairThreads;
    }

    @Override
    public int getRepairThreads()
    {
        return repairThreads;
    }

    @Override
    public Set<String> getOnGoingRepairHostIdsByGroupHash(int groupHash)
    {
        Set<String> hostIds = new HashSet<>();
        List<AutoRepairUtils.AutoRepairHistory> histories = AutoRepairUtils.getAutoRepairHistoryByGroupID(groupHash);
        if (histories == null) {
            return null;
        }
        AutoRepairUtils.CurrentRepairStatus currentRepairStatus = new AutoRepairUtils.CurrentRepairStatus(histories, AutoRepairUtils.getPriorityHostIds(groupHash));
        for (UUID id : currentRepairStatus.hostIdsWithOnGoingRepair)
        {
            hostIds.add(id.toString());
        }
        return hostIds;
    }

    @Override
    public Set<String> getOnGoingForceRepairHostIdsByGroupHash(int groupHash)
    {
        Set<String> hostIds = new HashSet<>();
        List<AutoRepairUtils.AutoRepairHistory> histories = AutoRepairUtils.getAutoRepairHistoryByGroupID(groupHash);
        if (histories == null) {
            return null;
        }
        AutoRepairUtils.CurrentRepairStatus currentRepairStatus = new AutoRepairUtils.CurrentRepairStatus(histories, AutoRepairUtils.getPriorityHostIds(groupHash));
        for (UUID id : currentRepairStatus.hostIdsWithOnGoingForceRepair)
        {
            hostIds.add(id.toString());
        }
        return hostIds;
    }

    @Override
    public void setRepairPriorityForHosts(Set<InetAddressAndPort> host)
    {
        AutoRepairUtils.addPriorityHost(host);
    }

    @Override
    public Set<InetAddressAndPort> getRepairHostPriority()
    {
        return AutoRepairUtils.getPriorityHosts();
    }

    public void setForceRepairForHosts(Set<InetAddressAndPort> hosts)
    {
        AutoRepairUtils.setForceRepair(hosts);
    }

    @Override
    public int getRepairSubRangeNum()
    {
        return repairSubRangeNum;
    }

    @Override
    public void setRepairSubRangeNum(int repairSubRanges)
    {
        this.repairSubRangeNum = repairSubRanges;
    }

    @Override
    public int getRepairMinFrequencyInHours()
    {
        return repairMinFrequencyInHours;
    }

    @Override
    public void setRepairMinFrequencyInHours(int repairMinFrequencyInHours)
    {
        this.repairMinFrequencyInHours = repairMinFrequencyInHours;
    }

    @Override
    public int getAutoRepairHistoryClearDeleteHostsBufferInSec() {
        return this.autoRepairHistoryClearDeleteHostsBufferInSec;
    }

    @Override
    public void setAutoRepairHistoryClearDeleteHostsBufferInSec(int seconds) {
        this.autoRepairHistoryClearDeleteHostsBufferInSec = seconds;
    }

    @Override
    public int getRepairSSTableCountHigherThreshold()
    {
        return sstableCountHigherThreshold;
    }

    @Override
    public void setRepairSSTableCountHigherThreshold(int sstableHigherThreshold)
    {
        this.sstableCountHigherThreshold = sstableHigherThreshold;
    }

    @Override
    public Pattern getRepairIgnoreKeyspaces()
    {
        return ignoreKeyspaces;
    }

    @Override
    public void setRepairIgnoreKeyspaces(Pattern ignoreKeyspaceRegex)
    {
        ignoreKeyspaces = ignoreKeyspaceRegex;
    }

    @Override
    public Pattern getRepairOnlyKeyspaces()
    {
        return repairOnlyKeyspaces;
    }

    @Override
    public void setRepairOnlyKeyspaces(Pattern repairOnlyKeyspacesRegex)
    {
        this.repairOnlyKeyspaces = repairOnlyKeyspacesRegex;
    }

    @Override
    public long getAutoRepairTableMaxRepairTimeInSec()
    {
        return autoRepairTableMaxRepairTimeInSec;
    }

    @Override
    public void setAutoRepairTableMaxRepairTimeInSec(long autoRepairTableMaxRepairTimeInSec)
    {
        this.autoRepairTableMaxRepairTimeInSec = autoRepairTableMaxRepairTimeInSec;
    }

    @Override
    public Set<String> getIgnoreDCs()
    {
        return autoRepairIgnoreDCs;
    }

    @Override
    public void setIgnoreDCs(Set<String> ignorDCs)
    {
        this.autoRepairIgnoreDCs = ignorDCs;
    }

    public void setDCGourps(Set<Set<String>> dcGourps) {
        autoRepairDCGroups = dcGourps;
    }

    public Set<Set<String>> getDCGroups() {
        return autoRepairDCGroups;
    }

    public TreeSet<UUID> getCurrentRingHostIds()
    {
        return AutoRepairUtils.getHostIdsInCurrentRing();
    }

    public boolean getRepairPrimaryTokenRangeOnly()
    {
        return primaryTokenRangeOnly;
    }

    public void setPrimaryTokenRangeOnly(boolean primaryTokenRangeOnly)
    {
        this.primaryTokenRangeOnly = primaryTokenRangeOnly;
    }

    public int getParallelRepairPercentageInGroup() {
        return parallelRepairPercentageInGroup;
    }

    public void setParallelRepairPercentageInGroup(int percentageInGroup) {
        this.parallelRepairPercentageInGroup = percentageInGroup;
    }

    public int getParallelRepairCountInGroup() {
        return parallelRepairCountInGroup;
    }

    public void setParallelRepairCountInGroup(int countInGroup) {
        this.parallelRepairCountInGroup = countInGroup;
    }
}
