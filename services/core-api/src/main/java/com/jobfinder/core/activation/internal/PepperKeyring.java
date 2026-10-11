package com.jobfinder.core.activation.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import com.jobfinder.core.activation.DigestSet;
import com.jobfinder.core.activation.IdentityDigest;
import com.jobfinder.core.activation.IdentityDigester;
import com.jobfinder.core.activation.ValueType;

/**
 * The keyring of peppers ({@code TRIAL_PEPPER_V1}, {@code TRIAL_PEPPER_V2}, ...): new claims are written under the
 * current version, lookups use every version present, so a pepper can be rotated without losing the claims stored under
 * the old one. Retire a version by removing its variable once its last claim has expired.
 */
final class PepperKeyring implements IdentityDigester {

    static final String VARIABLE_PREFIX = "TRIAL_PEPPER_V";
    static final int MIN_PEPPER_BYTES = 32;
    private static final Pattern VARIABLE = Pattern.compile("^" + VARIABLE_PREFIX + "(\\d{1,4})$");
    private static final String ALGORITHM = "HmacSHA256";
    private static final byte SEPARATOR = 0x1F;
    private static final Logger log = LoggerFactory.getLogger(PepperKeyring.class);

    private final short current;
    /** Current version first, then the others newest first. */
    private final List<Map.Entry<Short, SecretKeySpec>> keys;

    PepperKeyring(Map<Short, byte[]> peppers, short current) {
        if (!peppers.containsKey(current)) {
            throw new IllegalStateException("No pepper for the current version: set " + VARIABLE_PREFIX + current);
        }
        TreeMap<Short, SecretKeySpec> sorted = new TreeMap<>(Comparator.reverseOrder());
        peppers.forEach((version, pepper) -> {
            if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
                throw new IllegalStateException(VARIABLE_PREFIX + version + " must be at least " + MIN_PEPPER_BYTES
                        + " bytes");
            }
            sorted.put(version, new SecretKeySpec(pepper, ALGORITHM));
        });
        List<Map.Entry<Short, SecretKeySpec>> ordered = new ArrayList<>();
        ordered.add(Map.entry(current, sorted.get(current)));
        sorted.forEach((version, key) -> {
            if (version != current) {
                ordered.add(Map.entry(version, key));
            }
        });
        this.current = current;
        this.keys = List.copyOf(ordered);
    }

    /**
     * Reads every {@code TRIAL_PEPPER_V<n>} the environment has. Under the {@code prod} profile a missing or short
     * current pepper stops the application; elsewhere a missing one is replaced by a random pepper for this process only
     * (the gate then forgets its claims on restart, which is fine for local development and never for production).
     */
    static PepperKeyring fromEnvironment(Environment environment, short current) {
        Map<Short, byte[]> peppers = new TreeMap<>();
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (var source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (String name : enumerable.getPropertyNames()) {
                        Matcher m = VARIABLE.matcher(name);
                        String value = m.matches() ? environment.getProperty(name) : null;
                        if (value != null && !value.isBlank()) {
                            peppers.putIfAbsent(Short.parseShort(m.group(1)), value.getBytes(StandardCharsets.UTF_8));
                        }
                    }
                }
            }
        }
        if (!peppers.containsKey(current)) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("The trial gate needs its pepper: set " + VARIABLE_PREFIX + current
                        + " (at least " + MIN_PEPPER_BYTES + " random bytes) in the environment");
            }
            byte[] random = new byte[MIN_PEPPER_BYTES];
            new SecureRandom().nextBytes(random);
            peppers.put(current, random);
            log.warn("{}{} is not set: using a random pepper for this process; trial claims will not survive a restart",
                    VARIABLE_PREFIX, current);
        }
        return new PepperKeyring(peppers, current);
    }

    @Override
    public short currentVersion() {
        return current;
    }

    @Override
    public DigestSet digest(ValueType type, String canonical) {
        byte[] message = message(type, canonical);
        List<IdentityDigest> digests = new ArrayList<>(keys.size());
        for (Map.Entry<Short, SecretKeySpec> key : keys) {
            digests.add(new IdentityDigest(key.getKey(), hmac(key.getValue(), message)));
        }
        return new DigestSet(type, digests);
    }

    private static byte[] message(ValueType type, String canonical) {
        byte[] prefix = type.name().getBytes(StandardCharsets.US_ASCII);
        byte[] value = canonical.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + 1 + value.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        message[prefix.length] = SEPARATOR;
        System.arraycopy(value, 0, message, prefix.length + 1, value.length);
        return message;
    }

    private static byte[] hmac(SecretKeySpec key, byte[] message) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", e);
        }
    }
}
