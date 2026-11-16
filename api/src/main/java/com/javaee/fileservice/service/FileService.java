package com.javaee.fileservice.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * 文件服务接口
 */
public interface FileService {

    /**
     * 单文件上传
     */
    String upload(MultipartFile file);

    /**
     * 保存服务端生成的文件内容，并创建可通过 fileId 下载的元数据记录。
     */
    String store(String fileName, byte[] content, String contentType, String createdBy);

    /**
     * 与 {@link #store} 相同，但由调用方指定 fileId。传入同一个 fileId 重复调用是幂等的：
     * 覆盖同一个存储对象，不会产生第二份文件或第二条元数据。fileId 为空时退化为随机生成。
     */
    String store(String fileName, byte[] content, String contentType, String createdBy, String fileId);

    /**
     * 多文件上传
     */
    String[] uploadMultiple(MultipartFile[] files);

    /**
     * 分片上传
     */
    void uploadChunk(MultipartFile chunk, String fileId, int chunkIndex, int totalChunks);

    /**
     * 分片合并
     */
    String mergeChunk(String fileId, String fileName);

    /**
     * 文件下载
     */
    byte[] download(String fileId);

    /**
     * 文件删除
     */
    void delete(String fileId);

    /**
     * 删除文件，按元数据里记的桶和对象删，不看当前请求的用户：调用方已经自己授权过（比如
     * 文档服务按文档的写权限）。{@link #delete} 的桶和权限都取自当前请求的用户，Agent 在
     * 后台线程里执行时没有这个用户，协作者删别人的文档时桶也不对。文件已经不在时什么都不做。
     */
    void deleteStored(String fileId);

    /**
     * 文件重命名
     */
    void rename(String fileId, String newName);

    /**
     * 文件移动
     */
    void move(String fileId, String targetPath);

    /**
     * 文件复制
     */
    String copy(String fileId, String targetPath);

}
