/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.routing.allocation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.cluster.routing.allocation.decider.ShardsLimitAllocationDecider;
import org.elasticsearch.common.settings.Settings;

import static org.elasticsearch.cluster.routing.RoutingNodesHelper.shardsWithState;
import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.elasticsearch.cluster.routing.allocation.RoutingNodesUtils.numberOfShardsOfType;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

public class ShardsLimitAllocationTests extends ESAllocationTestCase {
    private final Logger logger = LogManager.getLogger(ShardsLimitAllocationTests.class);

    public void testIndexLevelShardsLimitAllocate() {
        AllocationService strategy = createAllocationService(
            Settings.builder().put("cluster.routing.allocation.node_concurrent_recoveries", 10).build()
        );

        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 4)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 2)
                    )
            )
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(2));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(0));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(0));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(4));

        logger.info("Do another reroute, make sure its still not allocated");
        startInitializingShardsAndReroute(strategy, clusterState);
    }


    public void testIndexLevelShardsLimitAllocateWhileSoftLimits() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 3)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 1)
                    )
            )
            .build();
        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();
        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(3));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED) + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(3));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));

        logger.info("Do another reroute, make sure its still not allocated");
    }

    public void testIndexLevelShardsLimitAllocateWhileSoftLimitsSingleNode() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 3)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 1)
                    )
            )
            .build();
        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();
        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING) , equalTo(3));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED) , equalTo(3));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(3));

        logger.info("Do another reroute, make sure its still not allocated");
    }

    public void testIndexLevelShardsLimitAllocateSoftLimitsRandomised() {
        // note that you have to increase throttling limit to allow allocation for primary > 4
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 100)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 100) // allows 100 shard simultaneously to be unallocated on a node
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");
        int randomShards = 2*randomIntBetween(1, 20);
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomShards)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 4)
                    )
            )
            .build();
        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();
        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();

        clusterState = strategy.reroute(clusterState, "reroute");
