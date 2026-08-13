package com.example;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class OpenTelemetryConfiguration {

    @Bean
    InitializingBean installOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }

    /**
     * The OpenTelemetry starter instruments everything that goes through the Micrometer Observation
     * API, but JDBC does not — so SQL statements never reach Tempo. Wrapping the DataSource adds a
     * child span per statement carrying the query text as {@code db.query.text}.
     */
    @Bean
    static BeanPostProcessor dataSourceTracingPostProcessor(ObjectProvider<OpenTelemetry> openTelemetry) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof OpenTelemetryDataSource)) {
                    return JdbcTelemetry.builder(openTelemetry.getObject())
                            .build()
                            .wrap(dataSource);
                }
                return bean;
            }
        };
    }
}
