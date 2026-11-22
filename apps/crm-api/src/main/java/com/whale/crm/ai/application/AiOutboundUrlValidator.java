package com.whale.crm.ai.application;

import com.whale.crm.platform.web.BusinessException;
import com.whale.crm.platform.web.ErrorCode;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class AiOutboundUrlValidator {
    private static final int MAX_URL_LENGTH = 1024;
    private static final Set<String> ALWAYS_BLOCKED_HOSTS = Set.of(
            "metadata.google.internal",
            "metadata.goog"
    );

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final boolean allowHttp;
    private final Set<String> allowedHosts;
    private final HostResolver resolver;

    AiOutboundUrlValidator(boolean allowHttp, String allowedHostsCsv, HostResolver resolver) {
        this.allowHttp = allowHttp;
        this.allowedHosts = parseAllowedHosts(allowedHostsCsv);
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    AiOutboundUrlValidator(boolean allowHttp, Set<String> allowedHosts, HostResolver resolver) {
        this.allowHttp = allowHttp;
        this.allowedHosts = allowedHosts == null ? Set.of() : allowedHosts.stream()
                .map(AiOutboundUrlValidator::normalizeHost)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
    }

    static Set<String> parseAllowedHosts(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(AiOutboundUrlValidator::normalizeHost)
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    void validate(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 格式无效");
        }
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 格式无效");
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 格式无效");
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 端口无效");
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 仅支持 HTTPS");
        }
        boolean isHttp = "http".equalsIgnoreCase(scheme);
        if (isHttp && !allowHttp) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 默认必须使用 HTTPS");
        }
        String host = normalizeHost(uri.getHost());
        if (host.isEmpty() || ALWAYS_BLOCKED_HOSTS.contains(host)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 禁止访问元数据或内网保留地址");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 格式无效或地址不可解析");
        }
        if (addresses == null || addresses.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 格式无效或地址不可解析");
        }
        boolean allowlisted = allowedHosts.contains(host);
        for (InetAddress address : addresses) {
            if (isAlwaysBlocked(address)) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 禁止访问元数据、链路本地或保留地址");
            }
            boolean internalOrNonPublic = isInternalOrNonPublic(address);
            if (isHttp) {
                if (!internalOrNonPublic || !allowlisted) {
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "仅允许明确配置的内网 HTTP 地址");
                }
            } else if (internalOrNonPublic && !allowlisted) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + " 禁止访问未放行的内网或保留地址");
            }
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.strip().toLowerCase(Locale.ROOT);
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isAlwaysBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            byte[] b = ipv4.getAddress();
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            if (b0 == 0) {
                return true; // 0.0.0.0/8
            }
            if (b0 == 169 && b1 == 254) {
                return true; // 169.254.0.0/16 link-local / cloud metadata
            }
            if ((b0 & 0xF0) == 0xE0) {
                return true; // 224.0.0.0/4 multicast
            }
            if ((b0 & 0xF0) == 0xF0) {
                return true; // 240.0.0.0/4 reserved / broadcast
            }
            return false;
        }
        if (address instanceof Inet6Address ipv6) {
            byte[] b = ipv6.getAddress();
            // Reject IPv4-compatible (::/96), NAT64 (64:ff9b::/96), 6to4 (2002::/16), Teredo (2001::/32)
            if (isZeroRange(b, 0, 12)) {
                return true;
            }
            if ((b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                    && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && isZeroRange(b, 4, 12)) {
                return true; // 64:ff9b::/96
            }
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
                return true; // 2002::/16
            }
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01 && b[2] == 0 && b[3] == 0) {
                return true; // 2001::/32
            }
        }
        return false;
    }

    private static boolean isInternalOrNonPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address ipv4) {
            byte[] b = ipv4.getAddress();
            int b0 = b[0] & 0xFF;
            int b1 = b[1] & 0xFF;
            int b2 = b[2] & 0xFF;
            if (b0 == 127 || b0 == 10) {
                return true;
            }
            if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                return true;
            }
            if (b0 == 192 && b1 == 168) {
                return true;
            }
            if (b0 == 100 && b1 >= 64 && b1 <= 127) {
                return true; // CGNAT 100.64.0.0/10
            }
            if (b0 == 192 && b1 == 0 && (b2 == 0 || b2 == 2)) {
                return true; // 192.0.0.0/24, 192.0.2.0/24
            }
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true; // 198.18.0.0/15
            }
            if (b0 == 198 && b1 == 51 && b2 == 100) {
                return true; // 198.51.100.0/24
            }
            if (b0 == 203 && b1 == 0 && b2 == 113) {
                return true; // 203.0.113.0/24
            }
            return false;
        }
        if (address instanceof Inet6Address ipv6) {
            byte[] b = ipv6.getAddress();
            if ((b[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 ULA
            }
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x01
                    && (b[2] & 0xFF) == 0x0D && (b[3] & 0xFF) == 0xB8) {
                return true; // 2001:db8::/32 documentation
            }
        }
        return false;
    }

    private static boolean isZeroRange(byte[] bytes, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
