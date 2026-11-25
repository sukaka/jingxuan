from __future__ import annotations

import ipaddress
import socket
from dataclasses import dataclass
from typing import Callable
from urllib.parse import urlsplit

import httpcore
import httpx

ALWAYS_BLOCKED_HOSTS = frozenset({
    "metadata.google.internal",
    "metadata.goog",
})

BLOCKED_LOCAL_HOSTNAMES = frozenset({
    "localhost",
    "localhost.localdomain",
})

RESERVED_IPV4_NETWORKS = (
    ipaddress.ip_network("100.64.0.0/10"),   # CGNAT
    ipaddress.ip_network("192.0.0.0/24"),    # IETF protocol assignments
    ipaddress.ip_network("192.0.2.0/24"),    # TEST-NET-1
    ipaddress.ip_network("198.18.0.0/15"),   # Benchmarking
    ipaddress.ip_network("198.51.100.0/24"), # TEST-NET-2
    ipaddress.ip_network("203.0.113.0/24"),  # TEST-NET-3
    ipaddress.ip_network("240.0.0.0/4"),     # Reserved / broadcast
)

EMBEDDED_IPV6_NETWORKS = (
    ipaddress.ip_network("::ffff:0:0/96"),   # IPv4-mapped IPv6
    ipaddress.ip_network("::/96"),           # IPv4-compatible IPv6
    ipaddress.ip_network("64:ff9b::/96"),    # NAT64
    ipaddress.ip_network("2002::/16"),       # 6to4
    ipaddress.ip_network("2001::/32"),       # Teredo
)

DOCUMENTATION_IPV6_NETWORK = ipaddress.ip_network("2001:db8::/32")
ULA_IPV6_NETWORK = ipaddress.ip_network("fc00::/7")

Resolver = Callable[[str, int], list[tuple]]


class UnsafeOutboundUrlError(ValueError):
    pass


@dataclass(frozen=True)
class ValidatedEndpoint:
    url: str
    scheme: str
    host: str
    port: int
    pinned_ips: tuple[str, ...]


def normalize_host(host: str) -> str:
    value = host.strip().lower()
    if value.startswith("[") and value.endswith("]") and len(value) > 2:
        value = value[1:-1]
    return value.rstrip(".")


def parse_allowed_hosts(raw: str | set[str] | list[str] | tuple[str, ...] | None) -> frozenset[str]:
    if not raw:
        return frozenset()
    items = raw.split(",") if isinstance(raw, str) else raw
    return frozenset(normalize_host(item) for item in items if item and normalize_host(item))


