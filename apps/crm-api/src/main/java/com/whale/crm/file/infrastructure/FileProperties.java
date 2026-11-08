package com.whale.crm.file.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.files")
public class FileProperties {
    private boolean enabled;
    private String bucket = "crm-private";
    private String endpoint;
    private String publicEndpoint;
    private String region;
    private String accessKey;
    private String secretKey;
    private int uploadExpirySeconds = 300;
    private int downloadExpirySeconds = 120;
    private long maxFileSizeBytes = 10 * 1024 * 1024;
    private String clamavHost = "localhost";
    private int clamavPort = 3310;
    private int clamavConnectTimeoutMs = 3000;
    private int clamavReadTimeoutMs = 15000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(String publicEndpoint) { this.publicEndpoint = publicEndpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    public int getUploadExpirySeconds() { return uploadExpirySeconds; }
    public void setUploadExpirySeconds(int uploadExpirySeconds) { this.uploadExpirySeconds = uploadExpirySeconds; }
    public int getDownloadExpirySeconds() { return downloadExpirySeconds; }
    public void setDownloadExpirySeconds(int downloadExpirySeconds) { this.downloadExpirySeconds = downloadExpirySeconds; }
    public long getMaxFileSizeBytes() { return maxFileSizeBytes; }
    public void setMaxFileSizeBytes(long maxFileSizeBytes) { this.maxFileSizeBytes = maxFileSizeBytes; }
    public String getClamavHost() { return clamavHost; }
    public void setClamavHost(String clamavHost) { this.clamavHost = clamavHost; }
    public int getClamavPort() { return clamavPort; }
    public void setClamavPort(int clamavPort) { this.clamavPort = clamavPort; }
    public int getClamavConnectTimeoutMs() { return clamavConnectTimeoutMs; }
    public void setClamavConnectTimeoutMs(int clamavConnectTimeoutMs) { this.clamavConnectTimeoutMs = clamavConnectTimeoutMs; }
    public int getClamavReadTimeoutMs() { return clamavReadTimeoutMs; }
    public void setClamavReadTimeoutMs(int clamavReadTimeoutMs) { this.clamavReadTimeoutMs = clamavReadTimeoutMs; }
}
