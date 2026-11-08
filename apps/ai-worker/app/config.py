from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    log_level: str = "INFO"
    ai_provider: Literal["disabled", "mock", "openai_compatible"] = "disabled"
    ai_internal_token: str = ""
    ai_base_url: str = ""
    ai_api_key: str = ""
    ai_model: str = ""
    ai_timeout_seconds: float = 30
    feature_ai_writeback: bool = False
    feature_real_dify: bool = False
    ai_dify_allow_http: bool = False


settings = Settings()