def _unwrap_ip(value: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address:
    addr = ipaddress.ip_address(value.split("%", 1)[0])
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped is not None:
        return addr.ipv4_mapped
    return addr


def _is_always_blocked(addr: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    if addr.is_unspecified or addr.is_link_local or addr.is_multicast:
        return True
    if isinstance(addr, ipaddress.IPv4Address):
        if int(addr) >> 24 == 0:
            return True
        if addr in ipaddress.ip_network("240.0.0.0/4"):
            return True
        return False
    return any(addr in net for net in EMBEDDED_IPV6_NETWORKS)


def _is_internal_or_non_public(addr: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    if addr.is_loopback or addr.is_private:
        return True
    if isinstance(addr, ipaddress.IPv4Address):
        return any(addr in net for net in RESERVED_IPV4_NETWORKS)
    return addr.is_site_local or addr in ULA_IPV6_NETWORK or addr in DOCUMENTATION_IPV6_NETWORK or not addr.is_global


def _parse_literal_ip(host: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return _unwrap_ip(host)
    except ValueError:
        return None


def validate_outbound_base_url(
    value: str,
    *,
    allow_http: bool,
    allowed_hosts: str | set[str] | list[str] | tuple[str, ...] | None = "",
    label: str = "接口地址",
    resolver: Resolver | None = None,
    resolve_dns: bool = True,
) -> ValidatedEndpoint:
    normalized = value.strip().rstrip("/")
    if not normalized or len(normalized) > 1024:
        raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析")
    try:
        parsed = urlsplit(normalized)
    except ValueError as exc:
        raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析") from exc
    if parsed.hostname is None or parsed.username is not None or parsed.password is not None:
        raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析")
    if "?" in normalized or "#" in normalized or parsed.query or parsed.fragment:
        raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析")
    scheme = parsed.scheme.lower()
    if scheme not in ("https", "http"):
        raise UnsafeOutboundUrlError(f"{label} 仅支持 HTTPS")
    if scheme == "http" and not allow_http:
        raise UnsafeOutboundUrlError(f"{label} 默认必须使用 HTTPS")
    try:
        port = parsed.port if parsed.port is not None else (443 if scheme == "https" else 80)
    except ValueError as exc:
        raise UnsafeOutboundUrlError(f"{label} 端口无效") from exc
    if port < 1 or port > 65535:
        raise UnsafeOutboundUrlError(f"{label} 端口无效")

    host = normalize_host(parsed.hostname)
    if not host or host in ALWAYS_BLOCKED_HOSTS:
        raise UnsafeOutboundUrlError(f"{label} 禁止访问元数据或内网保留地址")

    allowlist = parse_allowed_hosts(allowed_hosts)
    allowlisted = host in allowlist

    literal_ip = _parse_literal_ip(host)
    if literal_ip is not None:
        resolved_ips = (str(literal_ip),)
    elif resolve_dns:
        lookup = resolver or (lambda h, p: socket.getaddrinfo(h, p, type=socket.SOCK_STREAM))
        try:
            infos = lookup(host, port)
        except OSError as exc:
            raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析") from exc
        deduped: list[str] = []
        for item in infos:
            ip_text = str(_unwrap_ip(item[4][0]))
            if ip_text not in deduped:
                deduped.append(ip_text)
        if not deduped:
            raise UnsafeOutboundUrlError(f"{label} 格式无效或地址不可解析")
        resolved_ips = tuple(deduped)
    else:
        if host in BLOCKED_LOCAL_HOSTNAMES and not allowlisted:
            raise UnsafeOutboundUrlError(f"{label} 禁止访问未放行的内网或保留地址")
        if scheme == "http" and not allowlisted:
            raise UnsafeOutboundUrlError("仅允许明确配置的内网 HTTP 地址")
        resolved_ips = ()

    for ip_text in resolved_ips:
        addr = _unwrap_ip(ip_text)
        if _is_always_blocked(addr):
            raise UnsafeOutboundUrlError(f"{label} 禁止访问元数据、链路本地或保留地址")
        internal_or_non_public = _is_internal_or_non_public(addr)
        if scheme == "http":
            if not internal_or_non_public or not allowlisted:
                raise UnsafeOutboundUrlError("仅允许明确配置的内网 HTTP 地址")
        elif internal_or_non_public and not allowlisted:
            raise UnsafeOutboundUrlError(f"{label} 禁止访问未放行的内网或保留地址")

    return ValidatedEndpoint(
        url=normalized,
        scheme=scheme,
        host=host,
        port=port,
        pinned_ips=resolved_ips,
    )


class PinnedNetworkBackend(httpcore.SyncBackend):
    def __init__(self, expected_host: str, pinned_ips: tuple[str, ...]):
        super().__init__()
        self.expected_host = normalize_host(expected_host)
        self.pinned_ips = pinned_ips

    def connect_tcp(
        self,
        host: str,
        port: int,
        timeout: float | None = None,
        local_address: str | None = None,
        socket_options=None,
    ) -> httpcore.NetworkStream:
        if normalize_host(host) != self.expected_host or not self.pinned_ips:
            raise httpcore.ConnectError(f"Blocked unexpected outbound host: {host}")
        last_exc: Exception | None = None
        for ip_addr in self.pinned_ips:
            try:
                return super().connect_tcp(
                    ip_addr,
                    port,
                    timeout=timeout,
                    local_address=local_address,
                    socket_options=socket_options,
                )
            except Exception as exc:
                last_exc = exc
        assert last_exc is not None
        raise last_exc


class PinnedHTTPTransport(httpx.HTTPTransport):
    def __init__(self, expected_host: str, pinned_ips: tuple[str, ...]):
        super().__init__(trust_env=False)
        old_pool = self._pool
        ssl_context = getattr(old_pool, "_ssl_context", None)
        old_pool.close()
        self._pool = httpcore.ConnectionPool(
            ssl_context=ssl_context,
            network_backend=PinnedNetworkBackend(expected_host, pinned_ips),
        )
