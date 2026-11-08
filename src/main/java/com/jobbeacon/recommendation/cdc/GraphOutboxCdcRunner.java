package com.jobbeacon.recommendation.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbeacon.recommendation.RecommendationApplication;
import com.jobbeacon.recommendation.config.CdcProperties;
import com.jobbeacon.recommendation.queue.GraphEntityType;
import com.jobbeacon.recommendation.queue.GraphSyncQueueService;
import io.debezium.engine.ChangeEvent;
import io.debezium.engine.DebeziumEngine;
import io.debezium.engine.format.Json;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Streams the backend's {@code graph_sync_outbox} through a logical replication slot of its own (separate from the
 * search sync's) and copies each change into this service's {@code graph_sync_queue}. The slot only advances after a
 * batch is queued, so a crash re-delivers events instead of losing them; queueing an entity twice is harmless.
 *
 * <p>Offsets live in this service's database; the heartbeat writes to the backend database so the slot keeps
 * advancing (and Postgres can recycle WAL) while the outbox is quiet.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.cdc.enabled", havingValue = "true", matchIfMissing = true)
public class GraphOutboxCdcRunner implements ApplicationRunner {

    // Row 1 belongs to the search sync.
    private static final int HEARTBEAT_ID = 2;

    private final CdcProperties properties;
    private final Environment environment;
    private final GraphSyncQueueService queueService;
    private final GraphOutboxEventParser parser;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread engineThread;

    public GraphOutboxCdcRunner(
        CdcProperties properties,
        Environment environment,
        GraphSyncQueueService queueService,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.environment = environment;
        this.queueService = queueService;
        this.parser = new GraphOutboxEventParser(objectMapper);
    }

    @Override
    public void run(ApplicationArguments args) {
        engineThread = new Thread(this::loop, "graph-outbox-cdc");
        engineThread.start();
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (engineThread != null) {
            engineThread.interrupt();
        }
    }

    private void loop() {
        Thread.currentThread().setContextClassLoader(RecommendationApplication.class.getClassLoader());
        while (running.get()) {
            try (DebeziumEngine<ChangeEvent<String, String>> engine = engine()) {
                log.info("Graph outbox CDC is streaming slot {}", properties.slotName());
                engine.run();
            } catch (Exception exception) {
                if (!running.get()) {
                    return;
                }
                log.error("Graph outbox CDC stopped. Retrying in 5 seconds", exception);
                sleep();
            }
        }
    }

    private DebeziumEngine<ChangeEvent<String, String>> engine() {
        return DebeziumEngine.create(Json.class)
            .using(connectorProperties())
            .notifying((records, committer) -> {
                List<GraphOutboxChange> changes = new ArrayList<>();
                for (ChangeEvent<String, String> record : records) {
                    parser.parse(record.value()).ifPresent(changes::add);
                }
                enqueue(changes);
                for (ChangeEvent<String, String> record : records) {
                    committer.markProcessed(record);
                }
                committer.markBatchFinished();
            })
            .build();
    }

    void enqueue(List<GraphOutboxChange> changes) {
        // Repeats within a batch collapse here; the worker collapses the rest when it drains the queue.
        Map<GraphEntityType, Set<UUID>> idsByType = changes.stream().collect(Collectors.groupingBy(
            GraphOutboxChange::type,
            Collectors.mapping(GraphOutboxChange::entityId, Collectors.toCollection(LinkedHashSet::new))));
        idsByType.forEach(queueService::enqueue);
        if (!changes.isEmpty()) {
            log.debug("Queued {} graph changes from the backend outbox", changes.size());
        }
    }

    private Properties connectorProperties() {
        String url = properties.sourceUrl();

        Properties props = new Properties();
        props.setProperty("name", "jobbeacon-graph-cdc");
        props.setProperty("connector.class", "io.debezium.connector.postgresql.PostgresConnector");
        props.setProperty("offset.storage", "io.debezium.storage.jdbc.offset.JdbcOffsetBackingStore");
        props.setProperty("offset.storage.jdbc.connection.url", environment.getRequiredProperty("spring.datasource.url"));
        props.setProperty("offset.storage.jdbc.connection.user",
            environment.getRequiredProperty("spring.datasource.username"));
        props.setProperty("offset.storage.jdbc.connection.password",
            environment.getProperty("spring.datasource.password", ""));
        props.setProperty("offset.storage.jdbc.table.name", "debezium_offset_storage");
        props.setProperty("offset.flush.interval.ms", "10000");
        props.setProperty("plugin.name", "pgoutput");
        props.setProperty("database.hostname", JdbcUrls.host(url));
        props.setProperty("database.port", JdbcUrls.port(url));
        props.setProperty("database.user", properties.sourceUsername());
        props.setProperty("database.password", properties.sourcePassword());
        props.setProperty("database.dbname", JdbcUrls.databaseName(url));
        props.setProperty("topic.prefix", "jobbeacon-graph");
        props.setProperty("slot.name", properties.slotName());
        props.setProperty("publication.name", properties.publicationName());
        props.setProperty("publication.autocreate.mode", "filtered");
        props.setProperty("table.include.list", "public.graph_sync_outbox,public.debezium_heartbeat");
        props.setProperty("tombstones.on.delete", "false");
        props.setProperty("heartbeat.interval.ms", "10000");
        props.setProperty("heartbeat.action.query", "INSERT INTO debezium_heartbeat (id, ts) VALUES (" + HEARTBEAT_ID
            + ", NOW()) ON CONFLICT (id) DO UPDATE SET ts = EXCLUDED.ts");
        props.setProperty("key.converter.schemas.enable", "false");
        props.setProperty("value.converter.schemas.enable", "false");
        props.setProperty("max.batch.size", "512");
        props.setProperty("poll.interval.ms", "1000");
        return props;
    }

    private void sleep() {
        try {
            Thread.sleep(5000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }
}
