package com.gocavgo.delivary.service.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MinIO / S3-compatible object storage backend.
 * <p>
 * Configured with {@code MINIO_ENDPOINT}, {@code MINIO_ACCESS_KEY} and
 * {@code MINIO_SECRET_KEY}. When {@code MINIO_PUBLIC_ENDPOINT} is set, signed
 * download URLs are generated for that externally reachable host instead of the
 * internal container hostname (e.g. {@code http://minio:9000}).
 * <p>
 * Buckets are created lazily (and re-checked by the periodic health probe) so a
 * fresh MinIO instance needs no manual setup.
 */
@Service
@Slf4j
public class MinioStorageService {

    private static final List<String> DEFAULT_BUCKETS = List.of("package-media", "profiles");
    private static final long URL_CACHE_BUFFER_SECONDS = 300;

    @Value("${minio.enabled:true}")
    private boolean enabled;

    @Value("${minio.endpoint:}")
    private String endpoint;

    @Value("${minio.public-endpoint:}")
    private String publicEndpoint;

    @Value("${minio.access-key:}")
    private String accessKey;

    @Value("${minio.secret-key:}")
    private String secretKey;

    @Value("${minio.region:us-east-1}")
    private String region;

    @Value("${minio.presigned-expiry-seconds:604800}")
    private int presignedExpirySeconds;

    private volatile boolean available = false;
    private volatile MinioClient client;
    private volatile MinioClient publicClient;
    private volatile String publicClientEndpoint;

    private final ConcurrentHashMap<String, SignedUrlCache> signedUrlCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> ensuredBuckets = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.info("MinIO storage is not configured — uploads will use Supabase or local storage");
            return;
        }
        // Best-effort only: never block or fail application startup.
        checkHealth();
    }

    // ── Mode detection ───────────────────────────────────────────────────

    /** True when the MinIO endpoint and credentials are present. */
    public boolean isConfigured() {
        return enabled
                && notBlank(endpoint)
                && notBlank(accessKey)
                && notBlank(secretKey);
    }

    /** True when MinIO is configured and the last health probe succeeded. */
    public boolean isAvailable() {
        return isConfigured() && available;
    }

    public String getEndpoint() {
        return endpoint;
    }

    // ── Upload ───────────────────────────────────────────────────────────

    /**
     * Upload a multipart file to MinIO. The bucket is created automatically on
     * first use.
     *
     * @throws IOException when MinIO is unreachable or rejects the upload
     */
    public void upload(String bucket, String storagePath, MultipartFile file, String mimeType) throws IOException {
        if (!isConfigured()) {
            throw new IOException("MinIO is not configured");
        }
        try (InputStream in = file.getInputStream()) {
            putObject(bucket, storagePath, in, file.getSize(), mimeType);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            markUnavailable("upload", e);
            throw new IOException("MinIO upload failed: " + e.getMessage(), e);
        }
    }

    /**
     * Upload a local file to MinIO (used by the local → remote migration job).
     *
     * @return true when the object was stored successfully
     */
    public boolean uploadLocalFile(String bucket, String storagePath, Path localPath, String mimeType) {
        if (!isConfigured()) return false;
        if (!Files.exists(localPath)) {
            log.warn("Cannot migrate to MinIO — local file not found: {}", localPath);
            return false;
        }
        try (InputStream in = Files.newInputStream(localPath)) {
            putObject(bucket, storagePath, in, Files.size(localPath), mimeType);
            return true;
        } catch (Exception e) {
            markUnavailable("migration upload", e);
            log.error("Migration upload to MinIO failed for {}/{}: {}", bucket, storagePath, e.getMessage());
            return false;
        }
    }

    private void putObject(String bucket, String storagePath, InputStream in, long size, String mimeType) throws Exception {
        ensureBucket(bucket);
        client().putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(storagePath)
                .stream(in, size, -1)
                .contentType(mimeType != null ? mimeType : "application/octet-stream")
                .build());
        available = true;
    }

    // ── Download URL ─────────────────────────────────────────────────────

    /**
     * Return a presigned GET URL for an object. URLs are cached until shortly
     * before they expire.
     *
     * @return a signed URL, or null when MinIO is not reachable/configured
     */
    public String getPresignedUrl(String bucket, String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return null;
        if (!isConfigured()) return null;

        String cacheKey = bucket + ":" + storagePath;
        SignedUrlCache cached = signedUrlCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now + (URL_CACHE_BUFFER_SECONDS * 1000)) {
            return cached.url;
        }

        if (!isAvailable()) {
            // Best effort: an expired URL may still work for a short while.
            if (cached != null) return cached.url;
            return null;
        }

        try {
            String url = publicClient().getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(storagePath)
                    .expiry(presignedExpirySeconds, TimeUnit.SECONDS)
                    .build());

            long expiresAt = now + (presignedExpirySeconds * 1000L);
            signedUrlCache.put(cacheKey, new SignedUrlCache(url, expiresAt));
            return url;
        } catch (Exception e) {
            markUnavailable("presign", e);
            log.error("Failed to generate MinIO signed URL for {}/{}: {}", bucket, storagePath, e.getMessage());
            return cached != null ? cached.url : null;
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────

    /** Best-effort object delete. Failures are logged, never thrown. */
    public void delete(String bucket, String storagePath) {
        signedUrlCache.remove(bucket + ":" + storagePath);
        if (!isConfigured()) return;

        try {
            client().removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(storagePath)
                    .build());
            log.info("Deleted MinIO object: {}/{}", bucket, storagePath);
        } catch (Exception e) {
            log.warn("Failed to delete MinIO object {}/{}: {}", bucket, storagePath, e.getMessage());
        }
    }

    // ── Health ───────────────────────────────────────────────────────────

    /**
     * Health probe every 60 seconds. Also creates the default buckets when a
     * fresh MinIO instance becomes reachable.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void checkHealth() {
        if (!isConfigured()) {
            available = false;
            return;
        }

        boolean wasAvailable = available;
        try {
            // Re-validate bucket existence on each probe so a bucket deleted
            // out-of-band is recreated, then relax to the cached fast path for
            // uploads between probes.
            ensuredBuckets.clear();
            ensureBuckets();
            available = true;
            if (!wasAvailable) {
                log.info("MinIO is available at {} — uploads will be stored there", endpoint);
            }
        } catch (Exception e) {
            available = false;
            if (wasAvailable) {
                log.warn("MinIO health check failed — new uploads will fall back to Supabase/local: {}", e.getMessage());
            } else {
                log.debug("MinIO not available yet: {}", e.getMessage());
            }
        }
    }

    private void ensureBuckets() throws Exception {
        for (String bucket : DEFAULT_BUCKETS) {
            ensureBucket(bucket);
        }
    }

    private void ensureBucket(String bucket) throws Exception {
        if (bucket == null || bucket.isBlank()) return;
        if (Boolean.TRUE.equals(ensuredBuckets.get(bucket))) return;

        MinioClient c = client();
        boolean exists = c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
        if (!exists) {
            try {
                c.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("Created MinIO bucket: {}", bucket);
            } catch (Exception e) {
                // Another instance may have created it concurrently.
                if (!c.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    throw e;
                }
            }
        }
        ensuredBuckets.put(bucket, true);
    }

    // ── Internals ────────────────────────────────────────────────────────

    private MinioClient client() {
        MinioClient current = client;
        if (current == null) {
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = MinioClient.builder()
                            .endpoint(endpoint)
                            .credentials(accessKey, secretKey)
                            .region(region)
                            .build();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * Client used only for signing download URLs. When MINIO_PUBLIC_ENDPOINT is
     * set (e.g. https://files.example.com) the signature is valid for clients
     * outside the Docker network; otherwise the internal endpoint is used.
     */
    private MinioClient publicClient() {
        String target = notBlank(publicEndpoint)
                ? publicEndpoint.trim()
                : endpoint.trim();

        MinioClient current = publicClient;
        if (current == null || !target.equals(publicClientEndpoint)) {
            synchronized (this) {
                if (publicClient == null || !target.equals(publicClientEndpoint)) {
                    publicClient = MinioClient.builder()
                            .endpoint(target)
                            .credentials(accessKey, secretKey)
                            .region(region)
                            .build();
                    publicClientEndpoint = target;
                }
                current = publicClient;
            }
        }
        return current;
    }

    private void markUnavailable(String operation, Exception e) {
        boolean wasAvailable = available;
        available = false;
        if (wasAvailable) {
            log.warn("MinIO {} failed — marking storage unavailable until the next health probe: {}",
                    operation, e.getMessage());
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private record SignedUrlCache(String url, long expiresAt) {}
}
