package com.javaee.documentservice.service;

import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentQueryDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.vo.DocumentStatsVO;
import com.javaee.documentservice.vo.DocumentVO;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档服务接口
 * 提供文档的创建、更新、删除、查询、版本控制等核心业务功能
 */
public interface DocumentService {

    /**
     * 创建文档
     * @param dto 创建文档请求参数
     * @param userId 创建用户ID
     * @return 文档VO
     */
    DocumentVO create(DocumentCreateDTO dto, Long userId);

    /**
     * 创建文档，文档ID由调用方指定。
     *
     * <p>给重放准备的：同一次调用重来一遍时带同一个ID，撞主键而不是多出一篇。
     * 留空则照常生成。</p>
     */
    DocumentVO create(DocumentCreateDTO dto, Long userId, String requestedId);

    /** 上传文件并建成一篇可编辑文档：归档、解析、建协同状态一次做完，失败不留痕迹。 */
    DocumentVO createFromFile(MultipartFile file, String category, Long userId);

    /**
     * 更新文档
     * @param id 文档ID
     * @param dto 更新文档请求参数
     * @param userId 更新用户ID
     * @return 更新后的文档VO
     */
    DocumentVO update(String id, DocumentUpdateDTO dto, Long userId);

    /**
     * 删除文档（软删除）
     * @param id 文档ID
     * @param userId 删除用户ID
     */
    void delete(String id, Long userId);

    /**
     * 授权用户协作访问文档。
     */
    void grantAccess(String id, Long collaboratorUserId, String role, Long operatorUserId);

    /**
     * 根据ID获取文档详情
     * @param id 文档ID
     * @return 文档VO
     */
    DocumentVO getById(String id, Long userId);

    /**
     * 写权限断言。协同编辑绕过 update()，落地在 collab-server 上，
     * 权限只能在放行前单独查一次。
     */
    void assertCanWrite(String id, Long userId);

    /**
     * 读取协同文档的 CRDT 状态，没有则返回 null。
     */
    byte[] getYdocState(String id, Long userId);

    /**
     * 保存协同文档的 CRDT 状态。
     */
    void saveYdocState(String id, Long userId, byte[] state);

    /**
     * 获取文档在MinIO中的存储位置。
     */
    DocumentVO getStorageLocation(String id, Long userId);

    /**
     * 获取用户的文档列表
     * @param userId 用户ID
     * @return 文档VO列表
     */
    List<DocumentVO> getByUserId(Long userId);

    /**
     * 搜索文档
     * @param dto 查询参数（支持关键词、分类）
     * @return 文档VO列表
     */
    List<DocumentVO> search(DocumentQueryDTO dto, Long userId);

    /**
     * 获取文档的所有版本
     * @param documentId 文档ID
     * @return 文档版本列表
     */
    List<DocumentVersion> getVersions(String documentId, Long userId);

    /**
     * 恢复文档到指定版本
     * @param documentId 文档ID
     * @param versionNumber 版本号
     * @param userId 操作用户ID
     * @return 恢复后的文档VO
     */
    DocumentVO restoreVersion(String documentId, Integer versionNumber, Long userId);

    /** 正文的字符统计，标签由服务端剥离。 */
    DocumentStatsVO getStats(String documentId, Long userId);
}
