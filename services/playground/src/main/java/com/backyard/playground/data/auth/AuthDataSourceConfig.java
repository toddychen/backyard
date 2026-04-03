package com.backyard.playground.data.auth;

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
 * Datasource for the MySQL auth database. Covers entities in {@code data.auth}
 * only.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.backyard.playground.data.auth", entityManagerFactoryRef = "authEntityManagerFactory", transactionManagerRef = "authTransactionManager")
public class AuthDataSourceConfig {

    @Bean
    public DataSource authDataSource(
            @Value("${auth.mysql.url}") String url,
            @Value("${auth.mysql.username}") String username,
            @Value("${auth.mysql.password}") String password) {
        return DataSourceBuilder.create()
                .url(url)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean authEntityManagerFactory(
            @Qualifier("authDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.backyard.playground.data.auth");
        em.setPersistenceUnitName("auth");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setJpaPropertyMap(
                Map.of(
                        "hibernate.hbm2ddl.auto",
                        "update",
                        "hibernate.dialect",
                        "org.hibernate.dialect.MySQLDialect"));
        return em;
    }

    @Bean
    public PlatformTransactionManager authTransactionManager(
            @Qualifier("authEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
