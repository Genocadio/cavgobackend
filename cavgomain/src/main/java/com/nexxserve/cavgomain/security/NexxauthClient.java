package com.nexxserve.cavgomain.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the Nexxauth organisation API, authenticated as the SERVER client
 * (static {@code nx_} token). The backend uses it to keep local users in sync
 * with Nexxauth — the authoritative identity + roles store.
 *
 * <p>Users are NOT created by the backend — the apps register them directly
 * against Nexxauth. This client only reads, updates roles on, or disables
 * existing users.
 */
@Component
public class NexxauthClient {

    private static final Logger log = LoggerFactory.getLogger(NexxauthClient.class);

    private final String orgBaseUrl;
    private final String clientId;
    private final String clientToken;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .build();

    public NexxauthClient(
            @Value("${nexxauth.base-url}") String baseUrl,
            @Value("${nexxauth.organisation-id}") Long organisationId,
            @Value("${nexxauth.client-id}") String clientId,
            @Value("${nexxauth.client-token}") String clientToken
    ) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("nexxauth.base-url is not configured (NEXXAUTH_BASE_URL)");
        }
        if (organisationId == null) {
            throw new IllegalStateException("nexxauth.organisation-id is not configured (NEXXAUTH_ORGANISATION_ID)");
        }
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException("nexxauth.client-id is not configured (NEXXAUTH_CLIENT_ID)");
        }
        if (clientToken == null || clientToken.isBlank()) {
            throw new IllegalStateException("nexxauth.client-token is not configured (NEXXAUTH_CLIENT_TOKEN)");
        }
        this.orgBaseUrl = baseUrl.replaceAll("/+$", "")
                + "/organisations/" + organisationId;
        this.clientId = clientId;
        this.clientToken = clientToken;
        log.info("Nexxauth SERVER client initialised — org endpoint: {}", this.orgBaseUrl);
    }

    public OrgUser getUser(Long userId) {
        var json = request("GET", orgBaseUrl + "/users/" + userId, null);
        return parseUser(json);
    }

    public void updateUserRoles(Long userId, List<String> roles) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode rolesNode = body.putArray("roles");
        roles.forEach(rolesNode::add);
        request("PATCH", orgBaseUrl + "/users/" + userId, body);
    }

    public void setUserEnabled(Long userId, boolean enabled) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("enabled", enabled);
        request("PATCH", orgBaseUrl + "/users/" + userId, body);
    }

    public List<OrgRole> listRoles() {
        var json = request("GET", orgBaseUrl + "/roles", null);
        return parseRoles(json);
    }

    public OrgRole createRole(String name, List<String> permissions, boolean isDefault) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("name", name);
        ArrayNode permissionsNode = body.putArray("permissions");
        if (permissions != null) permissions.forEach(permissionsNode::add);
        body.put("isDefault", isDefault);

        var json = request("POST", orgBaseUrl + "/roles", body);
        return parseRole(json);
    }

    public OrgRole updateRole(Long roleId, String name, List<String> permissions, Boolean isDefault) {
        ObjectNode body = objectMapper.createObjectNode();
        if (name != null) body.put("name", name);
        if (permissions != null) {
            ArrayNode permissionsNode = body.putArray("permissions");
            permissions.forEach(permissionsNode::add);
        }
        if (isDefault != null) body.put("isDefault", isDefault);

        var json = request("PATCH", orgBaseUrl + "/roles/" + roleId, body);
        return parseRole(json);
    }

    public void deleteRole(Long roleId) {
        request("DELETE", orgBaseUrl + "/roles/" + roleId, null);
    }

    private String request(String method, String url, ObjectNode body) {
        try {
            var builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + clientToken)
                    .header("X-Client-Id", clientId)
                    .header("Content-Type", "application/json");

            if (body != null) {
                builder.method(method, HttpRequest.BodyPublishers.ofString(body.toString()));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body();

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = extractErrorMessage(responseBody);
                log.warn("Nexxauth {} {} failed ({}): {}", method, url, response.statusCode(), message);
                throw new NexxauthApiException(response.statusCode(), message);
            }
            return responseBody;
        } catch (NexxauthApiException e) {
            throw e;
        } catch (Exception e) {
            log.error("Nexxauth {} {} failed: {}", method, url, e.getMessage());
            throw new NexxauthApiException(0, "Nexxauth request failed: " + e.getMessage());
        }
    }

    private String extractErrorMessage(String responseBody) {
        try {
            var node = objectMapper.readTree(responseBody);
            var message = node.path("message").asText();
            return message.isBlank() ? node.path("error").asText("Unknown error") : message;
        } catch (Exception e) {
            return "Unknown error";
        }
    }

    private OrgUser parseUser(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            var roles = new ArrayList<String>();
            node.path("roles").forEach(r -> roles.add(r.asText()));
            var authTypes = new ArrayList<String>();
            node.path("authTypes").forEach(r -> authTypes.add(r.asText()));
            return new OrgUser(
                    node.path("id").asLong(),
                    text(node, "firstName"),
                    text(node, "lastName"),
                    text(node, "email"),
                    text(node, "phone"),
                    text(node, "username"),
                    node.path("enabled").asBoolean(true),
                    roles,
                    authTypes
            );
        } catch (Exception e) {
            log.error("Failed to parse Nexxauth user response: {}", e.getMessage());
            throw new NexxauthApiException(0, "Failed to parse Nexxauth user response");
        }
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private List<OrgRole> parseRoles(String json) {
        try {
            var roles = new ArrayList<OrgRole>();
            var root = objectMapper.readTree(json);
            if (root.isArray()) {
                for (JsonNode node : root) {
                    roles.add(parseRoleNode(node));
                }
            }
            return roles;
        } catch (Exception e) {
            log.error("Failed to parse Nexxauth roles response: {}", e.getMessage());
            throw new NexxauthApiException(0, "Failed to parse Nexxauth roles response");
        }
    }

    private OrgRole parseRole(String json) {
        try {
            return parseRoleNode(objectMapper.readTree(json));
        } catch (Exception e) {
            log.error("Failed to parse Nexxauth role response: {}", e.getMessage());
            throw new NexxauthApiException(0, "Failed to parse Nexxauth role response");
        }
    }

    private static OrgRole parseRoleNode(JsonNode node) {
        var permissions = new ArrayList<String>();
        node.path("permissions").forEach(p -> permissions.add(p.asText()));
        return new OrgRole(
                node.path("id").asLong(),
                node.path("name").asText(),
                permissions,
                node.path("isDefault").asBoolean(false)
        );
    }

    public record OrgUser(
            Long id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String username,
            boolean enabled,
            List<String> roles,
            List<String> authTypes
    ) {
    }

    public record OrgRole(
            Long id,
            String name,
            List<String> permissions,
            boolean isDefault
    ) {
    }

    public static class NexxauthApiException extends RuntimeException {
        private final int status;

        public NexxauthApiException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int getStatus() {
            return status;
        }
    }
}
