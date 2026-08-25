package com.nexxserve.cavgomain.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Validates all required Nexxauth configuration at startup.
 * If any critical property is missing, the application refuses to start.
 */
@Component
public class NexxauthConfigValidator implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(NexxauthConfigValidator.class);

    @Value("${nexxauth.base-url:}")
    private String baseUrl;

    @Value("${nexxauth.organisation-id:}")
    private String organisationId;

    @Value("${nexxauth.public-key:}")
    private String publicKey;

    @Value("${nexxauth.client-id:}")
    private String clientId;

    @Value("${nexxauth.client-token:}")
    private String clientToken;

    @Override
    public void afterPropertiesSet() {
        log.info("─────────────────────────────────────────────────────────────");
        log.info("  Nexxauth Configuration Status");
        log.info("─────────────────────────────────────────────────────────────");

        List<String> missing = new ArrayList<>();

        logProperty("base-url", baseUrl, false, missing);
        logProperty("organisation-id", organisationId, false, missing);
        logProperty("public-key", publicKey, true, missing);
        logProperty("client-id", clientId, false, missing);
        logProperty("client-token", clientToken, true, missing);

        if (!missing.isEmpty()) {
            log.error("─────────────────────────────────────────────────────────────");
            log.error("  STARTUP FAILED: Missing Nexxauth configuration");
            log.error("  The following required properties are not set:");
            for (String prop : missing) {
                log.error("    - nexxauth.{} (env: NEXXAUTH_{})", prop, prop.toUpperCase().replace("-", "_"));
            }
            log.error("─────────────────────────────────────────────────────────────");
            throw new IllegalStateException(
                    "Nexxauth is not configured — the application cannot start without it. "
                    + "Missing properties: " + String.join(", ", missing));
        }

        log.info("─────────────────────────────────────────────────────────────");
        log.info("  Nexxauth configuration OK — all required properties set");
        log.info("─────────────────────────────────────────────────────────────");
    }

    private void logProperty(String name, String value, boolean mask, List<String> missing) {
        if (value == null || value.isBlank()) {
            log.warn("  [MISSING] nexxauth.{}", name);
            missing.add(name);
        } else if (mask) {
            log.info("  [SET]     nexxauth.{} = {}...{}", name, value.substring(0, Math.min(4, value.length())), "*".repeat(Math.max(0, value.length() - 4)));
        } else {
            log.info("  [SET]     nexxauth.{} = {}", name, value);
        }
    }
}
