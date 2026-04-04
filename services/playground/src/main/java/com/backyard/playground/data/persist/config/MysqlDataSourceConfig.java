package com.backyard.playground.data.persist.config;
import org.springframework.context.annotation.Profile;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import javax.sql.DataSource;

/**
 * Datasource for MySQL. Covers all entities under {@code data.persist.mysql} —
 * add new MySQL domains as sub-packages there and they are picked up
 * automatically.
 */
@Profile("!home")
@Configuration
@EnableJpaRepositories(basePackages = "com.backyard.playground.data.persist.mysql", entityManagerFactoryRef = "mysqlEntityManagerFactory", transactionManagerRef = "mysqlTransactionManager")
public class MysqlDataSourceConfig {

    @Bean("mysqlDataSource")
    public DataSource mysqlDataSource(
            @Value("${mysql.url}") String url,
            @Value("${mysql.username}") String username,
            @Value("${mysql.password}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }

    @Bean("mysqlEntityManagerFactory")
    public LocalContainerEntityManagerFactoryBean mysqlEntityManagerFactory(
            @Qualifier("mysqlDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.backyard.playground.data.persist.mysql");
        em.setPersistenceUnitName("mysql");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setJpaPropertyMap(
                Map.of(
                        "hibernate.hbm2ddl.auto",
                        "update",
                        "hibernate.dialect",
                        "org.hibernate.dialect.MySQLDialect"));
        return em;
    }

    @Bean("mysqlTransactionManager")
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier("mysqlEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
