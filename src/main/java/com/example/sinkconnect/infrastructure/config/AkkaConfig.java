package com.example.sinkconnect.infrastructure.config;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import com.example.sinkconnect.domain.logic.alert.actor.AlertManagerActor;
import com.example.sinkconnect.domain.logic.alert.service.OutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import jakarta.annotation.PreDestroy;

/**
 * Apache Pekko System Configuration for Alert Processing
 *
 * Responsibilities:
 * 1. Create ActorSystem with Cassandra persistence configuration
 * 2. Initialize AlertManagerActor with cluster sharding
 * 3. Integrate with Spring lifecycle
 * 4. Configure Jackson serialization
 */
@Slf4j
@Configuration
@EnableScheduling  // Enable @Scheduled for OutboxRelayService
public class AkkaConfig {

    @Value("${sink-connector.alert.enabled:true}")
    private boolean alertEnabled;

    private ActorSystem<Void> actorSystem;

    /**
     * Create Apache Pekko ActorSystem with Cassandra persistence
     */
    @Bean
    public ActorSystem<Void> actorSystem() {
        if (!alertEnabled) {
            log.info("Alert system is DISABLED, skipping ActorSystem creation");
            return null;
        }

        log.info("Initializing Apache Pekko ActorSystem for Alert processing");

        // Load Pekko configuration from application-akka.conf
        Config akkaConfig = ConfigFactory.load("application-akka.conf");

        // Create ActorSystem with guardian behavior
        actorSystem = ActorSystem.create(
                Behaviors.setup(context -> {
                    log.info("Apache Pekko ActorSystem started: {}", context.getSystem().name());
                    return Behaviors.empty();
                }),
                "AlertSystem",
                akkaConfig
        );

        log.info("Apache Pekko ActorSystem initialized successfully");
        return actorSystem;
    }

    /**
     * Create AlertManagerActor bean
     */
    @Bean
    public ActorRef<AlertManagerActor.Command> alertManagerActor(
            ActorSystem<Void> actorSystem,
            OutboxService outboxService) {

        if (actorSystem == null) {
            log.info("ActorSystem is null, skipping AlertManagerActor creation");
            return null;
        }

        log.info("Creating AlertManagerActor with cluster sharding");

        ActorRef<AlertManagerActor.Command> alertManager = actorSystem.systemActorOf(
                AlertManagerActor.create(outboxService),
                "alertManager",
                Props.empty()
        );

        log.info("AlertManagerActor created successfully");
        return alertManager;
    }

    /**
     * Configure ObjectMapper for Jackson serialization (used by Apache Pekko)
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Register Java 8 time module for Instant serialization
        mapper.findAndRegisterModules();

        // Pretty print for debugging
        // mapper.enable(SerializationFeature.INDENT_OUTPUT);

        return mapper;
    }

    /**
     * Graceful shutdown of ActorSystem
     */
    @PreDestroy
    public void shutdown() {
        if (actorSystem != null) {
            log.info("Shutting down Apache Pekko ActorSystem...");

            try {
                actorSystem.terminate();
                actorSystem.getWhenTerminated().toCompletableFuture().get();
                log.info("Apache Pekko ActorSystem terminated gracefully");
            } catch (Exception e) {
                log.error("Error during ActorSystem shutdown: {}", e.getMessage(), e);
            }
        }
    }
}