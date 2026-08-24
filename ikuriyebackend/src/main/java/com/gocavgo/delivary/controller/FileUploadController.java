package com.gocavgo.delivary.controller;

import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * REST controller for file uploads and local file serving.
 * <p>
 * Upload returns only what clients need: <b>mediaId</b>, <b>url</b>, <b>mimeType</b>.
 * Clients never see storage paths, buckets, or backend storage details.
 * <p>
 * When clients create a package, they pass the <b>mediaIds</b> — the backend
 * links them. When fetching packages, media comes back as
 * <code>{ id, url, mimeType }</code>.
 */
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
@Slf4j
public class FileUploadController {

    private final StorageService storageService;
    private final PackageMediaJpaRepository mediaRepo;

    /**
     * Upload a file. Returns a media reference the client can use directly.
     *
     * @return <code>{ mediaId, url, mimeType }</code>
     */
    @PostMapping("/upload")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "purpose", defaultValue = "package-media") String purpose
    ) {
        var userId = SecurityContextHolder.getContext().getAuthentication().getName();
        log.info("Upload: user={}, purpose={}, size={}, type={}",
                userId, purpose, file.getSize(), file.getContentType());

        // Resolve bucket + folder from purpose
        String bucket;
        String folder;
        com.gocavgo.delivary.enums.delivery.MediaType mediaType;
        switch (purpose) {
            case "avatar" -> {
                bucket = "profiles";
                folder = "avatars";
                mediaType = com.gocavgo.delivary.enums.delivery.MediaType.PICTURE;
            }
            default -> {
                bucket = "package-media";
                folder = file.getContentType() != null && file.getContentType().startsWith("video/")
                        ? "videos" : "photos";
                mediaType = file.getContentType() != null && file.getContentType().startsWith("video/")
                        ? com.gocavgo.delivary.enums.delivery.MediaType.VIDEO
                        : com.gocavgo.delivary.enums.delivery.MediaType.PICTURE;
            }
        }

        // Validate file size (50MB max)
        if (file.getSize() > 50 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "File too large. Maximum size is 50MB."
            ));
        }

        // Validate content type
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unsupported file type. Allowed: images and videos."
            ));
        }

        try {
            // Upload to storage (Supabase or local — backend decides)
            var uploadResult = storageService.uploadFile(bucket, folder, file, mimeType);

            // Create a media entity so we have a stable mediaId
            var media = PackageMediaEntity.builder()
                    .packageId(null) // not linked to a package yet
                    .storagePath(uploadResult.storagePath())
                    .bucket(uploadResult.bucket())
                    .storageMode(uploadResult.mode())
                    .url(uploadResult.storagePath()) // backward compat placeholder
                    .mediaType(mediaType)
                    .build();
            media = mediaRepo.save(media);

            // Resolve the URL the client can fetch from
            boolean isLocal = "local".equals(uploadResult.mode());
            String url = storageService.getFileUrl(uploadResult.bucket(), uploadResult.storagePath(), isLocal);

            log.info("Upload complete: mediaId={}, url={}", media.getId(), url != null ? "(resolved)" : "(pending)");

            return ResponseEntity.ok(Map.of(
                    "mediaId", media.getId().toString(),
                    "url", url != null ? url : "",
                    "mimeType", mimeType
            ));
        } catch (Exception e) {
            log.error("Upload failed: user={}, error={}", userId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Upload failed: " + e.getMessage()
            ));
        }
    }

    /**
     * Serve locally stored files. UUID-based paths are non-guessable,
     * so no authentication is required.
     */
    @GetMapping("/local/{bucket}/{folder}/{filename}")
    public ResponseEntity<InputStreamResource> serveLocalFile(
            @PathVariable("bucket") String bucket,
            @PathVariable("folder") String folder,
            @PathVariable("filename") String filename
    ) {
        String storagePath = folder + "/" + filename;
        if (!storageService.localFileExists(bucket, storagePath)) {
            return ResponseEntity.notFound().build();
        }

        try {
            InputStream inputStream = storageService.getLocalFileStream(bucket, storagePath);
            String contentType = storageService.getLocalFileContentType(storagePath);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\"" + filename + "\"")
                    .body(new InputStreamResource(inputStream));
        } catch (Exception e) {
            log.error("Failed to serve local file: {}/{}: {}", bucket, storagePath, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    private static final java.util.Set<String> ALLOWED_MIME_TYPES = java.util.Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/heic",
            "video/mp4", "video/quicktime", "video/webm"
    );
}
