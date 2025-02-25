/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.fieldcaps;

import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchModule;
import org.elasticsearch.test.AbstractWireSerializingTestCase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.equalTo;

public class FieldCapabilitiesNodeRequestTests extends AbstractWireSerializingTestCase<FieldCapabilitiesNodeRequest> {

    @Override
    protected FieldCapabilitiesNodeRequest createTestInstance() {
        List<ShardId> randomShards = randomShardIds(randomIntBetween(1, 5));
        String[] randomFields = randomFields(randomIntBetween(1, 20));
        OriginalIndices originalIndices = randomOriginalIndices(randomIntBetween(0, 20));

        QueryBuilder indexFilter = randomBoolean() ? QueryBuilders.termQuery("field", randomAlphaOfLength(5)) : null;
        long nowInMillis = randomLong();

        Map<String, Object> runtimeFields = randomBoolean()
            ? Collections.singletonMap(randomAlphaOfLength(5), randomAlphaOfLength(5))
            : null;

        return new FieldCapabilitiesNodeRequest(randomShards, randomFields, originalIndices, indexFilter, nowInMillis, runtimeFields);
    }

    private List<ShardId> randomShardIds(int numShards) {
        List<ShardId> randomShards = new ArrayList<>(numShards);
        for (int i = 0; i < numShards; i++) {
            randomShards.add(new ShardId("index", randomAlphaOfLength(10), i));
        }
        return randomShards;
    }

    private String[] randomFields(int numFields) {
        String[] randomFields = new String[numFields];
        for (int i = 0; i < numFields; i++) {
            randomFields[i] = randomAlphaOfLengthBetween(5, 10);
        }
        return randomFields;
    }

    private OriginalIndices randomOriginalIndices(int numIndices) {
        String[] randomIndices = new String[numIndices];
        for (int i = 0; i < numIndices; i++) {
            randomIndices[i] = randomAlphaOfLengthBetween(5, 10);
        }
        IndicesOptions indicesOptions = randomBoolean() ? IndicesOptions.strictExpand() : IndicesOptions.lenientExpandOpen();
        return new OriginalIndices(randomIndices, indicesOptions);
    }

    @Override
    protected NamedWriteableRegistry getNamedWriteableRegistry() {
        SearchModule searchModule = new SearchModule(Settings.EMPTY, false, Collections.emptyList());
        return new NamedWriteableRegistry(searchModule.getNamedWriteables());
    }

    @Override
    protected Writeable.Reader<FieldCapabilitiesNodeRequest> instanceReader() {
        return FieldCapabilitiesNodeRequest::new;
    }

    @Override
    protected FieldCapabilitiesNodeRequest mutateInstance(FieldCapabilitiesNodeRequest instance) throws IOException {
        switch (random().nextInt(5)) {
            case 0:
                List<ShardId> shardIds = randomShardIds(instance.shardIds().size() + 1);
                return new FieldCapabilitiesNodeRequest(
                    shardIds,
                    instance.fields(),
                    instance.originalIndices(),
                    instance.indexFilter(),
                    instance.nowInMillis(),
                    instance.runtimeFields()
                );
            case 1:
                String[] fields = randomFields(instance.fields().length + 2);
                return new FieldCapabilitiesNodeRequest(
                    instance.shardIds(),
                    fields,
                    instance.originalIndices(),
                    instance.indexFilter(),
                    instance.nowInMillis(),
                    instance.runtimeFields()
                );
            case 2:
                OriginalIndices originalIndices = randomOriginalIndices(instance.indices().length + 1);
                return new FieldCapabilitiesNodeRequest(
                    instance.shardIds(),
                    instance.fields(),
                    originalIndices,
                    instance.indexFilter(),
                    instance.nowInMillis(),
                    instance.runtimeFields()
                );
            case 3:
                QueryBuilder indexFilter = instance.indexFilter() == null ? QueryBuilders.matchAllQuery() : null;
                return new FieldCapabilitiesNodeRequest(
                    instance.shardIds(),
                    instance.fields(),
                    instance.originalIndices(),
                    indexFilter,
                    instance.nowInMillis(),
                    instance.runtimeFields()
                );
            case 4:
                long nowInMillis = instance.nowInMillis() + 100;
                return new FieldCapabilitiesNodeRequest(
                    instance.shardIds(),
                    instance.fields(),
                    instance.originalIndices(),
                    instance.indexFilter(),
                    nowInMillis,
                    instance.runtimeFields()
                );
            case 5:
                Map<String, Object> runtimeFields = instance.runtimeFields() == null
                    ? Collections.singletonMap(randomAlphaOfLength(5), randomAlphaOfLength(5))
                    : null;
                return new FieldCapabilitiesNodeRequest(
                    instance.shardIds(),
                    instance.fields(),
                    instance.originalIndices(),
                    instance.indexFilter(),
                    instance.nowInMillis(),
                    runtimeFields
                );
            default:
                throw new IllegalStateException("The test should only allow 5 parameters mutated");
        }
    }

    public void testDescription() {
        FieldCapabilitiesNodeRequest r1 = new FieldCapabilitiesNodeRequest(
            Arrays.asList(new ShardId("index-1", "n/a", 0), new ShardId("index-2", "n/a", 3)),
            new String[] { "field-1", "field-2" },
            randomOriginalIndices(1),
            null,
            randomNonNegativeLong(),
            Collections.emptyMap()
        );
        assertThat(r1.getDescription(), equalTo("shards[[index-1][0],[index-2][3]], fields[field-1,field-2]"));

        FieldCapabilitiesNodeRequest r2 = new FieldCapabilitiesNodeRequest(
            Arrays.asList(new ShardId("index-1", "n/a", 0)),
            new String[] { "*" },
            randomOriginalIndices(1),
            null,
            randomNonNegativeLong(),
            Collections.emptyMap()
        );
        assertThat(r2.getDescription(), equalTo("shards[[index-1][0]], fields[*]"));
    }
}
