import pytest

from app.config import settings


@pytest.fixture(autouse=True)
def ai_test_settings(monkeypatch):
    monkeypatch.setattr(settings, "ai_provider", "mock")
    monkeypatch.setattr(settings, "ai_internal_token", "test-internal-token")
    monkeypatch.setattr(settings, "feature_ai_writeback", False)
