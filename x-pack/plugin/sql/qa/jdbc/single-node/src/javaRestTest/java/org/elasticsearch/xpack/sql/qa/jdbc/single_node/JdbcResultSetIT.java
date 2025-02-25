/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.sql.qa.jdbc.single_node;

import org.elasticsearch.xpack.sql.qa.jdbc.ResultSetTestCase;

public class JdbcResultSetIT extends ResultSetTestCase {
    @Override
    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/94461")
    public void testGettingDateWithoutCalendar() throws Exception {
        super.testGettingDateWithoutCalendar();
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/94329")
    @Override
    public void testGettingDateWithoutCalendarWithNanos() throws Exception {
        super.testGettingDateWithoutCalendarWithNanos();
    }
}
