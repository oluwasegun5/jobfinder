package com.jobfinder.core.storage.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.jobfinder.core.storage.ObjectNotFoundException;
import com.jobfinder.core.storage.ObjectStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
class S3ObjectStorage implements ObjectStorage {

    /** The longest a signed link may live (ASVS V12.5: files are served through short-lived signed URLs only). */
    static final Duration MAX_LINK_TTL = Duration.ofMinutes(15);

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    S3ObjectStorage(S3Client s3, S3Presigner presigner, StorageProperties properties) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                .contentDisposition("attachment").cacheControl("private, no-store").build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        try {
            return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
        } catch (NoSuchKeyException e) {
            throw new ObjectNotFoundException(key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public URI presignDownload(String key, String filename, Duration ttl) {
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_LINK_TTL) > 0) {
            throw new IllegalArgumentException("A download link must live between a moment and " + MAX_LINK_TTL);
        }
        // The type is forced from what we stored, with an attachment disposition, whatever the object's own metadata says.
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key)
                .responseContentDisposition(attachment(filename)).responseContentType(contentTypeOf(key))
                .responseCacheControl("private, no-store").build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get).build()).url().toString());
    }

    /** The only types we store; anything else is served as opaque bytes. */
    static String contentTypeOf(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (lower.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        return "application/octet-stream";
    }

    /**
     * An RFC 6266 attachment header that cannot be broken out of: quotes, backslashes and control characters never
     * reach the header; a plain ASCII fallback is always sent and the exact name travels as {@code filename*}.
     */
    static String attachment(String filename) {
        String name = filename == null || filename.isBlank() ? "download" : filename;
        StringBuilder ascii = new StringBuilder();
        StringBuilder encoded = new StringBuilder();
        name.codePoints().forEach(cp -> {
            boolean unsafe = Character.isISOControl(cp) || cp == '"' || cp == '\\' || cp == '/' || cp == '%'
                    || cp == ';';
            if (unsafe) {
                ascii.append('_');
                encoded.append('_');
            } else if (cp < 0x7f && cp >= 0x20) {
                ascii.append((char) cp);
                encoded.append((char) cp);
            } else {
                ascii.append('_');
                for (byte b : new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    encoded.append('%').append(String.format("%02X", b));
                }
            }
        });
        String disposition = "attachment; filename=\"" + ascii + "\"";
        return ascii.toString().contentEquals(encoded)
                ? disposition : disposition + "; filename*=UTF-8''" + encoded.toString().replace(" ", "%20");
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deleteByPrefix(String prefix) {
        s3.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                .forEach(page -> {
                    if (page.contents().isEmpty()) {
                        return;
                    }
                    var identifiers = page.contents().stream()
                            .map(object -> ObjectIdentifier.builder().key(object.key()).build()).toList();
                    var result = s3.deleteObjects(DeleteObjectsRequest.builder().bucket(bucket)
                            .delete(Delete.builder().objects(identifiers).build()).build());
                    if (result.hasErrors()) {
                        throw new IllegalStateException(
                                "Could not delete " + result.errors().size() + " object(s) under " + prefix);
                    }
                });
    }
}
