package com.gocavgo.delivary.service.storage;

import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.repository.delivery.PackageMediaJpaRepository;
import com.gocavgo.delivary.repository.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Background service that migrates locally-stored files to Supabase Storage
 * when it becomes available. Runs periodically and processes files in batches.
 * <p>
 * Only runs when:
 * <ul>
 *   <li>Supabase is configured</li>
 *   <li>Supabase is currently available</li>
 *   <li>Migration is enabled in config</li>
 * </ul>
 * <p>
 * Already-in-Supabase files are never touched. If Supabase goes offline again
 * mid-migration, partially-migrated files remain in Supabase (already uploaded)
 * and the local copy is only deleted after a successful upload.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageMigrationService {

    private final StorageService storageService;
    private final PackageMediaJpaRepository mediaRepo;
    private final UserRepository userRepository;

    private static final int BATCH_SIZE = 20;

    /**
     * Run every 5 minutes. Migrates a batch of local package media files to Supabase.
     */
    @Scheduled(fixedDelay = 300000, initialDelay = 60000)
    public void migratePackageMedia() {
        if (!shouldMigrate()) return;

        List<PackageMediaEntity> localMedia = mediaRepo.findByStorageMode("local");
        if (localMedia.isEmpty()) {
            log.debug("No local package media files to migrate");
            return;
        }

        log.info("Starting package media migration: {} local files found", localMedia.size());

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
                // File already gone — mark as supabase (it was migrated or never local)
                media.setStorageMode("supabase");
                mediaRepo.save(media);
                skipped++;
                continue;
            }

            try {
                boolean success = storageService.migrateLocalToSupabase(media.getBucket(), media.getStoragePath());
                if (success) {
                    media.setStorageMode("supabase");
                    mediaRepo.save(media);
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

        log.info("Starting avatar migration: {} local avatars found", localAvatars.size());

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
                user.setAvatarStorageMode("supabase");
                userRepository.save(user);
                continue;
            }

            try {
                boolean success = storageService.migrateLocalToSupabase(user.getAvatarBucket(), user.getAvatarStoragePath());
                if (success) {
                    user.setAvatarStorageMode("supabase");
                    userRepository.save(user);
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
        return storageService.isSupabaseConfigured()
                && storageService.isSupabaseAvailable();
    }
}
