package com.demo.mcpclient.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "mcp")
public class ServerConfig {

    private List<ServerInfo> servers = new ArrayList<>();

    public List<ServerInfo> getServers() {
        return servers;
    }

    public void setServers(List<ServerInfo> servers) {
        this.servers = servers;
    }

    public ServerInfo getServerInfo(String name) {
        return servers.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    public static class ServerInfo {
        private String name;
        private String url;
        private String toolNamePrefix;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getToolNamePrefix() {
            return toolNamePrefix;
        }

        public void setToolNamePrefix(String toolNamePrefix) {
            this.toolNamePrefix = toolNamePrefix;
        }
    }
}
