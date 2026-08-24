package com.gocavgo.delivary.service.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dual-mode file storage service — supports local disk storage and Supabase Storage.
 * <p>
 * When Supabase is configured and reachable, files are uploaded to Supabase and
 * clients receive signed URLs. When Supabase is not configured or unavailable,
 * files are stored locally on disk and served via a direct REST endpoint.
 * <p>
 * If Supabase comes back online after being unavailable, a background migration
 * moves local files to Supabase automatically. Files already in Supabase remain
 * there regardless of availability — cached signed URLs are returned.
 * <p>
 * Clients never see Supabase URLs, bucket names, or storage backends. They only
 * receive a URL they can fetch the file from.
 */
@Service
@Slf4j
public class StorageService {

    private static final long SIGNED_URL_EXPIRY_SECONDS = 604800; // 7 days

    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${supabase.service-key:}")
    private String supabaseServiceKey;

    @Value("${storage.local.base-path:./storage}")
    private String localStorageBasePath;

    @Value("${storage.migration.enabled:true}")
    private boolean migrationEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // Cache for Supabase signed URLs
    private final ConcurrentHashMap<String, SignedUrlCache> signedUrlCache = new ConcurrentHashMap<>();

    // Track Supabase availability (set by health check / failed requests)
    private volatile boolean supabaseAvailable = false;
    private volatile Instant lastSupabaseCheck = Instant.MIN;

    // ── Mode Detection ───────────────────────────────────────────────────

    /**
     * Returns true if Supabase is configured (env vars present).
     */
    public boolean isSupabaseConfigured() {
        return supabaseUrl != null && !supabaseUrl.isBlank()
                && supabaseServiceKey != null && !supabaseServiceKey.isBlank();
    }

    /**
     * Returns true if Supabase is configured AND currently reachable.
     * Falls back to local storage when this returns false.
     */
    public boolean isSupabaseAvailable() {
        if (!isSupabaseConfigured()) return false;
        return supabaseAvailable;
    }

    /**
     * Returns the active storage mode for logging/display.
     */
    public String getActiveMode() {
        if (isSupabaseAvailable()) return "supabase";
        if (isSupabaseConfigured()) return "supabase (unavailable — using local fallback)";
        return "local";
    }

    // ── Upload ───────────────────────────────────────────────────────────

    /**
     * Upload a file. Tries Supabase first if configured and available;
     * falls back to local disk storage otherwise.
     *
     * @param bucket   the storage bucket (e.g. "package-media", "profiles")
     * @param folder   subfolder (e.g. "photos", "avatars")
     * @param file     the multipart file
     * @param mimeType the MIME type
     * @return a {@link UploadResult} with the storage path, bucket, mode, and whether it's local
     */
    public UploadResult uploadFile(String bucket, String folder, MultipartFile file, String mimeType) throws IOException {
        String extension = getExtension(file.getOriginalFilename(), mimeType);
        String storagePath = folder + "/" + UUID.randomUUID() + "." + extension;

        // Try Supabase if available
        if (isSupabaseAvailable()) {
            try {
                uploadToSupabase(bucket, storagePath, file, mimeType);
                log.info("Upload to Supabase succeeded: bucket={}, path={}, size={}", bucket, storagePath, file.getSize());
                return new UploadResult(storagePath, bucket, "supabase", false);
            } catch (Exception e) {
                log.warn("Supabase upload failed, falling back to local storage: {}", e.getMessage());
                supabaseAvailable = false;
                lastSupabaseCheck = Instant.now();
            }
        }

        // Local storage fallback
        uploadToLocal(bucket, storagePath, file);
        log.info("Upload to local storage: bucket={}, path={}, size={}", bucket, storagePath, file.getSize());
        return new UploadResult(storagePath, bucket, "local", true);
    }

    // ── URL Resolution ───────────────────────────────────────────────────

    /**
     * Get a fetchable URL for a stored file. For Supabase files, returns a
     * signed URL (cached). For local files, returns a direct REST URL.
     *
     * @param bucket       the storage bucket
     * @param storagePath  the storage path
     * @param isLocal      whether the file is stored locally
     * @return a URL the client can fetch, or null if the file can't be resolved
     */
    public String getFileUrl(String bucket, String storagePath, boolean isLocal) {
        if (storagePath == null || storagePath.isBlank()) return null;

        if (isLocal) {
            return buildLocalFileUrl(bucket, storagePath);
        }

        // Supabase file — try to generate signed URL
        return getSignedUrl(bucket, storagePath);
    }

