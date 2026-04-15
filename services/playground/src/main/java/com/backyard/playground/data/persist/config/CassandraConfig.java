package com.backyard.playground.data.persist.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.cassandra.repository.config.EnableCassandraRepositories;

/**
 * Enables Spring Data Cassandra repositories for all keyspaces.
 *
 * <ul>
 *   <li>notification — repos from the shared notification-common module</li>
 *   <li>chat         — repos under data.persist.cassandra.chat</li>
 * </ul>
 *
 * Chat entities use {@code @Table(keyspace = "chat")} so CQL uses
 * fully-qualified table names regardless of the session default keyspace.
 */
@Configuration
@EnableCassandraRepositories(basePackages = {
        "com.backyard.notification.common.cassandra.repository",
        "com.backyard.playground.data.persist.cassandra.chat"
})
public class CassandraConfig {
}
