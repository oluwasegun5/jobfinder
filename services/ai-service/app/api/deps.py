from typing import Annotated

from fastapi import Depends, Request

from app.config import Settings
from app.llm import LLMProvider


def get_llm_provider(request: Request) -> LLMProvider:
    provider: LLMProvider = request.app.state.llm_provider
    return provider


LLMProviderDep = Annotated[LLMProvider, Depends(get_llm_provider)]


def get_app_settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


SettingsDep = Annotated[Settings, Depends(get_app_settings)]