//        System.out.println("Total unassigned nodes right now are : " + clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING));
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(randomShards/2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(randomShards/2));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(randomShards/2));
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(randomShards/2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(randomShards/2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(randomShards/2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));

    }

    public void testSoftLimitsRandomised() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 100)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 1000) // allows 250 shard simultaneously to be unallocated on a node
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");
        int randomShards = 2*randomIntBetween(1, 20);
        int randomReplicas = randomIntBetween(1, 5);
        int randomLimit = randomIntBetween(2, 40);

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomShards)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, randomReplicas)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), randomLimit)
                    )
            )
            .build();
        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();
        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")).add(newNode("node3")).add(newNode("node4")).add(newNode("node5")).add(newNode("node6")))
            .build();

        clusterState = strategy.reroute(clusterState, "reroute");
        int initializingNodes = 0;
        for(int i = 1; i <= 6; ++i ) {
            String nodeID = "node" + i;
            initializingNodes += clusterState.getRoutingNodes().node(nodeID).numberOfShardsWithState(ShardRoutingState.INITIALIZING);
        }
        assertThat(initializingNodes, equalTo(randomShards));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        int replicasInitializingCount = 0, primaryStartedCount = 0;
        for(int i = 1; i <= 6; ++i ) {
            String nodeID = "node" + i;
            primaryStartedCount += clusterState.getRoutingNodes().node(nodeID).numberOfShardsWithState(ShardRoutingState.STARTED);
            replicasInitializingCount += clusterState.getRoutingNodes().node(nodeID).numberOfShardsWithState(ShardRoutingState.INITIALIZING);
        }
        assertThat(replicasInitializingCount, equalTo(randomShards * randomReplicas));
        assertThat(initializingNodes, equalTo(primaryStartedCount));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));

        logger.info("Do another reroute, make sure its still not allocated");
    }

    public void testClusterLevelShardsLimitAllocateOnNodeLeft() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 1)
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 2)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                            .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), 0)
                    )
            )
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();

        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));

        // what if node 2 leaves
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove("node2")).build();
        clusterState = strategy.disassociateDeadNodes(clusterState, true, "reroute");
        // verify that NODE_LEAVE is the reason for meta
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(true));
        assertThat(shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size(), equalTo(1));


        assertThat(
            shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).get(0).unassignedInfo().getReason(),
            equalTo(UnassignedInfo.Reason.NODE_LEFT)
        );

        assertThat(
            shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).get(0).unassignedInfo().getUnassignedTimeInMillis(),
            greaterThan(0L)
        );
        clusterState = strategy.reroute(clusterState, "reroute");
        int init = clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING);
        int relocating = clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.RELOCATING);
        int unassigned = shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size();
        int started = clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(STARTED);
        System.out.println("Total shards of all states : " );
        System.out.println("INITIALISING " + init);
        System.out.println("RELOCATING " + relocating);
        System.out.println("UNASSIGNED " + unassigned);
        System.out.println("STARTED " + started);
    }

    public void testClusterLevelShardsLimitAllocateOnNodeLeft3nodes2Shards() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 1)
                .put(ShardsLimitAllocationDecider.SOFTEN_IF_UNASSIGNED_SHARDS_SETTING.getKey(), 1)
                .build()
        );
        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 2)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 1)
                            .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), 0)
                    )
            )
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();

        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")).add(newNode("node3")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");
        System.out.println("First rerouting done, count of shards initialising on node1, node 2, node 3 is " +  clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + " "
            + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + " " + clusterState.getRoutingNodes().node("node3").numberOfShardsWithState(ShardRoutingState.INITIALIZING));

        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        System.out.println("Initialised old primary shards, count of shards initialising on node1, node 2, node 3 is " +  clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + " "
            + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING) + " " + clusterState.getRoutingNodes().node("node3").numberOfShardsWithState(ShardRoutingState.INITIALIZING));
        System.out.println("Initialised old primary shards, count of shards started on node1, node 2, node 3 is " +  clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED) + " "
            + clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED) + " " + clusterState.getRoutingNodes().node("node3").numberOfShardsWithState(ShardRoutingState.STARTED));

        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node3").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        System.out.println("Started all replicas as well");
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));


        // what if node 1 leaves
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove("node1")).build();
        System.out.println("Node 1 left, disassociating Dead nodes");
        clusterState = strategy.disassociateDeadNodes(clusterState, true, "reroute");
        // verify that NODE_LEAVE is the reason for meta
        System.out.println(clusterState.getRoutingNodes().unassigned().getNumPrimaries());

        assertThat(shardsWithState(clusterState.getRoutingNodes(), UNASSIGNED).size(), equalTo(0));

        clusterState = strategy.reroute(clusterState, "reroute");
        int init = clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING);
        int relocating = clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.RELOCATING);

        System.out.println("Total shards of all states : " );
        System.out.println("INITIALISING " + init);
        System.out.println("RELOCATING " + relocating);

    }


    public void testClusterLevelShardsLimitAllocate() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 1)
                .build()
        );

        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 4)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
            )
            .build();

        RoutingTable routingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(routingTable).build();
        logger.info("Adding two nodes and performing rerouting");
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(1));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(2));

        // Bump the cluster total shards to 2
        strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put(ShardsLimitAllocationDecider.CLUSTER_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 2)
                .build()
        );

        logger.info("Do another reroute, make sure shards are now allocated");
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.INITIALIZING), equalTo(1));

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(ShardRoutingState.STARTED), equalTo(2));
        assertThat(clusterState.getRoutingNodes().unassigned().size(), equalTo(0));
    }

    public void testIndexLevelShardsLimitRemain() {
        AllocationService strategy = createAllocationService(
            Settings.builder()
                .put("cluster.routing.allocation.node_concurrent_recoveries", 10)
                .put("cluster.routing.allocation.node_initial_primaries_recoveries", 10)
                .put("cluster.routing.allocation.cluster_concurrent_rebalance", -1)
                .put("cluster.routing.allocation.balance.index", 0.0f)
                .put("cluster.routing.allocation.balance.replica", 1.0f)
                .put("cluster.routing.allocation.balance.primary", 0.0f)
                .build()
        );

        logger.info("Building initial routing table");

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 5)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
            )
            .build();

        RoutingTable initialRoutingTable = RoutingTable.builder().addAsNew(metadata.index("test")).build();

        ClusterState clusterState = ClusterState.builder(
            org.elasticsearch.cluster.ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY)
        ).metadata(metadata).routingTable(initialRoutingTable).build();
        logger.info("Adding one node and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        logger.info("Start the primary shards");
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(numberOfShardsOfType(clusterState.getRoutingNodes(), STARTED), equalTo(5));

        logger.info("add another index with 5 shards");
        metadata = Metadata.builder(clusterState.metadata())
            .put(
                IndexMetadata.builder("test1")
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 5)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                    )
            )
            .build();
        RoutingTable updatedRoutingTable = RoutingTable.builder(clusterState.routingTable()).addAsNew(metadata.index("test1")).build();

        clusterState = ClusterState.builder(clusterState).metadata(metadata).routingTable(updatedRoutingTable).build();

        logger.info("Add another one node and reroute");
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).add(newNode("node2"))).build();
        clusterState = strategy.reroute(clusterState, "reroute");

        clusterState = startInitializingShardsAndReroute(strategy, clusterState);

        assertThat(numberOfShardsOfType(clusterState.getRoutingNodes(), STARTED), equalTo(10));

        for (ShardRouting shardRouting : clusterState.getRoutingNodes().node("node1")) {
            assertThat(shardRouting.getIndexName(), equalTo("test"));
        }
        for (ShardRouting shardRouting : clusterState.getRoutingNodes().node("node2")) {
            assertThat(shardRouting.getIndexName(), equalTo("test1"));
        }

        logger.info("update {} for test, see that things move", ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey());
        metadata = Metadata.builder(clusterState.metadata())
            .put(
                IndexMetadata.builder(clusterState.metadata().index("test"))
                    .settings(
                        settings(Version.CURRENT).put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 5)
                            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
                            .put(ShardsLimitAllocationDecider.INDEX_TOTAL_SHARDS_PER_NODE_SETTING.getKey(), 3)
                    )
            )
            .build();

        clusterState = ClusterState.builder(clusterState).metadata(metadata).build();

        logger.info("reroute after setting");
        clusterState = strategy.reroute(clusterState, "reroute");

        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(STARTED), equalTo(3));
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(RELOCATING), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(RELOCATING), equalTo(2));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(STARTED), equalTo(3));
        // the first move will destroy the balance and the balancer will move 2 shards from node2 to node one right after
        // moving the nodes to node2 since we consider INITIALIZING nodes during rebalance
        clusterState = startInitializingShardsAndReroute(strategy, clusterState);
        // now we are done compared to EvenShardCountAllocator since the Balancer is not soely based on the average
        assertThat(clusterState.getRoutingNodes().node("node1").numberOfShardsWithState(STARTED), equalTo(5));
        assertThat(clusterState.getRoutingNodes().node("node2").numberOfShardsWithState(STARTED), equalTo(5));
    }
}
