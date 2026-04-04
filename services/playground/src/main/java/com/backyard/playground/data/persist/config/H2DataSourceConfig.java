package com.backyard.playground.data.persist.config;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

import javax.sql.DataSource;

/**
 * Datasource for H2. Covers all entities under
 * {@code data.persist.h2}.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.backyard.playground.data.persist.h2", entityManagerFactoryRef = "h2EntityManagerFactory", transactionManagerRef = "h2TransactionManager")
public class H2DataSourceConfig {

    @Bean("h2DataSource")
    @Primary
    public DataSource h2DataSource(@Value("${h2.url}") String url) {
        return DataSourceBuilder.create().url(url).driverClassName("org.h2.Driver").build();
    }

    @Bean("h2EntityManagerFactory")
    @Primary
    public LocalContainerEntityManagerFactoryBean h2EntityManagerFactory(
            @Qualifier("h2DataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.backyard.playground.data.persist.h2");
        em.setPersistenceUnitName("h2");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setJpaPropertyMap(
                Map.of(
                        "hibernate.hbm2ddl.auto",
                        "update",
                        "hibernate.dialect",
                        "org.hibernate.dialect.H2Dialect"));
        return em;
    }

    @Bean("h2TransactionManager")
    @Primary
    public PlatformTransactionManager h2TransactionManager(
            @Qualifier("h2EntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
