package com.javaee.documentservice.service.impl;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.utils.UserBucketUtils;
import com.javaee.common.security.BucketPermissionService;
import com.javaee.documentservice.service.DocumentContentService;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档内容服务实现类
 * 将文档内容存储到MinIO
 */
@Service
public class DocumentContentServiceImpl implements DocumentContentService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentContentServiceImpl.class);

    @Autowired
    private MinioClient minioClient;

    @Autowired
    private BucketPermissionService bucketPermissionService;

    /**
     * 获取文档内容在MinIO中的存储键
     */
    @Override
    public String getObjectName(String documentId) {
        return "document-content/" + documentId + ".md";
    }

    @Override
    public String getBucketName(Long userId) {
        return UserBucketUtils.bucketNameForUser(userId);
    }

    @Override
    public boolean saveContent(String documentId, Long userId, String content) {
        String bucketName = getBucketName(userId);
        bucketPermissionService.assertCanAccess(bucketName);
        return saveContent(documentId, bucketName, content);
    }

    @Override
    public boolean saveContent(String documentId, String bucketName, String content) {
        try {
            validateBucketName(bucketName);
            ensureBucketExists(bucketName);
            String contentKey = getObjectName(documentId);
            byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
            
            logger.info("Saving document content to MinIO, bucket: {}, key: {}, size: {} bytes", 
                    bucketName, contentKey, contentBytes.length);
            
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(contentKey)
                            .stream(new java.io.ByteArrayInputStream(contentBytes), contentBytes.length, -1)
                            .contentType("text/markdown; charset=UTF-8")
                            .build()
            );
            logger.info("Document content saved successfully, documentId: {}", documentId);
            return true;
        } catch (Exception e) {
            logger.error("Failed to save document content to MinIO, documentId: {}", documentId, e);
            throw new BusinessException("文档内容保存失败: " + e.getMessage());
        }
    }

    @Override
    public String getContent(String documentId, Long userId) {
        String bucketName = getBucketName(userId);
        bucketPermissionService.assertCanAccess(bucketName);
        return getContent(documentId, bucketName);
    }

    @Override
    public String getContent(String documentId, String bucketName) {
        try {
            validateBucketName(bucketName);
            String contentKey = getObjectName(documentId);
            logger.info("Getting document content from MinIO, bucket: {}, key: {}", bucketName, contentKey);
            
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(contentKey)
                            .build()
            )) {
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                logger.info("Document content retrieved successfully, documentId: {}, size: {} bytes", 
                        documentId, content.length());
                return content;
            }
        } catch (Exception e) {
            logger.warn("Failed to get document content from MinIO, documentId: {}, error: {}", documentId, e.getMessage());
            return null;
        }
    }

    @Override
    public long getContentSize(String documentId, String bucketName) {
        try {
            validateBucketName(bucketName);
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(getObjectName(documentId))
                            .build()
            );
            return stat.size();
        } catch (Exception e) {
            logger.debug("Failed to stat document content, documentId: {}, error: {}",
                    documentId, e.getMessage());
            return -1L;
        }
    }

    @Override
    public void deleteContent(String documentId, String bucketName) {
        validateBucketName(bucketName);
        List<String> keys = new ArrayList<>(List.of(getObjectName(documentId), ydocObjectName(documentId)));
        try {
            for (Result<Item> version : minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName).prefix(versionPrefix(documentId)).recursive(true).build())) {
                keys.add(version.get().objectName());
            }
        } catch (Exception e) {
            if (!isMissingStorageObject(e)) {
                throw new BusinessException("文档内容删除失败: " + e.getMessage());
            }
        }
        for (String key : keys) {
            try {
                minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucketName).object(key).build());
            } catch (Exception e) {
                // 已经不在就是要的结果：删到一半失败后重删，前面删掉的那些会落到这里
                if (!isMissingStorageObject(e)) {
                    throw new BusinessException("文档内容删除失败: " + e.getMessage());
                }
            }
        }
        logger.info("Document content deleted, documentId: {}, bucket: {}, objects: {}", documentId, bucketName, keys.size());
    }

    @Override
    public void saveVersionContent(String documentId, String versionId, String bucketName, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        try {
            validateBucketName(bucketName);
            ensureBucketExists(bucketName);
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(versionObjectName(documentId, versionId))
                    .stream(new java.io.ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType("text/markdown; charset=UTF-8")
                    .build());
        } catch (Exception e) {
            throw new BusinessException("版本正文保存失败: " + e.getMessage());
        }
    }

    @Override
    public String getVersionContent(String documentId, String versionId, String bucketName) {
        try {
            validateBucketName(bucketName);
            try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(bucketName)
                    .object(versionObjectName(documentId, versionId))
                    .build())) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            throw new BusinessException("版本正文读取失败: " + e.getMessage());
        }
    }

    private String versionPrefix(String documentId) {
        return "document-versions/" + documentId + "/";
    }

    private String versionObjectName(String documentId, String versionId) {
        return versionPrefix(documentId) + versionId + ".md";
    }

    @Override
    public boolean saveYdocState(String documentId, String bucketName, byte[] state) {
        try {
            validateBucketName(bucketName);
            ensureBucketExists(bucketName);
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(ydocObjectName(documentId))
                            .stream(new java.io.ByteArrayInputStream(state), state.length, -1)
                            .contentType("application/octet-stream")
                            .build()
            );
            logger.debug("Ydoc state saved, documentId: {}, size: {} bytes", documentId, state.length);
            return true;
        } catch (Exception e) {
            logger.error("Failed to save ydoc state, documentId: {}", documentId, e);
            throw new BusinessException("协同状态保存失败: " + e.getMessage());
        }
    }

    @Override
    public byte[] getYdocState(String documentId, String bucketName) {
        try {
            validateBucketName(bucketName);
            try (InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(ydocObjectName(documentId))
                            .build()
            )) {
                return inputStream.readAllBytes();
            }
        } catch (Exception e) {
            // 旧文档没存过二进制，调用方据此回退到用 HTML 灌种一次
            logger.debug("No ydoc state for documentId: {}, {}", documentId, e.getMessage());
            return null;
        }
    }

    private String ydocObjectName(String documentId) {
        return "document-content/" + documentId + ".ydoc";
    }

    @Override
    public boolean updateContent(String documentId, Long userId, String content) {
        return saveContent(documentId, userId, content);
    }

    @Override
    public boolean updateContent(String documentId, String bucketName, String content) {
        return saveContent(documentId, bucketName, content);
    }

    /**
     * 确保存储桶存在
     */
    private void ensureBucketExists(String bucketName) throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
        if (!exists) {
            logger.info("Bucket {} does not exist, creating...", bucketName);
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
            logger.info("Bucket {} created successfully", bucketName);
        }
    }

    private boolean isMissingStorageObject(Exception e) {
        if (e instanceof ErrorResponseException errorResponseException) {
            String code = errorResponseException.errorResponse().code();
            return "NoSuchBucket".equals(code)
                    || "NoSuchKey".equals(code)
                    || "NoSuchObject".equals(code);
        }
        String message = e.getMessage();
        return message != null
                && (message.contains("The specified bucket does not exist")
                || message.contains("The specified key does not exist")
                || message.contains("Object does not exist"));
    }

    private void validateBucketName(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new BusinessException("存储桶名称不能为空");
        }
    }
}
