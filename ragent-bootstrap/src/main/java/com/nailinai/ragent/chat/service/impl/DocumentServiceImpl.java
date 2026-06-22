package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.framework.common.ErrorCode;
import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.Document;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.enums.DocumentStatus;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.DocumentTaskMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.chat.service.DocumentService;
import com.nailinai.ragent.chat.service.FileStorageService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class DocumentServiceImpl implements DocumentService {

    private final FileStorageService fileStorageService;
    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentTaskMapper documentTaskMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    public DocumentServiceImpl(FileStorageService fileStorageService,
                               DocumentMapper documentMapper,
                               DocumentChunkMapper documentChunkMapper,
                               DocumentTaskMapper documentTaskMapper,
                               KnowledgeBaseMapper knowledgeBaseMapper) {
        this.fileStorageService = fileStorageService;
        this.documentMapper = documentMapper;
        this.documentChunkMapper = documentChunkMapper;
        this.documentTaskMapper = documentTaskMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
    }

    @Override
    public DocumentResponse upload(Long kbId, MultipartFile file) {
        if (knowledgeBaseMapper.selectById(kbId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "file is required");
        }

        String storagePath = null;
        Document document = new Document();
        try {
            storagePath = fileStorageService.save(file);
            document.setKbId(kbId);
            document.setName(resolveFilename(file.getOriginalFilename()));
            document.setFileType(resolveFileType(file.getOriginalFilename()));
            document.setStoragePath(storagePath);
            document.setContent(null);
            document.setStatus(DocumentStatus.UPLOADED);
            documentMapper.insert(document);
            return toResponse(document);
        } catch (RuntimeException ex) {
            if (StringUtils.hasText(storagePath)) {
                fileStorageService.delete(storagePath);
            }
            throw ex;
        }
    }

    @Override
    public List<DocumentResponse> listByKbId(Long kbId) {
        return documentMapper.selectByKbId(kbId).stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * 按文档 ID 查询文档实体。
     * 异步入库流程中 DocumentController 通过此方法获取文档的 kbId，
     * 用于创建 DocumentTask 记录。
     */
    @Override
    public Document getById(Long docId) {
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        return document;
    }

    @Override
    public DocumentDetailResponse getDetail(Long docId) {
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        List<DocumentChunk> chunks = documentChunkMapper.selectByDocId(docId);
        List<DocumentDetailResponse.ChunkItem> chunkItems = chunks.stream()
                .map(c -> DocumentDetailResponse.ChunkItem.builder()
                        .chunkIndex(c.getChunkIndex())
                        .chunkText(c.getChunkText())
                        .tokenEstimate(c.getTokenEstimate())
                        .paragraphIndex(c.getParagraphIndex())
                        .build())
                .toList();
        return DocumentDetailResponse.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .name(document.getName())
                .fileType(document.getFileType())
                .status(document.getStatus())
                .chunkCount(document.getChunkCount())
                .createdAt(document.getCreatedAt())
                .content(document.getContent())
                .chunks(chunkItems)
                .build();
    }

    @Override
    @Transactional
    public void delete(Long docId) {
        Document document = documentMapper.selectById(docId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "document not found");
        }
        documentChunkMapper.deleteByDocId(docId);
        documentTaskMapper.deleteByDocId(docId);
        documentMapper.deleteById(docId);
        fileStorageService.delete(document.getStoragePath());
    }

    private String resolveFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unknown";
        }
        return filename;
    }

    private String resolveFileType(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "unknown";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    private DocumentResponse toResponse(Document document) {
        return DocumentResponse.builder()
                .id(document.getId())
                .kbId(document.getKbId())
                .name(document.getName())
                .fileType(document.getFileType())
                .status(document.getStatus())
                .chunkCount(document.getChunkCount())
                .createdAt(document.getCreatedAt())
                .build();
    }
}
