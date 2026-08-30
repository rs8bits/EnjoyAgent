package com.enjoy.agent.shared.security;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "enjoy.security.outbound")
public class OutboundUrlProperties {

    /** Keep false outside isolated local/test environments. */
    private boolean allowPrivateAddresses;
    /** Plain HTTP can expose prompts and credentials; enable only for isolated local mocks. */
    private boolean allowPlainHttp;
    private List<String> allowedHosts = new ArrayList<>();

    public boolean isAllowPrivateAddresses() {
        return allowPrivateAddresses;
    }

    public void setAllowPrivateAddresses(boolean allowPrivateAddresses) {
        this.allowPrivateAddresses = allowPrivateAddresses;
    }

    public boolean isAllowPlainHttp() {
        return allowPlainHttp;
    }

    public void setAllowPlainHttp(boolean allowPlainHttp) {
        this.allowPlainHttp = allowPlainHttp;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new ArrayList<>() : new ArrayList<>(allowedHosts);
    }
}
