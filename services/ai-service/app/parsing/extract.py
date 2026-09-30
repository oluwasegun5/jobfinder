"""PDF / DOCX to plain text. The file is untrusted: the format is sniffed from content (never
from a name or declared type) and every dimension is bounded."""

import io
import logging
import zipfile
from typing import Literal, cast

import pdfplumber
from docx import Document
from docx.oxml.table import CT_Tbl
from docx.oxml.text.paragraph import CT_P
from docx.table import Table
from docx.text.paragraph import Paragraph

from app.parsing.errors import ResumeInputError

logger = logging.getLogger(__name__)

# core-api caps uploads at 5 MB; a little headroom so its limit is the one users see.
MAX_FILE_BYTES = 6 * 1024 * 1024
MAX_PDF_PAGES = 15
MAX_TEXT_CHARS = 60_000
# Below this there is nothing to parse (typically a scanned, image-only PDF; OCR is not built yet).
MIN_TEXT_CHARS = 80
_MAX_DOCX_XML_BYTES = 30 * 1024 * 1024
_MAX_ZIP_ENTRIES = 2000

FileFormat = Literal["pdf", "docx"]


def detect_format(data: bytes) -> FileFormat:
    if data.startswith(b"%PDF-"):
        return "pdf"
    if data.startswith(b"PK\x03\x04"):
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                names = set(archive.namelist())
        except zipfile.BadZipFile:
            names = set()
        if "word/document.xml" in names and "word/vbaProject.bin" not in names:
            return "docx"
    raise ResumeInputError(
        "unsupported_file_type", "Only PDF and DOCX files are supported.", status_code=415
    )


def extract_text(data: bytes) -> str:
    """Blocking; call from a worker thread. Raises ResumeInputError."""
    if not data:
        raise ResumeInputError("empty_file", "The file is empty.", status_code=400)
    if len(data) > MAX_FILE_BYTES:
        raise ResumeInputError("file_too_large", "The file is too large.", status_code=413)

    fmt = detect_format(data)
    try:
        text = _pdf_text(data) if fmt == "pdf" else _docx_text(data)
    except ResumeInputError:
        raise
    except Exception as e:  # parser libraries raise many types on corrupt input
        logger.info("could not read %s (%s)", fmt, type(e).__name__)
        raise ResumeInputError("unreadable_file", "The file could not be read.") from e

    text = "\n".join(line.rstrip() for line in text.splitlines()).strip()
    if len(text) < MIN_TEXT_CHARS:
        raise ResumeInputError(
            "no_extractable_text",
            "No text could be extracted; the file may be a scan or an image.",
        )
    if len(text) > MAX_TEXT_CHARS:
        logger.info("CV text truncated (%d chars)", len(text))
        text = text[:MAX_TEXT_CHARS]
    return text


def _pdf_text(data: bytes) -> str:
    parts: list[str] = []
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        for page in pdf.pages[:MAX_PDF_PAGES]:
            parts.append(page.extract_text() or "")
    return "\n".join(parts)


def _docx_text(data: bytes) -> str:
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        infos = archive.infolist()
        # Decompression-bomb guard before python-docx inflates the XML.
        if len(infos) > _MAX_ZIP_ENTRIES or sum(i.file_size for i in infos) > _MAX_DOCX_XML_BYTES:
            raise ResumeInputError("unreadable_file", "The file could not be read.")

    document = Document(io.BytesIO(data))
    lines: list[str] = []
    # Walk the body in order so table content (two-column CV layouts) stays where it appears.
    for child in document.element.body.iterchildren():
        tag = child.tag.rsplit("}", 1)[-1]
        if tag == "p":
            lines.append(Paragraph(cast("CT_P", child), document).text)
        elif tag == "tbl":
            lines.extend(_table_lines(Table(cast("CT_Tbl", child), document)))
    return "\n".join(lines)


def _table_lines(table: Table) -> list[str]:
    lines: list[str] = []
    for row in table.rows:
        seen: set[int] = set()
        for cell in row.cells:
            # Merged cells are returned once per grid column; read each underlying cell once.
            if id(cell._tc) in seen:
                continue
            seen.add(id(cell._tc))
            lines.extend(p.text for p in cell.paragraphs)
    return lines
