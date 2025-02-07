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

package org.apache.flink.streaming.connectors.kafka.table;

import org.apache.flink.core.testutils.CommonTestUtils;
import org.apache.flink.table.api.Table;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.table.utils.TableTestMatchers;
import org.apache.flink.types.Row;
import org.apache.flink.types.RowKind;
import org.apache.flink.util.CloseableIterator;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.HamcrestCondition.matching;

/** Utils for kafka table tests. */
public class KafkaTableTestUtils {
    public static List<Row> collectRows(Table table, int expectedSize) throws Exception {
        final TableResult result = table.execute();
        final List<Row> collectedRows = new ArrayList<>();
        try (CloseableIterator<Row> iterator = result.collect()) {
            while (collectedRows.size() < expectedSize && iterator.hasNext()) {
                collectedRows.add(iterator.next());
            }
        }
        result.getJobClient()
                .ifPresent(
                        jc -> {
                            try {
                                jc.cancel().get(5, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        });

        return collectedRows;
    }

    /**
     * Variant of {@link #collectRows(Table, int)} for bounded queries. This should not run
     * indefinitely if there is a bounded number of returned rows.
     */
    public static List<Row> collectAllRows(Table table) throws Exception {
        final TableResult result = table.execute();

        final List<Row> collectedRows = new ArrayList<>();
        try (CloseableIterator<Row> iterator = result.collect()) {
            while (iterator.hasNext()) {
                collectedRows.add(iterator.next());
            }
        }

        return collectedRows;
    }

    public static List<String> readLines(String resource) throws IOException {
        final URL url = KafkaChangelogTableITCase.class.getClassLoader().getResource(resource);
        assertThat(url).isNotNull();
        Path path = new File(url.getFile()).toPath();
        return Files.readAllLines(path);
    }

    public static void waitingExpectedResults(
            String sinkName, List<String> expected, Duration timeout)
            throws InterruptedException, TimeoutException {
        Collections.sort(expected);
        CommonTestUtils.waitUtil(
                () -> {
                    List<String> actual = TestValuesTableFactory.getResults(sinkName);
                    Collections.sort(actual);
                    return expected.equals(actual);
                },
                timeout,
                "Can not get the expected result.");
    }

    public static void comparedWithKeyAndOrder(
            Map<Row, List<Row>> expectedData, List<Row> actual, int[] keyLoc) {
        Map<Row, LinkedList<Row>> actualData = new HashMap<>();
        for (Row row : actual) {
            Row key = Row.project(row, keyLoc);
            // ignore row kind
            key.setKind(RowKind.INSERT);
            actualData.computeIfAbsent(key, k -> new LinkedList<>()).add(row);
        }
        // compare key first
        assertThat(actualData).as("Actual result: " + actual).hasSameSizeAs(expectedData);
        // compare by value
        for (Row key : expectedData.keySet()) {
            assertThat(actualData.get(key))
                    .satisfies(
                            matching(TableTestMatchers.deepEqualTo(expectedData.get(key), false)));
        }
    }
}
