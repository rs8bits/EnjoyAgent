package com.enjoy.agent.shared.security;

import com.enjoy.agent.shared.exception.ApiException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Validates tenant-controlled outbound destinations before every network operation. */
@Component
public class OutboundUrlPolicy {

    private final OutboundUrlProperties properties;

    public OutboundUrlPolicy(OutboundUrlProperties properties) {
        this.properties = properties;
    }

    public URI requireAllowedHttpUrl(String rawUrl, String errorCode) {
        final URI parsed;
        try {
            parsed = new URI(rawUrl == null ? "" : rawUrl.trim()).normalize();
        } catch (URISyntaxException ex) {
            throw invalid(errorCode, "Outbound URL is invalid");
        }

        String scheme = parsed.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || !parsed.isAbsolute()
                || parsed.getHost() == null
                || parsed.getUserInfo() != null
                || parsed.getFragment() != null) {
            throw invalid(errorCode, "Outbound URL must be an absolute HTTP(S) URL without user-info or fragment");
        }
        if ("http".equalsIgnoreCase(scheme) && !properties.isAllowPlainHttp()) {
            throw invalid(errorCode, "Outbound URL must use HTTPS");
        }

        String host;
        try {
            host = IDN.toASCII(parsed.getHost()).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException ex) {
            throw invalid(errorCode, "Outbound URL host is invalid");
        }
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) {
            requirePrivateAllowed(host, errorCode);
        }

        if (!privateAddressAllowed(host)) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (isPrivateOrSpecial(address)) {
                        throw invalid(errorCode, "Outbound URL resolves to a private or special-use address");
                    }
                }
            } catch (UnknownHostException ex) {
                throw invalid(errorCode, "Outbound URL host could not be resolved");
            }
        }

        return parsed;
    }

    private void requirePrivateAllowed(String host, String errorCode) {
        if (!privateAddressAllowed(host)) {
            throw invalid(errorCode, "Outbound URL points to a private or local address");
        }
    }

    private boolean privateAddressAllowed(String host) {
        if (properties.isAllowPrivateAddresses()) {
            return true;
        }
        Set<String> allowed = new HashSet<>();
        for (String configured : properties.getAllowedHosts()) {
            if (configured != null && !configured.isBlank()) {
                allowed.add(configured.trim().toLowerCase(Locale.ROOT));
            }
        }
        return allowed.contains(host);
    }

    private boolean isPrivateOrSpecial(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (first & 0xfe) == 0xfc; // RFC 4193 unique-local fc00::/7
        }
        int first = bytes[0] & 0xff;
        int second = bytes[1] & 0xff;
        return first == 0
                || first >= 224
                || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                || (first == 192 && second == 0)
                || (first == 198 && (second == 18 || second == 19));
    }

    private ApiException invalid(String errorCode, String message) {
        return new ApiException(errorCode, message, HttpStatus.BAD_REQUEST);
    }
}
