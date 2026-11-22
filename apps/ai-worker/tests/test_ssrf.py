import json
import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpcore
import httpx
import pytest
from pydantic import ValidationError

from app.config import settings
from app.schemas import (
    AiProviderConfig,
    ExtractionRequest,
    KnowledgeDatasetConfig,
    KnowledgeDocumentDisableRequest,
    ReplyGenerationRequest,
)
from app.services.extractor import AiExtractionError, OpenAICompatibleExtractor
from app.services.knowledge import AiKnowledgeError, DifyKnowledgeAdapter
from app.services.knowledge_dataset import DifyDatasetAdapter
from app.services.reply import AiReplyError, OpenAICompatibleReplyGenerator
from app.services.ssrf import PinnedNetworkBackend, UnsafeOutboundUrlError, validate_outbound_base_url


def _dns(ip: str):
    family = socket.AF_INET6 if ":" in ip else socket.AF_INET
    return lambda host, port: [(family, socket.SOCK_STREAM, 6, "", (ip, port))]


@pytest.mark.parametrize("url", [
    "http://public.example.com/v1",
    "ftp://provider.example.com/v1",
    "https://user:secret@provider.example.com/v1",
    "https://provider.example.com:0/v1",
    "https://provider.example.com/v1?",
    "https://provider.example.com/v1#",
    "https://provider.example.com/v1?token=leak",
    "https://provider.example.com/v1#frag",
    "https://127.0.0.1/v1",
    "https://[::1]/v1",
    "https://169.254.169.254/latest/meta-data",
    "https://[fe80::1]/v1",
    "https://10.0.0.1/v1",
    "https://172.16.0.1/v1",
    "https://192.168.1.1/v1",
    "https://100.64.0.1/v1",
    "https://0.0.0.0/v1",
    "https://[::]/v1",
    "https://224.0.0.1/v1",
    "https://[ff02::1]/v1",
    "https://240.0.0.1/v1",
    "https://[fc00::1]/v1",
    "https://[fd12:3456::1]/v1",
    "https://[::ffff:169.254.169.254]/v1",
    "https://[64:ff9b::127.0.0.1]/v1",
    "https://[2002:7f00:1::]/v1",
    "https://metadata.google.internal/computeMetadata/v1",
])
def test_ssrf_blocks_http_and_internal_or_reserved_ips(url):
    with pytest.raises(UnsafeOutboundUrlError):
        validate_outbound_base_url(url, allow_http=False, allowed_hosts="")


@pytest.mark.parametrize("resolved_ip", [
    "127.0.0.1",
    "169.254.169.254",
    "10.1.2.3",
    "172.20.0.1",
    "192.168.10.20",
    "100.64.1.1",
    "224.0.0.1",
    "fc00::1",
    "64:ff9b::127.0.0.1",
])
def test_https_dns_resolution_blocks_non_public_targets_without_allowlist(resolved_ip):
    with pytest.raises(UnsafeOutboundUrlError):
        validate_outbound_base_url(
            "https://rebind.example.test/v1",
            allow_http=False,
            allowed_hosts="",
            resolver=_dns(resolved_ip),
        )


def test_allowlist_permits_explicit_internal_host_but_never_metadata_or_public_http():
    endpoint = validate_outbound_base_url(
        "https://dify.internal/v1",
        allow_http=False,
        allowed_hosts="dify.internal",
        resolver=_dns("10.10.0.5"),
    )
    assert endpoint.pinned_ips == ("10.10.0.5",)

    http_endpoint = validate_outbound_base_url(
        "http://127.0.0.1:8080/v1/",
        allow_http=True,
        allowed_hosts="127.0.0.1",
    )
    assert http_endpoint.url == "http://127.0.0.1:8080/v1"
    assert http_endpoint.pinned_ips == ("127.0.0.1",)

    for dangerous in ("169.254.169.254", "0.0.0.0", "224.0.0.1", "metadata.google.internal"):
        with pytest.raises(UnsafeOutboundUrlError):
            validate_outbound_base_url(
                f"https://{dangerous}/v1",
                allow_http=True,
                allowed_hosts=dangerous,
                resolver=_dns("169.254.169.254" if dangerous == "metadata.google.internal" else dangerous),
            )

    with pytest.raises(UnsafeOutboundUrlError):
        validate_outbound_base_url(
            "http://8.8.8.8/v1",
            allow_http=True,
            allowed_hosts="8.8.8.8",
        )


