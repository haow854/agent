package com.javaee.documentservice.client;

import com.javaee.fileservice.service.FileService;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * In-process adapter for the file functionality used by document management.
 */
@Component
public class FileServiceClient {

    private final FileService fileService;

    public FileServiceClient(FileService fileService) {
        this.fileService = fileService;
    }

    public String upload(MultipartFile file) {
        return fileService.upload(file);
    }

    /** 文档的原始文件随文档一起删。文档的写权限由调用方查过。 */
    public void delete(String fileId) {
        fileService.deleteStored(fileId);
    }
}