    /**
     * Build the REST URL for a locally stored file.
     */
    public String buildLocalFileUrl(String bucket, String storagePath) {
        // The URL pattern matches the local file serving endpoint
        return "/api/files/local/" + bucket + "/" + storagePath;
    }

    /**
     * Get the actual disk path for a locally stored file.
     */
    public Path getLocalFilePath(String bucket, String storagePath) {
        return Paths.get(localStorageBasePath, bucket, storagePath).normalize();
    }

    /**
     * Check if a local file exists on disk.
     */
    public boolean localFileExists(String bucket, String storagePath) {
        return Files.exists(getLocalFilePath(bucket, storagePath));
    }

    /**
     * Get an InputStream for a local file (for serving to clients).
     */
    public InputStream getLocalFileStream(String bucket, String storagePath) throws IOException {
        Path path = getLocalFilePath(bucket, storagePath);
        if (!Files.exists(path)) {
            throw new IOException("File not found: " + storagePath);
        }
        return Files.newInputStream(path);
    }

    /**
     * Get the content type for a local file based on its extension.
     */
    public String getLocalFileContentType(String storagePath) {
        if (storagePath == null) return "application/octet-stream";
        String ext = storagePath.contains(".") ? storagePath.substring(storagePath.lastIndexOf('.') + 1).toLowerCase() : "";
        return switch (ext) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "heic" -> "image/heic";
            case "mp4" -> "video/mp4";
            case "mov" -> "video/quicktime";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }

    // ── Migration: Local → Supabase ──────────────────────────────────────

