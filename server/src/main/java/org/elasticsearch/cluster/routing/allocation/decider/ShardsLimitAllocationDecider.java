/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation.decider;

import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;

import java.util.function.BiPredicate;

/**
 * This {@link AllocationDecider} limits the number of shards per node on a per
 * index or node-wide basis. The allocator prevents a single node to hold more
 * than {@code index.routing.allocation.total_shards_per_node} per index and
 * {@code cluster.routing.allocation.total_shards_per_node} globally during the allocation
 * process. The limits of this decider can be changed in real-time via the
 * index settings API.
 * <p>
 * If {@code index.routing.allocation.total_shards_per_node} is reset to a negative value shards
 * per index are unlimited per node. Shards currently in the
 * {@link ShardRoutingState#RELOCATING relocating} state are ignored by this
 * {@link AllocationDecider} until the shard changed its state to either
 * {@link ShardRoutingState#STARTED started},
 * {@link ShardRoutingState#INITIALIZING inializing} or
 * {@link ShardRoutingState#UNASSIGNED unassigned}
 * <p>
 * Note: Reducing the number of shards per node via the index update API can
 * trigger relocation and significant additional load on the clusters nodes.
 * </p>
 */
public class ShardsLimitAllocationDecider extends AllocationDecider {

    public static final String NAME = "shards_limit";

    private volatile int clusterShardLimit;
    private volatile int softenShardLimit;

    /**
     * Controls the maximum number of shards per index on a single Elasticsearch
     * node. Negative values are interpreted as unlimited.
     */
    public static final Setting<Integer> INDEX_TOTAL_SHARDS_PER_NODE_SETTING = Setting.intSetting(
        "index.routing.allocation.total_shards_per_node",
        -1,
        -1,
        Property.Dynamic,
        Property.IndexScope
    );

    /**
     * Controls the maximum number of shards per node on a global level.
     * Negative values are interpreted as unlimited.
     */
    public static final Setting<Integer> CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING = Setting.intSetting(
        "cluster.routing.allocation.total_shards_per_node",
        -1,
        -1,
        Property.Dynamic,
        Property.NodeScope
    );

    public static final Setting<Integer> SOFTEN_IF_UNASSIGNED_SHARDS_SETTING = Setting.intSetting(
        "cluster.routing.allocation.soften_limit",
        -1,
        -1,
        Property.Dynamic,
        Property.NodeScope
    );

    private final Settings settings;

    public ShardsLimitAllocationDecider(Settings settings, ClusterSettings clusterSettings) {
        this.settings = settings;
        this.clusterShardLimit = CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.get(settings);
        this.softenShardLimit = SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.get(settings);
        clusterSettings.addSettingsUpdateConsumer(CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING, this::setClusterShardLimit);
        clusterSettings.addSettingsUpdateConsumer(SOFTEN_IF_UNASSIGNED_SHARDS_SETTING, this::setSoftenShardLimit);
    }

    private void setClusterShardLimit(int clusterShardLimit) {
        this.clusterShardLimit = clusterShardLimit;
    }
    private void setSoftenShardLimit(int softenShardLimit) { this.softenShardLimit = softenShardLimit; }

    @Override
    public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return doDecide(shardRouting, node, allocation, (count, limit) -> count >= limit);
    }

    @Override
    public Decision canAllocateWithSoftShardLimits(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {

        if (softenShardLimit > 0) {
            return allocation.decision(
                Decision.YES,
                NAME,
                "softening of limits is enabled: [softenLimit: %d] > 0.",
                softenShardLimit
            );
        }
        return canAllocate(shardRouting, node, allocation);
    }

    @Override
    public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        return doDecide(shardRouting, node, allocation, (count, limit) -> count > limit);
    }

    private Decision doDecide(
        ShardRouting shardRouting,
        RoutingNode node,
        RoutingAllocation allocation,
        BiPredicate<Integer, Integer> decider
    ) {

        IndexMetadata indexMd = allocation.metadata().getIndexSafe(shardRouting.index());
        final int indexShardLimit = INDEX_TOTAL_SHARDS_PER_NODE_SETTING.get(indexMd.getSettings(), settings);
        // Capture the limit here in case it changes during this method's
        // execution
        final int clusterShardLimit = this.clusterShardLimit;

        if (indexShardLimit <= 0 && clusterShardLimit <= 0) {
            return allocation.decision(
                Decision.YES,
                NAME,
                "total shard limits are disabled: [index: %d, cluster: %d] <= 0",
                indexShardLimit,
                clusterShardLimit
            );
        }

        final int nodeShardCount = node.numberOfOwningShards();
        if (clusterShardLimit > 0 && decider.test(nodeShardCount, clusterShardLimit)) {
            return allocation.decision(
                Decision.NO,
                NAME,
                "too many shards [%d] allocated to this node, cluster setting [%s=%d]",
                nodeShardCount,
                CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.getKey(),
                clusterShardLimit
            );
        }
        if (indexShardLimit > 0) {
            final int indexShardCount = node.numberOfOwningShardsForIndex(shardRouting.index());
            if (decider.test(indexShardCount, indexShardLimit)) {
                return allocation.decision(
                    Decision.NO,
                    NAME,
                    "too many shards [%d] allocated to this node for index [%s], index setting [%s=%d]",
                    indexShardCount,
                    shardRouting.getIndexName(),
                    INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(),
                    indexShardLimit
                );
            }
        }
        return allocation.decision(
            Decision.YES,
            NAME,
            "the shard count [%d] for this node is under the index limit [%d] and cluster level node limit [%d]",
            nodeShardCount,
            indexShardLimit,
            clusterShardLimit
        );
    }

}
