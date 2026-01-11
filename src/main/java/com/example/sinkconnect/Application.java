package com.example.sinkconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;
import org.springframework.retry.annotation.EnableRetry;

@SpringBootApplication
@EnableCassandraRepositories(basePackages = "com.example.sinkconnect.infrastructure.repository")
@EnableRetry
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

}
