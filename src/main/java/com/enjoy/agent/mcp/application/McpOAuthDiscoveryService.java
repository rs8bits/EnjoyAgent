package com.enjoy.agent.mcp.application;

import com.enjoy.agent.shared.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * MCP OAuth 元数据发现服务。
 */
@Service
public class McpOAuthDiscoveryService {

    private static final Pattern RESOURCE_METADATA_PATTERN = Pattern.compile("resource_metadata=\"([^\"]+)\"");
    private static final String INITIALIZE_REQUEST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"clientInfo\":{\"name\":\"enjoy-agent\",\"version\":\"0.0.1-SNAPSHOT\"}}}";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public McpOAuthDiscoveryService(ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().build();
        this.objectMapper = objectMapper;
    }

    /**
     * 基于 MCP Server 地址完成 protected resource metadata 与 auth server metadata 发现。
     */
    public McpOAuthDiscoveryResult discover(String baseUrl) {
        ProtectedResourceMetadata protectedResourceMetadata = discoverProtectedResourceMetadata(baseUrl);
        if (protectedResourceMetadata.authorizationServers().isEmpty()) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Protected resource metadata did not expose authorization_servers", HttpStatus.BAD_GATEWAY);
        }

        String issuer = protectedResourceMetadata.authorizationServers().get(0);
        AuthorizationServerMetadata authorizationServerMetadata = discoverAuthorizationServerMetadata(issuer);
        if (!StringUtils.hasText(authorizationServerMetadata.authorizationEndpoint())
                || !StringUtils.hasText(authorizationServerMetadata.tokenEndpoint())) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Authorization server metadata is missing endpoints", HttpStatus.BAD_GATEWAY);
        }

        return new McpOAuthDiscoveryResult(
                protectedResourceMetadata.metadataUrl(),
                issuer,
                authorizationServerMetadata.authorizationEndpoint(),
                authorizationServerMetadata.tokenEndpoint(),
                protectedResourceMetadata.resourceIndicator(),
                authorizationServerMetadata.pkceS256Supported()
        );
    }

    private ProtectedResourceMetadata discoverProtectedResourceMetadata(String baseUrl) {
        for (String candidate : buildProtectedResourceMetadataCandidates(baseUrl)) {
            SimpleHttpResponse response = get(candidate);
            if (response.status().is2xxSuccessful()) {
                return parseProtectedResourceMetadata(candidate, response.body());
            }
        }

        SimpleHttpResponse challenge = probeUnauthorizedChallenge(baseUrl);
        if (challenge.status().value() != HttpStatus.UNAUTHORIZED.value()) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Unable to discover MCP protected resource metadata", HttpStatus.BAD_GATEWAY);
        }

        String authenticateHeader = challenge.headers().getFirst(HttpHeaders.WWW_AUTHENTICATE);
        String metadataUrl = extractResourceMetadataUrl(authenticateHeader);
        if (!StringUtils.hasText(metadataUrl)) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "MCP server challenge did not include resource_metadata", HttpStatus.BAD_GATEWAY);
        }

        SimpleHttpResponse metadataResponse = get(metadataUrl);
        if (!metadataResponse.status().is2xxSuccessful()) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Failed to load protected resource metadata", HttpStatus.BAD_GATEWAY);
        }
        return parseProtectedResourceMetadata(metadataUrl, metadataResponse.body());
    }

    private AuthorizationServerMetadata discoverAuthorizationServerMetadata(String issuer) {
        for (String candidate : buildAuthorizationServerMetadataCandidates(issuer)) {
            SimpleHttpResponse response = get(candidate);
            if (response.status().is2xxSuccessful()) {
                return parseAuthorizationServerMetadata(response.body());
            }
        }
        throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Failed to discover authorization server metadata", HttpStatus.BAD_GATEWAY);
    }

    private ProtectedResourceMetadata parseProtectedResourceMetadata(String metadataUrl, String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            List<String> authorizationServers = new ArrayList<>();
            for (JsonNode item : json.path("authorization_servers")) {
                if (item.isTextual()) {
                    authorizationServers.add(item.asText());
                }
            }
            return new ProtectedResourceMetadata(
                    metadataUrl,
                    authorizationServers,
                    textOrNull(json.get("resource"))
            );
        } catch (Exception ex) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Failed to parse protected resource metadata", HttpStatus.BAD_GATEWAY);
        }
    }

    private AuthorizationServerMetadata parseAuthorizationServerMetadata(String body) {
        try {
            JsonNode json = objectMapper.readTree(body);
            boolean pkceS256Supported = true;
            JsonNode methods = json.get("code_challenge_methods_supported");
            if (methods != null && methods.isArray()) {
                pkceS256Supported = false;
                for (JsonNode method : methods) {
                    if ("S256".equalsIgnoreCase(method.asText())) {
                        pkceS256Supported = true;
                        break;
                    }
                }
            }
            return new AuthorizationServerMetadata(
                    textOrNull(json.get("authorization_endpoint")),
                    textOrNull(json.get("token_endpoint")),
                    pkceS256Supported
            );
        } catch (Exception ex) {
            throw new ApiException("MCP_OAUTH_DISCOVERY_FAILED", "Failed to parse authorization server metadata", HttpStatus.BAD_GATEWAY);
        }
    }

    private SimpleHttpResponse probeUnauthorizedChallenge(String baseUrl) {
        return restClient.post()
                .uri(URI.create(baseUrl))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .body(INITIALIZE_REQUEST)
                .exchange((request, clientResponse) -> new SimpleHttpResponse(
                        clientResponse.getStatusCode(),
                        clientResponse.getHeaders(),
                        clientResponse.bodyTo(String.class)
                ));
    }

    private SimpleHttpResponse get(String url) {
        return restClient.get()
                .uri(URI.create(url))
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, clientResponse) -> new SimpleHttpResponse(
                        clientResponse.getStatusCode(),
                        clientResponse.getHeaders(),
                        clientResponse.bodyTo(String.class)
                ));
    }

    private List<String> buildProtectedResourceMetadataCandidates(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String path = normalizePath(uri.getPath());
        Set<String> candidates = new LinkedHashSet<>();
        if (StringUtils.hasText(path) && !"/".equals(path)) {
            candidates.add(origin + "/.well-known/oauth-protected-resource" + path);
        }
        candidates.add(origin + "/.well-known/oauth-protected-resource");
        return List.copyOf(candidates);
    }

    private List<String> buildAuthorizationServerMetadataCandidates(String issuer) {
        URI uri = URI.create(issuer);
        Set<String> candidates = new LinkedHashSet<>();
        String issuerString = issuer.trim();
        if (issuerString.contains("/.well-known/")) {
            candidates.add(issuerString);
        }
        candidates.add(buildWellKnownUrl(uri, "oauth-authorization-server"));
        candidates.add(buildWellKnownUrl(uri, "openid-configuration"));
        return List.copyOf(candidates);
    }

    private String buildWellKnownUrl(URI uri, String suffix) {
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String path = normalizePath(uri.getPath());
        if (!StringUtils.hasText(path) || "/".equals(path)) {
            return origin + "/.well-known/" + suffix;
        }
        return origin + "/.well-known/" + suffix + path;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private String extractResourceMetadataUrl(String authenticateHeader) {
        if (!StringUtils.hasText(authenticateHeader)) {
            return null;
        }
        Matcher matcher = RESOURCE_METADATA_PATTERN.matcher(authenticateHeader);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private record ProtectedResourceMetadata(
            String metadataUrl,
            List<String> authorizationServers,
            String resourceIndicator
    ) {
    }

    private record AuthorizationServerMetadata(
            String authorizationEndpoint,
            String tokenEndpoint,
            boolean pkceS256Supported
    ) {
    }

    private record SimpleHttpResponse(
            org.springframework.http.HttpStatusCode status,
            HttpHeaders headers,
            String body
    ) {
    }
}
