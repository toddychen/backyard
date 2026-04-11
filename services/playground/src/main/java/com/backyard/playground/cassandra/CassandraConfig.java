package com.backyard.playground.cassandra;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;

/**
 * Enables Spring Data Cassandra repositories from notification-common. Entities
 * and repositories live in the shared module, so the base packages must be
 * declared explicitly here rather than relying on component scan.
 */
@Configuration
@EnableCassandraRepositories(basePackages = "com.backyard.notification.common.cassandra.repository")
public class CassandraConfig {
}
