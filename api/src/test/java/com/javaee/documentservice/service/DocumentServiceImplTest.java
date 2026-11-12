package com.javaee.documentservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.client.CollabClient;
import com.javaee.documentservice.client.FileServiceClient;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.mapper.DocumentVersionMapper;
import com.javaee.documentservice.service.impl.DocumentServiceImpl;
import com.javaee.documentservice.vo.DocumentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentServiceImplTest {

    private DocumentMapper documentMapper;
    private DocumentVersionMapper documentVersionMapper;
    private DocumentContentService documentContentService;
    private DocumentAccessService documentAccessService;
    private FileServiceClient fileServiceClient;
    private CollabClient collab;
    private DocumentServiceImpl documentService;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        documentVersionMapper = mock(DocumentVersionMapper.class);
        documentContentService = mock(DocumentContentService.class);
        documentAccessService = mock(DocumentAccessService.class);
        fileServiceClient = mock(FileServiceClient.class);
        collab = mock(CollabClient.class);
        when(collab.convert(any())).thenReturn(new byte[]{9, 9});

        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class).doInTransaction(null));

        documentService = new DocumentServiceImpl();
        ReflectionTestUtils.setField(documentService, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(documentService, "documentVersionMapper", documentVersionMapper);
        ReflectionTestUtils.setField(documentService, "documentContentService", documentContentService);
        ReflectionTestUtils.setField(documentService, "documentAccessService", documentAccessService);
        ReflectionTestUtils.setField(documentService, "fileServiceClient", fileServiceClient);
        ReflectionTestUtils.setField(documentService, "collab", collab);
        ReflectionTestUtils.setField(documentService, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(documentService, "objectMapper", new ObjectMapper());
    }

    @Test
    void createStoresDocumentInUserBucketAndGrantsOwnerAccess() {
        when(documentContentService.getBucketName(7L)).thenReturn("user-7");
        when(documentContentService.getObjectName("doc-1")).thenReturn("document-content/doc-1.md");
        doAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId("doc-1");
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        DocumentCreateDTO dto = new DocumentCreateDTO();
        dto.setTitle("计划");
        dto.setCategory("work");
        dto.setTags(List.of("q2"));

        DocumentVO vo = documentService.create(dto, 7L);

        ArgumentCaptor<Document> inserted = ArgumentCaptor.forClass(Document.class);
        verify(documentMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getBucketName()).isEqualTo("user-7");
        verify(documentMapper, atLeastOnce()).updateById(any(Document.class));
        verify(documentAccessService).grantOwnerAccess("doc-1", "user-7", 7L);
        assertThat(vo.getBucketName()).isEqualTo("user-7");
        assertThat(vo.getObjectName()).isEqualTo("document-content/doc-1.md");
        // 协同状态跟正文一起落，不再等第一次打开编辑器
        verify(documentContentService).saveYdocState("doc-1", "user-7", new byte[]{9, 9});
    }

    @Test
    void uploadParsesTheBytesInHandAndBuildsCollabStateUpFront() {
        when(documentContentService.getBucketName(7L)).thenReturn("user-7");
        when(fileServiceClient.upload(any())).thenReturn("file-1");
        doAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId("doc-9");
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        DocumentVO vo = documentService.createFromFile(markdownUpload(), "work", 7L);

        assertThat(vo.getTitle()).isEqualTo("笔记");
        verify(documentContentService).saveYdocState("doc-9", "user-7", new byte[]{9, 9});
        verify(documentContentService).saveContent("doc-9", "user-7", "# 标题\n\n正文");
    }

    @Test
    void uploadRefusesFormatsThatCannotBecomeAnEditableDocument() {
        MockMultipartFile pdf = new MockMultipartFile("file", "扫描件.pdf", null, new byte[]{1});

        assertThatThrownBy(() -> documentService.createFromFile(pdf, "work", 7L))
                .isInstanceOf(BusinessException.class);

        verify(fileServiceClient, never()).upload(any());
    }

    @Test
    void collabStateFailureLeavesNoDocumentAndNoArchivedFile() {
        when(documentContentService.getBucketName(7L)).thenReturn("user-7");
        when(fileServiceClient.upload(any())).thenReturn("file-2");
        when(collab.convert(any())).thenThrow(new BusinessException("协同服务不可达"));
        doAnswer(invocation -> {
            invocation.getArgument(0, Document.class).setId("doc-10");
            return 1;
        }).when(documentMapper).insert(any(Document.class));

        assertThatThrownBy(() -> documentService.createFromFile(markdownUpload(), "work", 7L))
                .isInstanceOf(BusinessException.class);

        verify(documentMapper).deleteById("doc-10");
        verify(fileServiceClient).delete("file-2");
    }

    private MockMultipartFile markdownUpload() {
        return new MockMultipartFile("file", "笔记.md", null,
                "# 标题\n\n正文".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void collaboratorReadsContentFromDocumentBucket() {
        Document document = document("doc-2", 1L, "team-bucket", "document-content/doc-2.txt");
        when(documentMapper.selectById("doc-2")).thenReturn(document);
        doNothing().when(documentAccessService).assertCanRead(document, 2L);
        when(documentContentService.getContent("doc-2", "team-bucket")).thenReturn("shared text");

        DocumentVO vo = documentService.getById("doc-2", 2L);

        verify(documentAccessService).assertCanRead(document, 2L);
        verify(documentContentService).getContent("doc-2", "team-bucket");
        assertThat(vo.getContent()).isEqualTo("shared text");
        assertThat(vo.getBucketName()).isEqualTo("team-bucket");
    }

    @Test
    void grantAccessUsesDocumentBucketAndRequiresOwner() {
        Document document = document("doc-3", 1L, "user-1", "document-content/doc-3.txt");
        when(documentMapper.selectById("doc-3")).thenReturn(document);
        when(documentAccessService.isOwner(document, 1L)).thenReturn(true);

        documentService.grantAccess("doc-3", 2L, "editor", 1L);

        verify(documentAccessService).grantAccess("doc-3", "user-1", 2L, "editor");
    }

    @Test
    void collaboratorCanSaveEmptyContentWithoutOverwritingMetadata() {
        Document document = document("doc-4", 1L, "owner-bucket", "document-content/doc-4.txt");
        when(documentMapper.selectById("doc-4")).thenReturn(document);
        doNothing().when(documentAccessService).assertCanWrite(document, 2L);
        when(documentContentService.getContent("doc-4", "owner-bucket")).thenReturn("before");

        DocumentUpdateDTO dto = new DocumentUpdateDTO();
        dto.setContent("");

        DocumentVO vo = documentService.update("doc-4", dto, 2L);

        verify(documentAccessService).assertCanWrite(document, 2L);
        verify(documentContentService).updateContent("doc-4", "owner-bucket", "");
        assertThat(document.getTitle()).isEqualTo("doc");
        assertThat(vo.getContent()).isEqualTo("");
    }

    /**
     * 协同服务停止编辑 2 秒后就回存一次。每次都建版本的话，版本表会按打字速度增长。
     */
    @Test
    void autosaveFromTheCollabServerDoesNotCreateAHistoryVersion() {
        Document document = document("doc-5", 1L, "owner-bucket", "document-content/doc-5.txt");
        when(documentMapper.selectById("doc-5")).thenReturn(document);

        DocumentUpdateDTO dto = new DocumentUpdateDTO();
        dto.setContent("<p>正在打字</p>");
        dto.setAutosave(true);

        documentService.update("doc-5", dto, 1L);

        verify(documentVersionMapper, never()).insert(any(DocumentVersion.class));
        verify(documentContentService).updateContent("doc-5", "owner-bucket", "<p>正在打字</p>");
    }

    /** 手动保存照旧建版本——省掉的只是自动回存那条路。 */
    @Test
    void manualSaveStillCreatesAHistoryVersion() {
        Document document = document("doc-6", 1L, "owner-bucket", "document-content/doc-6.txt");
        when(documentMapper.selectById("doc-6")).thenReturn(document);
        when(documentContentService.getContent("doc-6", "owner-bucket")).thenReturn("<p>旧内容</p>");

        DocumentUpdateDTO dto = new DocumentUpdateDTO();
        dto.setContent("<p>新内容</p>");

        documentService.update("doc-6", dto, 1L);

        verify(documentVersionMapper).insert(any(DocumentVersion.class));
    }

    /** 协同状态和正文存在同一个桶里，读写各自要读写权限。 */
    @Test
    void ydocStateFollowsTheDocumentsOwnBucketAndAccessRules() {
        Document document = document("doc-7", 1L, "team-bucket", "document-content/doc-7.txt");
        when(documentMapper.selectById("doc-7")).thenReturn(document);
        when(documentContentService.getYdocState("doc-7", "team-bucket"))
                .thenReturn(new byte[]{1, 2, 3});

        assertThat(documentService.getYdocState("doc-7", 2L)).containsExactly(1, 2, 3);
        verify(documentAccessService).assertCanRead(document, 2L);

        documentService.saveYdocState("doc-7", 2L, new byte[]{4, 5});

        verify(documentAccessService).assertCanWrite(document, 2L);
        verify(documentContentService).saveYdocState("doc-7", "team-bucket", new byte[]{4, 5});
    }

    /** 没存过二进制的旧文档返回 null，协同服务据此回退到用 HTML 灌种一次。 */
    @Test
    void reportsMissingYdocStateSoTheCollabServerStartsEmpty() {
        Document document = document("doc-8", 1L, "owner-bucket", "document-content/doc-8.md");
        when(documentMapper.selectById("doc-8")).thenReturn(document);
        when(documentContentService.getYdocState("doc-8", "owner-bucket")).thenReturn(null);

        assertThat(documentService.getYdocState("doc-8", 1L)).isNull();
    }

    private Document document(String id, Long userId, String bucketName, String objectName) {
        Document document = new Document();
        document.setId(id);
        document.setTitle("doc");
        document.setUserId(userId);
        document.setBucketName(bucketName);
        document.setObjectName(objectName);
        document.setStatus("active");
        document.setVersion(1);
        document.setTags("[]");
        document.setKeywords("[]");
        return document;
    }
}
