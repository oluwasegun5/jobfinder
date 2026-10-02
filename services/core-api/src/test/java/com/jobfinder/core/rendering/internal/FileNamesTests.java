package com.jobfinder.core.rendering.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class FileNamesTests {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]+\\.(pdf|docx)");

    @Test
    void aNameAndACompanyMakeTheDocumentedShape() {
        assertThat(FileNames.of("Segun Adeyemi", "Acme Corp", "pdf")).isEqualTo("Segun_Adeyemi_Resume_Acme_Corp.pdf");
        assertThat(FileNames.of("Segun Adeyemi", null, "DOCX")).isEqualTo("Segun_Adeyemi_Resume.docx");
        assertThat(FileNames.of(null, "", "pdf")).isEqualTo("Resume.pdf");
    }

    @Test
    void accentsAreFoldedNotDropped() {
        assertThat(FileNames.of("Ṣọlá Ọ̀gúnlẹ́yẹ", "Ìbàdàn Payments Ltd", "pdf"))
                .isEqualTo("Sola_Ogunleye_Resume_Ibadan_Payments_Ltd.pdf");
        assertThat(FileNames.part("Björk Øster Łukasz Straße")).isEqualTo("Bjork_Oster_Lukasz_Strasse");
    }

    static Stream<String> hostile() {
        return Stream.of("../../etc/passwd", "..\\..\\windows\\system32", "evil\r\nSet-Cookie: session=1",
                "quote\" ; filename=\"x.exe", "name\u0000.pdf", "\u202Egnp.exe", "a/b/c", "%2e%2e%2f", "$(rm -rf /)",
                "<script>alert(1)</script>", "  ", "\u4F60\u597D", "CON", "x".repeat(500),
                "\uD83D\uDE80\uD83D\uDE80");
    }

    @ParameterizedTest
    @MethodSource("hostile")
    void hostileNamesAndCompaniesAlwaysGiveAPlainSafeFileName(String hostile) {
        for (String name : new String[] { FileNames.of(hostile, "Acme", "pdf"), FileNames.of("Ann Lee", hostile, "docx"),
                FileNames.of(hostile, hostile, "pdf") }) {
            assertThat(name).matches(SAFE).hasSizeLessThanOrEqualTo(30 + 30 + 20 + 10);
            assertThat(name).doesNotContain("..").doesNotStartWith(".").doesNotStartWith("-");
        }
    }

    @Test
    void aNameWithNoLatinLettersLeavesJustTheResumeWord() {
        assertThat(FileNames.of("你好", "会社", "pdf")).isEqualTo("Resume.pdf");
    }
}
