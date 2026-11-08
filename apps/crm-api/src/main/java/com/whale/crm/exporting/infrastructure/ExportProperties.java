package com.whale.crm.exporting.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.exports")
public class ExportProperties {
    private int maxRows = 10000;
    private int pageSize = 500;
    private long fileExpirySeconds = 86400;
    private long downloadExpirySeconds = 60;

    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public long getFileExpirySeconds() { return fileExpirySeconds; }
    public void setFileExpirySeconds(long fileExpirySeconds) { this.fileExpirySeconds = fileExpirySeconds; }
    public long getDownloadExpirySeconds() { return downloadExpirySeconds; }
    public void setDownloadExpirySeconds(long downloadExpirySeconds) { this.downloadExpirySeconds = downloadExpirySeconds; }
}
