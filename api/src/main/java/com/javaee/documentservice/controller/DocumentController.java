package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentQueryDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.common.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentStatsVO;
import com.javaee.documentservice.vo.DocumentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理控制器
 * 提供文档的CRUD和版本控制REST API接口
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "文档创建、更新、删除、查询、版本控制等接口")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private RequestUserContext requestUserContext;

    /**
     * 创建文档
     * @param dto 创建文档请求参数
     * @return 文档VO
     */
    @PostMapping
    @Operation(summary = "创建文档", description = "创建新文档，自动保存初始版本")
    public Result<DocumentVO> create(@RequestBody DocumentCreateDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.create(dto, userId);
        return Result.success(document);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文件建文档",
            description = "原件归档、解析、建协同状态一次做完；任一步失败则整体不留痕迹")
    public Result<DocumentVO> upload(
            @Parameter(description = "文档文件") @RequestParam("file") MultipartFile file,
            @Parameter(description = "分类") @RequestParam(defaultValue = "default") String category) {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.createFromFile(file, category, userId));
    }

    /**
     * 更新文档
     * @param id 文档ID
     * @param dto 更新文档请求参数
     * @return 更新后的文档VO
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新文档", description = "更新文档内容，自动保存历史版本")
    public Result<DocumentVO> update(
            @Parameter(description = "文档ID") @PathVariable String id,
            @RequestBody DocumentUpdateDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.update(id, dto, userId);
        return Result.success(document);
    }

    /**
     * 删除文档
     * @param id 文档ID
     * @return 无
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档", description = "软删除文档，将文档状态标记为已删除")
    public Result<Void> delete(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        documentService.delete(id, userId);
        return Result.success();
    }

    /**
     * 添加文档协作者
     * @param id 文档ID
     * @param collaboratorUserId 协作者用户ID
     * @param role 协作角色
     * @return 无
     */
    @PostMapping("/{id}/collaborators/{collaboratorUserId}")
    @Operation(summary = "添加文档协作者", description = "为文档授权协作者，角色支持 owner/editor/viewer")
    public Result<Void> grantAccess(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "协作者用户ID") @PathVariable Long collaboratorUserId,
            @Parameter(description = "协作角色") @RequestParam(defaultValue = "editor") String role) {
        Long userId = requestUserContext.getRequiredUserId();
        documentService.grantAccess(id, collaboratorUserId, role, userId);
        return Result.success();
    }

    /**
     * 获取文档详情
     * @param id 文档ID
     * @return 文档VO
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取文档详情", description = "根据文档ID获取文档详细信息")
    public Result<DocumentVO> getById(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.getById(id, userId);
        return Result.success(document);
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "获取文档字符统计", description = "统计正文的字数、中英文、数字、标点、段落数")
    public Result<DocumentStatsVO> getStats(@Parameter(description = "文档ID") @PathVariable String id) {
        return Result.success(
                documentService.getStats(id, requestUserContext.getRequiredUserId()));
    }

    /**
     * 获取文档MinIO存储位置
     * @param id 文档ID
     * @return 文档存储位置
     */
    @GetMapping("/{id}/storage")
    @Operation(summary = "获取文档存储位置", description = "根据文档ID获取其MinIO bucket/objectName，供AI文件操作使用")
    public Result<DocumentVO> getStorageLocation(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.getStorageLocation(id, userId);
        return Result.success(document);
    }

    /**
     * 协同状态存取。只有 collab-server 会调这两个，走的是当次用户的令牌，
     * 所以文档授权规则和其他接口完全一致。
     *
     * <p>返回裸字节而不是 Result 包装：它是 CRDT 二进制，没有可读结构可言，
     * 而且 404 本身就是调用方需要的信号——表示这是个还没存过二进制的旧文档，
     * 该回退到用 HTML 灌种一次。</p>
     */
    @GetMapping(value = "/{id}/ydoc", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "获取协同状态", description = "读取文档的 Y.Doc 二进制状态，未保存过返回 404")
    public ResponseEntity<byte[]> getYdocState(@Parameter(description = "文档ID") @PathVariable String id) {
        byte[] state = documentService.getYdocState(id, requestUserContext.getRequiredUserId());
        return state == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(state);
    }

    @PutMapping(value = "/{id}/ydoc", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @Operation(summary = "保存协同状态", description = "保存文档的 Y.Doc 二进制状态")
    public Result<Void> saveYdocState(@Parameter(description = "文档ID") @PathVariable String id,
                                      @RequestBody byte[] state) {
        documentService.saveYdocState(id, requestUserContext.getRequiredUserId(), state);
        return Result.success();
    }

    /**
     * 获取用户文档列表
     * @param userId 用户ID
     * @return 文档VO列表
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "获取用户文档列表", description = "获取指定用户的所有活跃文档列表")
    public Result<List<DocumentVO>> getByUserId(@Parameter(description = "用户ID") @PathVariable Long userId) {
        Long currentUserId = requestUserContext.getRequiredUserId();
        if (!currentUserId.equals(userId)) {
            throw new com.javaee.common.exception.BusinessException("无权查看其他用户的文档");
        }
        List<DocumentVO> documents = documentService.getByUserId(currentUserId);
        return Result.success(documents);
    }

    /**
     * 搜索文档
     * @param keyword 关键词（可选）
     * @param category 分类（可选）
     * @return 文档VO列表
     */
    @GetMapping("/search")
    @Operation(summary = "搜索文档", description = "根据关键词搜索标题、内容、关键词，或按分类筛选文档")
    public Result<List<DocumentVO>> search(
            @Parameter(description = "关键词（搜索标题、内容、关键词）") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类") @RequestParam(required = false) String category) {
        DocumentQueryDTO dto = new DocumentQueryDTO();
        dto.setKeyword(keyword);
        dto.setCategory(category);
        Long userId = requestUserContext.getRequiredUserId();
        List<DocumentVO> documents = documentService.search(dto, userId);
        return Result.success(documents);
    }

    /**
     * 获取文档版本列表
     * @param id 文档ID
     * @return 文档版本列表
     */
    @GetMapping("/{id}/versions")
    @Operation(summary = "获取文档版本列表", description = "获取文档的所有历史版本，按版本号降序排列")
    public Result<List<DocumentVersion>> getVersions(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        List<DocumentVersion> versions = documentService.getVersions(id, userId);
        return Result.success(versions);
    }

    /**
     * 恢复文档版本
     * @param id 文档ID
     * @param versionNumber 版本号
     * @return 恢复后的文档VO
     */
    @PostMapping("/{id}/restore/{versionNumber}")
    @Operation(summary = "恢复文档版本", description = "将文档恢复到指定版本，自动保存当前版本作为历史版本")
    public Result<DocumentVO> restoreVersion(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "版本号") @PathVariable Integer versionNumber) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.restoreVersion(id, versionNumber, userId);
        return Result.success(document);
    }
}
