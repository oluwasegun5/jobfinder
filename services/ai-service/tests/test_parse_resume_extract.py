import io
import zipfile

import pytest

from app.parsing.errors import ResumeInputError
from app.parsing.extract import MAX_FILE_BYTES, detect_format, extract_text
from tests.fixtures.cvs import ALL_FIXTURES, CAREER_CHANGER, CvFixture
from tests.fixtures.render import render_docx, render_pdf


def _zip(names: list[str]) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name in names:
            archive.writestr(name, "<x/>")
    return buffer.getvalue()


@pytest.mark.parametrize("fixture", ALL_FIXTURES, ids=lambda f: f.name)
def test_extracts_the_text_of_every_fixture(fixture: CvFixture) -> None:
    text = extract_text(fixture.render())
    flat = " ".join(text.split())
    for company in (job["company"] for job in fixture.expected["experience"]):
        assert company in flat
    for skill in fixture.expected["skills"]:
        assert skill in flat
    assert fixture.expected["contact"]["email"] in flat


def test_docx_table_cells_are_read_in_order() -> None:
    text = extract_text(CAREER_CHANGER.render())
    assert text.index("SKILLS") < text.index("Tableau") < text.index("EXPERIENCE")
    assert "Ysgol Bryn Glas" in text


def test_multi_page_pdfs_are_read_in_full() -> None:
    lines = [
        f"Line {i} of a very long curriculum vitae with plenty of words in it" for i in range(120)
    ]
    text = extract_text(render_pdf(list(lines)))
    assert "Line 0 " in text
    assert "Line 119 " in text


def test_pdf_page_count_is_capped() -> None:
    lines = [
        f"Row {i} of a very long curriculum vitae with plenty of words in it"
        for i in range(55 * 16)
    ]
    text = extract_text(render_pdf(list(lines)))
    assert "Row 0 " in text
    assert "Row 879 " not in text  # page 16 is past MAX_PDF_PAGES


def test_format_is_sniffed_from_content() -> None:
    assert detect_format(render_pdf(["hello"])) == "pdf"
    assert detect_format(render_docx(["hello"])) == "docx"


@pytest.mark.parametrize(
    "data",
    [
        b"just some text",
        b"\x89PNG\r\n\x1a\n" + b"\0" * 32,
        b"PK\x03\x04 not really a zip",
        _zip(["hello.txt"]),
        _zip(["word/document.xml", "word/vbaProject.bin"]),  # macro-enabled
    ],
    ids=["text", "png", "broken-zip", "plain-zip", "macros"],
)
def test_other_content_is_unsupported(data: bytes) -> None:
    with pytest.raises(ResumeInputError) as caught:
        extract_text(data)
    assert caught.value.code == "unsupported_file_type"
    assert caught.value.status_code == 415


def test_empty_and_oversized_files_are_refused() -> None:
    with pytest.raises(ResumeInputError) as empty:
        extract_text(b"")
    assert empty.value.code == "empty_file"
    with pytest.raises(ResumeInputError) as big:
        extract_text(b"%PDF-" + b"0" * MAX_FILE_BYTES)
    assert big.value.code == "file_too_large"


def test_corrupt_files_are_reported_as_unreadable() -> None:
    with pytest.raises(ResumeInputError) as pdf:
        extract_text(b"%PDF-1.4\nthis is not a pdf body\n")
    assert pdf.value.code == "unreadable_file"
    with pytest.raises(ResumeInputError) as docx:
        extract_text(_zip(["word/document.xml"]))  # a zip that is not a Word document
    assert docx.value.code == "unreadable_file"


def test_a_zip_with_an_absurd_number_of_entries_is_refused_before_it_is_opened() -> None:
    data = _zip(["word/document.xml", *[f"junk/{i}.txt" for i in range(2500)]])
    with pytest.raises(ResumeInputError) as caught:
        extract_text(data)
    assert caught.value.code == "unreadable_file"


def test_a_file_without_text_is_reported_as_such() -> None:
    with pytest.raises(ResumeInputError) as caught:
        extract_text(render_pdf(["Hi"]))
    assert caught.value.code == "no_extractable_text"
    assert caught.value.status_code == 422
