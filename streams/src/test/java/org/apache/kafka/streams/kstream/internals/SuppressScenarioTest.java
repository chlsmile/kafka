/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Bytes;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.KeyValueTimestamp;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.kstream.Serialized;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.test.ConsumerRecordFactory;
import org.apache.kafka.streams.test.OutputVerifier;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static java.time.Duration.ZERO;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.apache.kafka.streams.kstream.Suppressed.BufferConfig.unbounded;
import static org.apache.kafka.streams.kstream.Suppressed.untilTimeLimit;

public class SuppressScenarioTest {
    private static final StringDeserializer STRING_DESERIALIZER = new StringDeserializer();
    private static final StringSerializer STRING_SERIALIZER = new StringSerializer();
    private static final Serde<String> STRING_SERDE = Serdes.String();
    private static final LongDeserializer LONG_DESERIALIZER = new LongDeserializer();

    @Test
    public void shouldImmediatelyEmitEventsWithZeroEmitAfter() {
        final StreamsBuilder builder = new StreamsBuilder();

        final KTable<String, Long> valueCounts = builder
            .table(
                "input",
                Consumed.with(STRING_SERDE, STRING_SERDE),
                Materialized.<String, String, KeyValueStore<Bytes, byte[]>>with(STRING_SERDE, STRING_SERDE)
                    .withCachingDisabled()
                    .withLoggingDisabled()
            )
            .groupBy((k, v) -> new KeyValue<>(v, k), Serialized.with(STRING_SERDE, STRING_SERDE))
            .count();

        valueCounts
            .suppress(untilTimeLimit(ZERO, unbounded()))
            .toStream()
            .to("output-suppressed", Produced.with(STRING_SERDE, Serdes.Long()));

        valueCounts
            .toStream()
            .to("output-raw", Produced.with(STRING_SERDE, Serdes.Long()));

        final Topology topology = builder.build();

        final Properties config = Utils.mkProperties(Utils.mkMap(
            Utils.mkEntry(StreamsConfig.APPLICATION_ID_CONFIG, getClass().getSimpleName().toLowerCase(Locale.getDefault())),
            Utils.mkEntry(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "bogus")
        ));

        final ConsumerRecordFactory<String, String> recordFactory = new ConsumerRecordFactory<>(STRING_SERIALIZER, STRING_SERIALIZER);

        try (final TopologyTestDriver driver = new TopologyTestDriver(topology, config)) {
            driver.pipeInput(recordFactory.create("input", "k1", "v1", 0L));
            driver.pipeInput(recordFactory.create("input", "k1", "v2", 1L));
            driver.pipeInput(recordFactory.create("input", "k2", "v1", 2L));
            verify(
                drainProducerRecords(driver, "output-raw", STRING_DESERIALIZER, LONG_DESERIALIZER),
                asList(
                    new KeyValueTimestamp<>("v1", 1L, 0L),
                    new KeyValueTimestamp<>("v1", 0L, 1L),
                    new KeyValueTimestamp<>("v2", 1L, 1L),
                    new KeyValueTimestamp<>("v1", 1L, 2L)
                )
            );
            verify(
                drainProducerRecords(driver, "output-suppressed", STRING_DESERIALIZER, LONG_DESERIALIZER),
                asList(
                    new KeyValueTimestamp<>("v1", 1L, 0L),
                    new KeyValueTimestamp<>("v1", 0L, 1L),
                    new KeyValueTimestamp<>("v2", 1L, 1L),
                    new KeyValueTimestamp<>("v1", 1L, 2L)
                )
            );
            driver.pipeInput(recordFactory.create("input", "x", "x", 3L));
            verify(
                drainProducerRecords(driver, "output-raw", STRING_DESERIALIZER, LONG_DESERIALIZER),
                singletonList(
                    new KeyValueTimestamp<>("x", 1L, 3L)
                )
            );
            verify(
                drainProducerRecords(driver, "output-suppressed", STRING_DESERIALIZER, LONG_DESERIALIZER),
                singletonList(
                    new KeyValueTimestamp<>("x", 1L, 3L)
                )
            );
            driver.pipeInput(recordFactory.create("input", "x", "x", 4L));
            verify(
                drainProducerRecords(driver, "output-raw", STRING_DESERIALIZER, LONG_DESERIALIZER),
                asList(
                    new KeyValueTimestamp<>("x", 0L, 4L),
                    new KeyValueTimestamp<>("x", 1L, 4L)
                )
            );
            verify(
                drainProducerRecords(driver, "output-suppressed", STRING_DESERIALIZER, LONG_DESERIALIZER),
                asList(
                    new KeyValueTimestamp<>("x", 0L, 4L),
                    new KeyValueTimestamp<>("x", 1L, 4L)
                )
            );
        }
    }

    private <K, V> void verify(final List<ProducerRecord<K, V>> results, final List<KeyValueTimestamp<K, V>> expectedResults) {
        if (results.size() != expectedResults.size()) {
            throw new AssertionError(printRecords(results) + " != " + expectedResults);
        }
        final Iterator<KeyValueTimestamp<K, V>> expectedIterator = expectedResults.iterator();
        for (final ProducerRecord<K, V> result : results) {
            final KeyValueTimestamp<K, V> expected = expectedIterator.next();
            try {
                OutputVerifier.compareKeyValueTimestamp(result, expected.key(), expected.value(), expected.timestamp());
            } catch (final AssertionError e) {
                throw new AssertionError(printRecords(results) + " != " + expectedResults, e);
            }
        }
    }

    private <K, V> List<ProducerRecord<K, V>> drainProducerRecords(final TopologyTestDriver driver, final String topic, final Deserializer<K> keyDeserializer, final Deserializer<V> valueDeserializer) {
        final List<ProducerRecord<K, V>> result = new LinkedList<>();
        for (ProducerRecord<K, V> next = driver.readOutput(topic, keyDeserializer, valueDeserializer);
             next != null;
             next = driver.readOutput(topic, keyDeserializer, valueDeserializer)) {
            result.add(next);
        }
        return new ArrayList<>(result);
    }

    private <K, V> String printRecords(final List<ProducerRecord<K, V>> result) {
        final StringBuilder resultStr = new StringBuilder();
        resultStr.append("[\n");
        for (final ProducerRecord<?, ?> record : result) {
            resultStr.append("  ").append(record.toString()).append("\n");
        }
        resultStr.append("]");
        return resultStr.toString();
    }
}