def test_pinned_network_backend_connects_only_to_pinned_ip_and_blocks_unexpected_host():
    received_hosts = []

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            received_hosts.append(self.headers.get("Host"))
            body = json.dumps({
                "choices": [{
                    "message": {
                        "content": json.dumps({
                            "insight_type": "APPOINTMENT_INTENT",
                            "customer_id": "C00128",
                            "project_codes": [],
                            "mentioned_institution": None,
                            "mentioned_time": None,
                            "absolute_time": None,
                            "evidence_message_ids": ["msg_001"],
                            "confidence": 0.8,
                            "proposed_actions": ["VERIFY_BALANCE"],
                            "fact_status": "UNVERIFIED",
                            "allow_financial_write": False,
                            "reasoning_summary": "客户表达预约意向",
                            "reply_draft": "为您人工核实",
                            "followup_draft": None,
                            "risk_flags": [],
                        })
                    }
                }]
            }).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    calls = 0

    def single_use_dns(host: str, req_port: int):
        nonlocal calls
        calls += 1
        assert calls == 1
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("127.0.0.1", req_port))]

    try:
        old_allow_http = settings.ai_allow_http
        old_allowed = settings.ai_allowed_hosts
        settings.ai_allow_http = True
        settings.ai_allowed_hosts = "rebind.internal"
        try:
            extractor = OpenAICompatibleExtractor(
                f"http://rebind.internal:{port}/v1",
                "test-key",
                "test-model",
                resolver=single_use_dns,
            )
            req = ExtractionRequest.model_validate({
                "job_id": "job-1",
                "customer_id": "C00128",
                "messages": [{"id": "msg_001", "sender_type": "CUSTOMER", "sent_at": "2026-10-26T08:00:00Z", "text": "想预约"}],
            })
            insight, _, _ = extractor.extract(req)
            assert insight.insight_type == "APPOINTMENT_INTENT"
            assert calls == 1
            assert received_hosts == [f"rebind.internal:{port}"]
        finally:
            settings.ai_allow_http = old_allow_http
            settings.ai_allowed_hosts = old_allowed

        backend = PinnedNetworkBackend("rebind.internal", ("127.0.0.1",))
        with pytest.raises(httpcore.ConnectError):
            backend.connect_tcp("evil.example.com", port)
    finally:
        server.shutdown()
        server.server_close()


def test_adapters_reject_redirects_and_ssrf_targets(monkeypatch):
    monkeypatch.setattr(settings, "ai_allow_http", False)
    monkeypatch.setattr(settings, "feature_real_dify", True)

    req_extract = ExtractionRequest.model_validate({
        "job_id": "job-1",
        "customer_id": "C00128",
        "messages": [{"id": "msg_001", "sender_type": "CUSTOMER", "sent_at": "2026-10-26T08:00:00Z", "text": "想预约"}],
    })
    with pytest.raises(AiExtractionError) as ext_err:
        OpenAICompatibleExtractor("http://model.local/v1", "k", "m", transport=httpx.MockTransport(lambda _: httpx.Response(200))).extract(req_extract)
    assert ext_err.value.code == "AI_PROVIDER_NOT_CONFIGURED"

    redirect_transport = httpx.MockTransport(lambda _: httpx.Response(302, headers={"Location": "http://169.254.169.254/latest"}))
    with pytest.raises(AiExtractionError) as ext_redir:
        OpenAICompatibleExtractor("https://model.example.test/v1", "k", "m", transport=redirect_transport).extract(req_extract)
    assert ext_redir.value.code == "AI_PROVIDER_FAILED"

    req_reply = ReplyGenerationRequest.model_validate({
        "suggestion_id": "s1",
        "customer_id": "128",
        "scene": "INITIAL",
        "facts": {"customer_name": "测试"},
    })
    with pytest.raises(AiReplyError) as rep_err:
        OpenAICompatibleReplyGenerator("https://169.254.169.254/v1", "k", "m", transport=httpx.MockTransport(lambda _: httpx.Response(200))).generate(req_reply)
    assert rep_err.value.code == "AI_PROVIDER_NOT_CONFIGURED"
    with pytest.raises(AiReplyError) as rep_redir:
        OpenAICompatibleReplyGenerator("https://model.example.test/v1", "k", "m", transport=redirect_transport).generate(req_reply)
    assert rep_redir.value.code == "AI_PROVIDER_FAILED"

    ds_cfg = KnowledgeDatasetConfig.model_validate({
        "provider": "dify",
        "base_url": "https://dify.example.test/v1",
        "api_key": "k",
        "dataset_id": "ds-1",
        "timeout_seconds": 10,
    })
    with pytest.raises(AiKnowledgeError) as ds_redir:
        DifyDatasetAdapter(ds_cfg, transport=redirect_transport).disable(KnowledgeDocumentDisableRequest(document_id="doc-1", dataset_config=ds_cfg))
    assert ds_redir.value.code == "AI_PROVIDER_FAILED"


def test_ai_provider_config_schema_enforces_bounds():
    with pytest.raises(ValidationError):
        AiProviderConfig.model_validate({
            "provider": "openai_compatible",
            "base_url": "https://example.com/" + "a" * 1020,
            "api_key": "k",
            "model": "m",
        })
    with pytest.raises(ValidationError):
        AiProviderConfig.model_validate({
            "provider": "openai_compatible",
            "base_url": "https://example.com/v1",
            "api_key": "k" * 513,
            "model": "m",
        })
    with pytest.raises(ValidationError):
        AiProviderConfig.model_validate({
            "provider": "openai_compatible",
            "base_url": "https://example.com/v1",
            "api_key": "k",
            "model": "m" * 129,
        })
    with pytest.raises(ValidationError):
        AiProviderConfig.model_validate({
            "provider": "openai_compatible",
            "base_url": "https://example.com/v1",
            "api_key": "k",
            "model": "m",
            "timeout_seconds": 301,
        })
