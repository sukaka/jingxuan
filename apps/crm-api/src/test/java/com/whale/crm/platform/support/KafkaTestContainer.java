package com.whale.crm.platform.support;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

public final class KafkaTestContainer {
    private KafkaTestContainer() {
    }

    public static GenericContainer<?> start() {
        int hostPort = freePort();
        GenericContainer<?> container = new org.testcontainers.containers.FixedHostPortGenericContainer<>("apache/kafka:4.1.2")
                .withFixedExposedPort(hostPort, 9092)
                .withEnv("KAFKA_HEAP_OPTS", "-Xms256m -Xmx512m")
                .withEnv("KAFKA_NODE_ID", "1")
                .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + hostPort)
                .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "true")
                .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1))
                .withStartupTimeout(Duration.ofSeconds(90));
        container.start();
        return container;
    }

    public static String bootstrapServers(GenericContainer<?> container) {
        return "localhost:" + container.getMappedPort(9092);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new IllegalStateException("无法分配 Kafka 测试端口", ex);
        }
    }
}
