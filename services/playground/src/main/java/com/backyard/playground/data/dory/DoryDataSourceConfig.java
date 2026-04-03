package com.backyard.playground.data.dory;

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
 * Datasource for the H2 dory database. Covers entities in {@code data.dory}
 * only.
 */
@Configuration
@EnableJpaRepositories(basePackages = "com.backyard.playground.data.dory", entityManagerFactoryRef = "doryEntityManagerFactory", transactionManagerRef = "doryTransactionManager")
public class DoryDataSourceConfig {

    @Bean
    @Primary
    public DataSource doryDataSource(
            @Value("${spring.datasource.url}") String url,
            @Value("${spring.datasource.driver-class-name}") String driver) {
        return DataSourceBuilder.create().url(url).driverClassName(driver).build();
    }

    @Bean
    @Primary
    public LocalContainerEntityManagerFactoryBean doryEntityManagerFactory(
            @Qualifier("doryDataSource") DataSource dataSource) {
        LocalContainerEntityManagerFactoryBean em = new LocalContainerEntityManagerFactoryBean();
        em.setDataSource(dataSource);
        em.setPackagesToScan("com.backyard.playground.data.dory");
        em.setPersistenceUnitName("dory");
        em.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        em.setJpaPropertyMap(
                Map.of(
                        "hibernate.hbm2ddl.auto",
                        "update",
                        "hibernate.dialect",
                        "org.hibernate.dialect.H2Dialect"));
        return em;
    }

    @Bean
    @Primary
    public PlatformTransactionManager doryTransactionManager(
            @Qualifier("doryEntityManagerFactory") EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
