package com.jobfinder.core.storage.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ContentDispositionTests {

    @Test
    void aPlainNameIsAQuotedAttachment() {
        assertThat(S3ObjectStorage.attachment("Ann_Lee_Resume.pdf")).isEqualTo("attachment; filename=\"Ann_Lee_Resume.pdf\"");
    }

    @Test
    void quotesBackslashesSemicolonsAndControlCharactersCannotBreakTheHeader() {
        String header = S3ObjectStorage.attachment("a\"b\\c;d\r\ne\u0000f/g.pdf");
        assertThat(header).doesNotContain("\r").doesNotContain("\n").doesNotContain("\u0000");
        assertThat(header).isEqualTo("attachment; filename=\"a_b_c_d__e_f_g.pdf\"");
    }

    @Test
    void anAccentedNameGetsAnAsciiFallbackAndAnEncodedExactName() {
        assertThat(S3ObjectStorage.attachment("Résumé ọ.pdf"))
                .isEqualTo("attachment; filename=\"R_sum_ _.pdf\"; filename*=UTF-8''R%C3%A9sum%C3%A9%20%E1%BB%8D.pdf");
        assertThat(S3ObjectStorage.attachment(null)).isEqualTo("attachment; filename=\"download\"");
    }
}
