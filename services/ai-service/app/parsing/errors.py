"""Problems with the uploaded file itself (as opposed to LLM failures, see app.llm.base)."""


class ResumeInputError(Exception):
    """The file cannot be parsed. `code` is a stable identifier core-api stores as the reason."""

    def __init__(self, code: str, message: str, *, status_code: int = 422) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
