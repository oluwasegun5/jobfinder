package com.jobfinder.core.profile.internal;

import java.net.URI;
import java.time.Duration;

import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

@Component
class S3ObjectStorage implements ObjectStorage {

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
                .contentDisposition("attachment").build(), RequestBody.fromBytes(content));
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
    public URI presignDownload(String key, String filename, Duration ttl) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key)
                .responseContentDisposition("attachment; filename=\"" + filename + "\"").build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(ttl).getObjectRequest(get).build()).url().toString());
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
