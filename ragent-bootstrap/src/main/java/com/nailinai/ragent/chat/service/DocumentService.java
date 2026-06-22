package com.nailinai.ragent.chat.service;

import com.nailinai.ragent.dto.response.DocumentDetailResponse;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理服务接口。
 * 提供文档上传、查询、删除等基础操作。
 */
public interface DocumentService {

    /** 上传文档到指定知识库，保存文件并记录元数据 */
    DocumentResponse upload(Long kbId, MultipartFile file);

    /** 查询指定知识库下的文档列表 */
    List<DocumentResponse> listByKbId(Long kbId);

    /**
     * 按文档 ID 查询文档实体。
     * 用于异步入库流程中获取文档的 kbId 等信息。
     *
     * @param docId 文档 ID
     * @return 文档实体，不存在时抛出 BusinessException
     */
    Document getById(Long docId);

    /** 查询文档详情，包含原文内容和切片列表 */
    DocumentDetailResponse getDetail(Long docId);

    /** 删除文档及其关联的 chunk 数据和本地文件 */
    void delete(Long docId);
}
