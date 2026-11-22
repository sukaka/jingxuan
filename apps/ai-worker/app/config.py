from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    log_level: str = "INFO"
    ai_provider: Literal["disabled", "mock", "openai_compatible"] = "disabled"
    ai_internal_token: str = ""
    ai_base_url: str = ""
    ai_api_key: str = ""
    ai_model: str = ""
    ai_timeout_seconds: float = Field(default=30, gt=0, le=300)
    ai_allow_http: bool = False
    ai_allowed_hosts: str = ""
    feature_ai_writeback: bool = False
    feature_real_dify: bool = False
    ai_dify_allow_http: bool = False
    ai_dify_allowed_hosts: str = ""


settings = Settings()
