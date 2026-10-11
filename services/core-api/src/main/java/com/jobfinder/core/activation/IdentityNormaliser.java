package com.jobfinder.core.activation;

import java.net.IDN;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;

/**
 * The canonical claim key of an identity value (docs/adr/0042-trial-credit-abuse-gate.md, section 5). Two spellings of
 * one mailbox (dots and {@code +tags} on Gmail, {@code googlemail.com}, Apple and Proton alias domains) get one key.
 * For the trial claim only: login and mail delivery keep the address exactly as the user typed it.
 */
public final class IdentityNormaliser {

    /** Phone numbers without a country code are read as Nigerian. */
    public static final String DEFAULT_PHONE_REGION = "NG";
    /** At most this many numbers are taken from one CV. */
    public static final int MAX_PHONES = 3;

    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final Set<String> GMAIL = Set.of("gmail.com", "googlemail.com");
    private static final Map<String, String> DOMAIN_ALIASES = Map.of(
            "googlemail.com", "gmail.com",
            "me.com", "icloud.com",
            "mac.com", "icloud.com",
            "protonmail.com", "proton.me",
            "protonmail.ch", "proton.me",
            "pm.me", "proton.me");
    private static final String FASTMAIL_SUFFIX = ".fastmail.com";
    private static final int MAX_EMAIL_LENGTH = 320;

    private IdentityNormaliser() {
    }

    /** The claim key of an email address, or empty when it is not a usable address. */
    public static Optional<String> email(String raw) {
        Optional<String[]> parts = split(raw);
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        String local = parts.get()[0];
        String domain = parts.get()[1];
        if (domain.endsWith(FASTMAIL_SUFFIX) && domain.chars().filter(c -> c == '.').count() == 2) {
            // tag@user.fastmail.com is user@fastmail.com.
            local = domain.substring(0, domain.length() - FASTMAIL_SUFFIX.length());
            domain = "fastmail.com";
        }
        int plus = local.indexOf('+');
        if (plus >= 0) {
            local = local.substring(0, plus);
        }
        if (GMAIL.contains(domain)) {
            local = local.replace(".", "");
        }
        domain = DOMAIN_ALIASES.getOrDefault(domain, domain);
        if (local.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(local + "@" + domain);
    }

    /** The domain of an email address, lower-case ASCII (IDNA), without alias merging; for the disposable check. */
    public static Optional<String> emailDomain(String raw) {
        return split(raw).map(parts -> parts[1]);
    }

    private static Optional<String[]> split(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String value = Normalizer.normalize(raw.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.length() > MAX_EMAIL_LENGTH) {
            return Optional.empty();
        }
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1 || value.indexOf('@') != at) {
            return Optional.empty();
        }
        String local = value.substring(0, at);
        String domain = value.substring(at + 1);
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (local.chars().anyMatch(Character::isWhitespace) || domain.isEmpty() || !domain.contains(".")) {
            return Optional.empty();
        }
        try {
            domain = IDN.toASCII(domain, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new String[] {local, domain});
    }

    /**
     * The valid phone numbers in {@code text} as E.164, at most {@link #MAX_PHONES}, in the order they appear, without
     * repeats. Numbers without a country code are read as {@link #DEFAULT_PHONE_REGION}.
     */
    public static List<String> phones(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        PhoneNumberUtil util = PhoneNumberUtil.getInstance();
        Set<String> found = new LinkedHashSet<>();
        for (PhoneNumberMatch match : util.findNumbers(text, DEFAULT_PHONE_REGION, PhoneNumberUtil.Leniency.VALID,
                Long.MAX_VALUE)) {
            if (util.isValidNumber(match.number())) {
                found.add(util.format(match.number(), PhoneNumberFormat.E164));
            }
            if (found.size() == MAX_PHONES) {
                break;
            }
        }
        return new ArrayList<>(found);
    }

    /** The claim key of a Google account: issuer and subject. Never the Google email, which can change. */
    public static String googleSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("A Google subject is required");
        }
        return GOOGLE_ISSUER + "|" + subject.strip();
    }

    /** SHA-256 of a file's bytes (32 bytes). */
    public static byte[] fileSha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The claim key of a file: the lower-case hex of its SHA-256. */
    public static String file(byte[] sha256) {
        if (sha256 == null || sha256.length != 32) {
            throw new IllegalArgumentException("A SHA-256 is 32 bytes");
        }
        return hex(sha256);
    }

    public static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
