package com.backyard.notification.consumer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;

/**
 * Enables Spring Data Cassandra repositories from notification-common. Base
 * package declared explicitly — component scan only covers this module.
 */
@Configuration
@EnableCassandraRepositories(basePackages = "com.backyard.notification.common.cassandra.repository")
public class CassandraConfig {
}
