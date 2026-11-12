package com.javaee.fileservice.config;

import com.javaee.common.security.RequestUserContext;
import com.javaee.common.utils.UserBucketUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * 文件存储通用配置类
 */
@Configuration
public class FileStorageConfig {

    @Autowired
    private RequestUserContext requestUserContext;

    @Value("${file.storage.type}")
    private String storageType;

    @Value("${file.storage.local-path}")
    private String localPath;

    @Value("${file.storage.max-size}")
    private long maxSize;

    @Value("${minio.bucket-name}")
    private String bucketName;

    public String getStorageType() {
        return storageType;
    }

    public String getLocalPath() {
        return localPath;
    }

    public long getMaxSize() {
        return maxSize;
    }

    public String getBucketName() {
        return currentUserId()
                .map(UserBucketUtils::bucketNameForUser)
                .orElse(bucketName);
    }

    public String getConfiguredBucketName() {
        return bucketName;
    }

    private Optional<Long> currentUserId() {
        return requestUserContext.getCurrentUserId();
    }

}
