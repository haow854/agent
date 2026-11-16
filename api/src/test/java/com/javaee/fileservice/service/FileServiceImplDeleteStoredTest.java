package com.javaee.fileservice.service;

import com.javaee.fileservice.entity.FileMetadata;
import com.javaee.fileservice.mapper.FileMetadataMapper;
import com.javaee.fileservice.service.impl.FileServiceImpl;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FileServiceImplDeleteStoredTest {

    private FileMetadataMapper metadataMapper;
    private MinioClient minio;
    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        metadataMapper = mock(FileMetadataMapper.class);
        minio = mock(MinioClient.class);
        fileService = new FileServiceImpl();
        ReflectionTestUtils.setField(fileService, "fileMetadataMapper", metadataMapper);
        ReflectionTestUtils.setField(fileService, "minioClient", minio);
    }

    /** 桶取自文件自己的元数据，不取当前请求的用户：Agent 删除时没有这个用户。 */
    @Test
    void deletesFromTheBucketRecordedForTheFile() throws Exception {
        FileMetadata metadata = new FileMetadata();
        metadata.setId("meta-1");
        metadata.setFileId("file-1");
        metadata.setStorageType("minio");
        metadata.setBucketName("user-3");
        metadata.setObjectKey("file-1.md");
        when(metadataMapper.selectByFileId("file-1")).thenReturn(metadata);

        fileService.deleteStored("file-1");

        verify(minio).removeObject(argThat((RemoveObjectArgs args) ->
                args.bucket().equals("user-3") && args.object().equals("file-1.md")));
        verify(metadataMapper).deleteById("meta-1");
    }

    @Test
    void aFileThatIsAlreadyGoneIsLeftAlone() {
        fileService.deleteStored("file-gone");

        verifyNoInteractions(minio);
    }
}
