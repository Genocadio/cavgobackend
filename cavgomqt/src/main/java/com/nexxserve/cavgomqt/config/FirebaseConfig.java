package com.nexxserve.cavgomqt.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * Configuration class for Firebase Admin SDK initialization.
 * Uses GOOGLE_APPLICATION_CREDENTIALS environment variable to locate the service account JSON file.
 */
@Configuration
public class FirebaseConfig {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseConfig.class);

    @Bean
    public FirebaseMessaging firebaseMessaging() {
        try {
            // Check if Firebase is already initialized
            FirebaseApp firebaseApp;
            if (FirebaseApp.getApps().isEmpty()) {
                String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
                
                if (credentialsPath == null || credentialsPath.isEmpty()) {
                    // FCM is optional in deployments that do not use push notifications.
                    // Returning no bean lets FirebaseService start with push disabled.
                    logger.warn("⚠️ GOOGLE_APPLICATION_CREDENTIALS is not set — Firebase push notifications are disabled");
                    return null;
                }

                logger.info("Initializing Firebase Admin SDK with credentials from: {}", credentialsPath);

                FileInputStream serviceAccount = new FileInputStream(credentialsPath);
                GoogleCredentials credentials = GoogleCredentials.fromStream(serviceAccount);
                
                FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(credentials)
                    .build();

                firebaseApp = FirebaseApp.initializeApp(options);
                logger.info("✅ Firebase Admin SDK initialized successfully");
            } else {
                firebaseApp = FirebaseApp.getInstance();
                logger.info("✅ Firebase Admin SDK already initialized, using existing instance");
            }

            return FirebaseMessaging.getInstance(firebaseApp);
        } catch (IOException e) {
            logger.error("❌ Failed to initialize Firebase Admin SDK: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Firebase Admin SDK", e);
        } catch (IllegalStateException e) {
            logger.error("❌ Firebase initialization error: {}", e.getMessage(), e);
            throw e;
        }
    }
}

