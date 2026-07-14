package com.guildworkman.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The single source of truth for CORS.
 *
 * <p>Origins come from the {@code cors.allowed-origins} property (env:
 * {@code CORS_ALLOWED_ORIGINS}), a comma-separated list — so the deployed
 * origin can change without a code change.
 *
 * <p>Uses {@code allowedOriginPatterns} rather than {@code allowedOrigins} so
 * that wildcard patterns (e.g. {@code https://*.vercel.app} for preview
 * deployments) remain usable alongside {@code allowCredentials(true)}, which
 * Spring forbids with a literal {@code "*"} origin.
 *
 * <p>Do not add {@code @CrossOrigin} to controllers — handler-level annotations
 * are combined with this config and were previously drifting out of sync
 * (wrong domain, and an invalid trailing slash on the origin).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