    /**
     * Migrate a local file to Supabase. Returns true on success.
     * Used by the background migration service.
     */
    public boolean migrateLocalToSupabase(String bucket, String storagePath) {
        if (!isSupabaseAvailable()) {
            log.debug("Cannot migrate — Supabase not available");
            return false;
        }

        Path localPath = getLocalFilePath(bucket, storagePath);
        if (!Files.exists(localPath)) {
            log.warn("Cannot migrate — local file not found: {}", localPath);
            return false;
        }

        try {
            byte[] bytes = Files.readAllBytes(localPath);
            String mimeType = getLocalFileContentType(storagePath);

            String url = supabaseUrl.replaceAll("/+$", "")
                    + "/storage/v1/object/" + bucket + "/" + storagePath;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("Content-Type", mimeType)
                    .header("x-upsert", "true")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Delete local file after successful migration
                Files.deleteIfExists(localPath);
                log.info("Migrated local file to Supabase: bucket={}, path={}", bucket, storagePath);
                return true;
            }

            log.error("Migration upload to Supabase failed: status={}, body={}", response.statusCode(), response.body());
            return false;
        } catch (Exception e) {
            log.error("Migration failed for {}/{}: {}", bucket, storagePath, e.getMessage());
            return false;
        }
    }

    // ── Supabase Health Check ────────────────────────────────────────────

    /**
     * Periodic health check to see if Supabase is available.
     * Runs every 60 seconds when Supabase is configured.
     */
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void checkSupabaseHealth() {
        if (!isSupabaseConfigured()) return;

        try {
            String url = supabaseUrl.replaceAll("/+$", "") + "/storage/v1/bucket";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .GET()
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean wasAvailable = supabaseAvailable;
            supabaseAvailable = response.statusCode() >= 200 && response.statusCode() < 300;

            if (!wasAvailable && supabaseAvailable) {
                log.info("Supabase is back online! Migration of local files will begin.");
            } else if (wasAvailable && !supabaseAvailable) {
                log.warn("Supabase went offline. New uploads will use local storage.");
            }
        } catch (Exception e) {
            if (supabaseAvailable) {
                log.warn("Supabase health check failed: {}", e.getMessage());
            }
            supabaseAvailable = false;
        }
        lastSupabaseCheck = Instant.now();
    }

    // ── Private: Supabase Operations ─────────────────────────────────────

    private void uploadToSupabase(String bucket, String storagePath, MultipartFile file, String mimeType) throws IOException {
        String url = supabaseUrl.replaceAll("/+$", "")
                + "/storage/v1/object/" + bucket + "/" + storagePath;

        try {
            byte[] bytes = file.getBytes();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("Content-Type", mimeType)
                    .header("x-upsert", "true")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Supabase upload failed with status " + response.statusCode() + ": " + response.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted", e);
        }
    }

    String getSignedUrl(String bucket, String storagePath) {
        if (storagePath == null || storagePath.isBlank()) return null;

        String cacheKey = bucket + ":" + storagePath;
        SignedUrlCache cached = signedUrlCache.get(cacheKey);

        // Return cached URL if it's still valid (with 5-minute buffer)
        if (cached != null && cached.expiresAt > System.currentTimeMillis() + 300_000) {
            return cached.url;
        }

        // If Supabase is not available, return null (caller should use local URL)
        if (!isSupabaseAvailable()) {
            // Try to return expired cached URL as best-effort
            if (cached != null) {
                log.debug("Returning expired cached signed URL for {}/{}", bucket, storagePath);
                return cached.url;
            }
            return null;
        }

        // Generate fresh signed URL from Supabase
        String url = supabaseUrl.replaceAll("/+$", "")
                + "/storage/v1/object/sign/" + bucket + "/" + storagePath;

        String jsonBody = "{\"expiresIn\":" + SIGNED_URL_EXPIRY_SECONDS + "}";

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + supabaseServiceKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String signedUrl = parseSignedUrl(response.body());
                if (signedUrl != null) {
                    long expiresAt = System.currentTimeMillis() + (SIGNED_URL_EXPIRY_SECONDS * 1000);
                    signedUrlCache.put(cacheKey, new SignedUrlCache(signedUrl, expiresAt));
                    return signedUrl;
                }
            }

            log.error("Signed URL generation failed: status={}, body={}", response.statusCode(), response.body());
            return null;
        } catch (Exception e) {
            log.error("Failed to generate signed URL for {}: {}", storagePath, e.getMessage());
            return null;
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────

    /**
     * Delete a file from wherever it is stored (Supabase or local disk).
     * Best-effort: failures are logged but don't throw.
     */
    public void deleteFile(String bucket, String storagePath, String storageMode) {
        if (storagePath == null || bucket == null) return;

        if ("local".equals(storageMode)) {
            try {
                Path path = getLocalFilePath(bucket, storagePath);
                Files.deleteIfExists(path);
                log.info("Deleted local file: {}/{}", bucket, storagePath);
            } catch (Exception e) {
                log.warn("Failed to delete local file {}/{}: {}", bucket, storagePath, e.getMessage());
            }
        } else if (isSupabaseAvailable() || isSupabaseConfigured()) {
            try {
                String url = supabaseUrl.replaceAll("/+$", "")
                        + "/storage/v1/object/" + bucket + "/" + storagePath;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + supabaseServiceKey)
                        .DELETE()
                        .timeout(Duration.ofSeconds(15))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    log.info("Deleted Supabase file: {}/{}", bucket, storagePath);
                } else {
                    log.warn("Supabase delete returned {}: {}", response.statusCode(), response.body());
                }
            } catch (Exception e) {
                log.warn("Failed to delete Supabase file {}/{}: {}", bucket, storagePath, e.getMessage());
            }
        }
        // Also purge from signed URL cache
        signedUrlCache.remove(bucket + ":" + storagePath);
    }

    // ── Private: Local Storage Operations ────────────────────────────────

    private void uploadToLocal(String bucket, String storagePath, MultipartFile file) throws IOException {
        Path targetPath = getLocalFilePath(bucket, storagePath);
        Files.createDirectories(targetPath.getParent());
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private String parseSignedUrl(String json) {
        int idx = json.indexOf("\"signedUrl\"");
        if (idx < 0) idx = json.indexOf("\"signed_url\"");
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx);
        int startQuote = json.indexOf('\"', colonIdx + 1);
        int endQuote = json.indexOf('\"', startQuote + 1);

        if (startQuote < 0 || endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private String getExtension(String filename, String mimeType) {
        if (filename != null && filename.contains(".")) {
            return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        }
        if (mimeType != null && mimeType.contains("/")) {
            return mimeType.substring(mimeType.indexOf('/') + 1).toLowerCase();
        }
        return "bin";
    }

    // ── Types ────────────────────────────────────────────────────────────

    public record UploadResult(String storagePath, String bucket, String mode, boolean local) {}

    private record SignedUrlCache(String url, long expiresAt) {}
}
