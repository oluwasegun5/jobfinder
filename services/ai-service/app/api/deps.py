from typing import Annotated

from fastapi import Depends, Request

from app.llm import LLMProvider


def get_llm_provider(request: Request) -> LLMProvider:
    provider: LLMProvider = request.app.state.llm_provider
    return provider


LLMProviderDep = Annotated[LLMProvider, Depends(get_llm_provider)]
