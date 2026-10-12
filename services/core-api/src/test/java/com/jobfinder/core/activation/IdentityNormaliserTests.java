package com.jobfinder.core.activation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The claim key of an email, phone and Google account (docs/adr/0042-trial-credit-abuse-gate.md, section 5). */
class IdentityNormaliserTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            // Gmail: dots and +tags go, googlemail is gmail.
            "A.B.C+jobs@Gmail.com, abc@gmail.com",
            "abc@googlemail.com, abc@gmail.com",
            "a.b.c+x+y@googlemail.com, abc@gmail.com",
            "'  abc@gmail.com  ', abc@gmail.com",
            // Every other provider keeps its dots; +tags still go.
            "first.last+tag@outlook.com, first.last@outlook.com",
            "first.last@hotmail.com, first.last@hotmail.com",
            "first.last+x@company.ng, first.last@company.ng",
            // Yahoo: never strip after '-'.
            "first-tag@yahoo.com, first-tag@yahoo.com",
            "first+tag@ymail.com, first@ymail.com",
            // Apple and Proton aliases merge.
            "me+x@me.com, me@icloud.com",
            "me@mac.com, me@icloud.com",
            "me@ICLOUD.com, me@icloud.com",
            "me@protonmail.com, me@proton.me",
            "me@pm.me, me@proton.me",
            "me@protonmail.ch, me@proton.me",
            // Fastmail subdomain addressing.
            "anything@user.fastmail.com, user@fastmail.com",
            "user+x@fastmail.com, user@fastmail.com",
            // Unicode: NFKC and IDNA.
            "ＡＢＣ@gmail.com, abc@gmail.com",
            "kunde@bücher.de, kunde@xn--bcher-kva.de",
            "user@Example.COM., user@example.com"
    })
    void canonicalEmail(String raw, String expected) {
        assertThat(IdentityNormaliser.email(raw)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"no-at-sign", "@gmail.com", "user@", "+tag@gmail.com", "a@b@", "user@exa mple.com"})
    void invalidEmailsHaveNoClaimKey(String raw) {
        assertThat(IdentityNormaliser.email(raw)).isEmpty();
    }

    @Test
    void theDomainIsLowercaseAsciiAndNotMerged() {
        assertThat(IdentityNormaliser.emailDomain("x@Mailinator.COM")).contains("mailinator.com");
        assertThat(IdentityNormaliser.emailDomain("x@googlemail.com")).contains("googlemail.com");
        assertThat(IdentityNormaliser.emailDomain("x@bücher.de")).contains("xn--bcher-kva.de");
        assertThat(IdentityNormaliser.emailDomain("broken")).isEmpty();
    }

    @Test
    void phonesAreE164WithNigeriaAsTheDefaultRegion() {
        assertThat(IdentityNormaliser.phones("0803 123 4567")).containsExactly("+2348031234567");
        assertThat(IdentityNormaliser.phones("+234 (0) 803-123-4567")).containsExactly("+2348031234567");
        assertThat(IdentityNormaliser.phones("+44 20 7946 0958")).containsExactly("+442079460958");
    }

    @Test
    void atMostThreeDistinctValidPhonesAreKept() {
        String text = "0803 123 4567 / 08031234567, 0805 987 6543; 0706 111 2222 | 0909 333 4444 | 12345";
        assertThat(IdentityNormaliser.phones(text))
                .containsExactly("+2348031234567", "+2348059876543", "+2347061112222");
    }

    @Test
    void textWithoutAValidNumberHasNoPhones() {
        assertThat(IdentityNormaliser.phones(null)).isEmpty();
        assertThat(IdentityNormaliser.phones("call me maybe, 12345")).isEmpty();
    }

    @Test
    void aGoogleAccountIsKeyedByIssuerAndSubjectNeverItsEmail() {
        assertThat(IdentityNormaliser.googleSubject("1234567890")).isEqualTo("https://accounts.google.com|1234567890");
        assertThat(IdentityNormaliser.googleSubject(" 42 ")).isEqualTo("https://accounts.google.com|42");
    }

    @Test
    void aFileIsKeyedByTheHexOfItsSha256() {
        byte[] sha = IdentityNormaliser.fileSha256("abc".getBytes(StandardCharsets.UTF_8));
        assertThat(sha).hasSize(32);
        assertThat(IdentityNormaliser.hex(sha))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
