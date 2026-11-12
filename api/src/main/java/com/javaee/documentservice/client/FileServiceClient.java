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

    public void delete(String fileId) {
        fileService.delete(fileId);
    }
}
