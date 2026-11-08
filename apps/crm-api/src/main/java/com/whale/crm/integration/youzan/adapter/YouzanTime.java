package com.whale.crm.integration.youzan.adapter;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class YouzanTime {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private YouzanTime() {
    }

    public static String format(Instant value) {
        return LocalDateTime.ofInstant(value, ZONE).format(FORMAT);
    }
}
