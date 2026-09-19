package com.campusdeal.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fails fast in production when an enabled integration still relies on a
 * development default. The exception contains property names only; values are
 * intentionally never included in the message.
 */
@Component
@Profile("prod")
public class ProductionConfigurationGuard implements InitializingBean {

    @Value("${spring.datasource.password:}")
    private String databasePassword;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    @Value("${campusdeal.agent.enabled:true}")
    private boolean agentEnabled;

    @Value("${campusdeal.deepseek.api-key:}")
    private String deepSeekApiKey;

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean pgVectorEnabled;

    @Value("${campusdeal.pgvector.url:}")
    private String pgVectorUrl;

    @Value("${campusdeal.pgvector.username:}")
    private String pgVectorUsername;

    @Value("${campusdeal.pgvector.password:}")
    private String pgVectorPassword;

    @Value("${campusdeal.neo4j.enabled:false}")
    private boolean neo4jEnabled;

    @Value("${campusdeal.neo4j.user:}")
    private String neo4jUser;

    @Value("${campusdeal.neo4j.password:}")
    private String neo4jPassword;

    @Override
    public void afterPropertiesSet() {
        List<String> missing = new ArrayList<>();
        require(databasePassword, "CAMPUSDEAL_DB_PASSWORD", missing);
        require(redisPassword, "CAMPUSDEAL_REDIS_PASSWORD", missing);
        if (agentEnabled) {
            require(deepSeekApiKey, "CAMPUSDEAL_DEEPSEEK_API_KEY", missing);
        }
        if (pgVectorEnabled) {
            require(pgVectorUrl, "CAMPUSDEAL_PGVECTOR_URL", missing);
            require(pgVectorUsername, "CAMPUSDEAL_PGVECTOR_USERNAME", missing);
            require(pgVectorPassword, "CAMPUSDEAL_PGVECTOR_PASSWORD", missing);
            require(deepSeekApiKey, "CAMPUSDEAL_DEEPSEEK_API_KEY", missing);
        }
        if (neo4jEnabled) {
            require(neo4jUser, "CAMPUSDEAL_NEO4J_USER", missing);
            require(neo4jPassword, "CAMPUSDEAL_NEO4J_PASSWORD", missing);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Missing required production configuration: "
                    + String.join(", ", missing));
        }
    }

    private static void require(String value, String name, List<String> missing) {
        if (value == null || value.isBlank()) {
            missing.add(name);
        }
    }
}
