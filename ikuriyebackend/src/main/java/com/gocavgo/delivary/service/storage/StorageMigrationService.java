package com.gocavgo.delivary.service.storage;

import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Background service that migrates locally-stored files to the active remote
 * storage backend (MinIO first, then Supabase) when it becomes available. Runs
 * periodically and processes files in batches.
 * <p>
 * Only runs when:
 * <ul>
 *   <li>a remote backend (MinIO or Supabase) is configured</li>
 *   <li>that backend is currently available</li>
 *   <li>migration is enabled in config</li>
 * </ul>
 * <p>
 * Files already stored remotely are never touched. If the remote backend goes
 * offline mid-migration, partially-migrated files remain in the remote store
 * (already uploaded) and the local copy is only deleted after a successful
 * upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageMigrationService {

    private final StorageService storageService;
    private final PackageMediaJpaRepository mediaRepo;
    private final UserRepository userRepository;

    @Value("${storage.migration.enabled:true}")
    private boolean migrationEnabled;

    private static final int BATCH_SIZE = 20;

    /**
     * Run every 5 minutes. Migrates a batch of local package media files to the
     * active remote backend.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void migratePackageMedia() {
        if (!shouldMigrate()) return;

        List<PackageMediaEntity> localMedia = mediaRepo.findByStorageMode("local");
        if (localMedia.isEmpty()) {
            log.debug("No local package media files to migrate");
            return;
        }

        log.info("Starting package media migration: {} local files found, target={}",
                localMedia.size(), storageService.getActiveRemoteMode());

        int migrated = 0;
        int failed = 0;
        int skipped = 0;

        List<PackageMediaEntity> toProcess = localMedia.stream()
                .limit(BATCH_SIZE)
                .toList();

        for (var media : toProcess) {
            if (media.getStoragePath() == null || media.getBucket() == null) {
                skipped++;
                continue;
            }

            // Check if file actually exists locally
            if (!storageService.localFileExists(media.getBucket(), media.getStoragePath())) {
                // File already gone — mark it with the active remote mode (it was
                // migrated previously or never existed locally).
                String remoteMode = storageService.getActiveRemoteMode();
                if (remoteMode != null) {
                    media.setStorageMode(remoteMode);
                    mediaRepo.save(media);
                }
                skipped++;
                continue;
            }

            try {
                String remoteMode = storageService.getActiveRemoteMode();
                boolean success = storageService.migrateLocalToRemote(media.getBucket(), media.getStoragePath());
                if (success && remoteMode != null) {
                    media.setStorageMode(remoteMode);
                    // Update the stored url to point at the remote signed URL
                    String remoteUrl = storageService.getFileUrl(
                            media.getBucket(), media.getStoragePath(), remoteMode);
                    if (remoteUrl != null) {
                        media.setUrl(remoteUrl);
                    }
                    mediaRepo.save(media);
                    migrated++;
                } else if (success) {
                    // Upload succeeded but the backend became unavailable before
                    // the mode was recorded; the next batch will reconcile it.
                    migrated++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Migration failed for media id={}: {}", media.getId(), e.getMessage());
                failed++;
            }
        }

        log.info("Package media migration batch complete: migrated={}, failed={}, skipped={}, remaining={}",
                migrated, failed, skipped, Math.max(0, localMedia.size() - BATCH_SIZE));
    }

    /**
     * Run every 5 minutes (offset by 30s from package media). Migrates user avatars.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 90000)
    public void migrateUserAvatars() {
        if (!shouldMigrate()) return;

        List<UserEntity> localAvatars = userRepository.findByAvatarStorageMode("local");
        if (localAvatars.isEmpty()) {
            log.debug("No local user avatars to migrate");
            return;
        }

        log.info("Starting avatar migration: {} local avatars found, target={}",
                localAvatars.size(), storageService.getActiveRemoteMode());

        int migrated = 0;
        int failed = 0;

        List<UserEntity> toProcess = localAvatars.stream()
                .limit(BATCH_SIZE)
                .toList();

        for (var user : toProcess) {
            if (user.getAvatarStoragePath() == null || user.getAvatarBucket() == null) {
                continue;
            }

            if (!storageService.localFileExists(user.getAvatarBucket(), user.getAvatarStoragePath())) {
                String remoteMode = storageService.getActiveRemoteMode();
                if (remoteMode != null) {
                    user.setAvatarStorageMode(remoteMode);
                    userRepository.save(user);
                }
                continue;
            }

            try {
                String remoteMode = storageService.getActiveRemoteMode();
                boolean success = storageService.migrateLocalToRemote(
                        user.getAvatarBucket(), user.getAvatarStoragePath());
                if (success && remoteMode != null) {
                    user.setAvatarStorageMode(remoteMode);
                    userRepository.save(user);
                    migrated++;
                } else if (success) {
                    migrated++;
                } else {
                    failed++;
                }
            } catch (Exception e) {
                log.error("Avatar migration failed for user id={}: {}", user.getId(), e.getMessage());
                failed++;
            }
        }

        log.info("Avatar migration batch complete: migrated={}, failed={}, remaining={}",
                migrated, failed, Math.max(0, localAvatars.size() - BATCH_SIZE));
    }

    private boolean shouldMigrate() {
        return migrationEnabled && storageService.isRemoteAvailable();
    }
}
