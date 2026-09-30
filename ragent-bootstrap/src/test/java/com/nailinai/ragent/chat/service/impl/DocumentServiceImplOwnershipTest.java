package com.nailinai.ragent.chat.service.impl;

import com.nailinai.ragent.chat.service.FileStorageService;
import com.nailinai.ragent.dto.response.DocumentResponse;
import com.nailinai.ragent.entity.Document;
import com.nailinai.ragent.entity.KnowledgeBase;
import com.nailinai.ragent.framework.common.BusinessException;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import com.nailinai.ragent.mapper.DocumentMapper;
import com.nailinai.ragent.mapper.DocumentTaskMapper;
import com.nailinai.ragent.mapper.KnowledgeBaseMapper;
import com.nailinai.ragent.user.context.UserIdHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档列表接口的归属校验测试。
 *
 * <p>背景：{@code GET /api/kb/{kbId}/documents} 此前只按 kbId 查询，没有归属校验——
 * 任何登录用户猜到（自增的）kbId 就能列出别人知识库下的文档名、类型与状态。
 * 同一份数据的其它出口（文档详情、触发入库、删除）都校验了归属，只有这个列表漏了。
 * 这里把「列表与详情同口径」钉成回归测试，避免以后又被改回去。</p>
 */
@ExtendWith(MockitoExtension.class)
class DocumentServiceImplOwnershipTest {

    private static final Long OWNER_USER_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;
    private static final Long KB_ID = 1L;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private DocumentMapper documentMapper;

    @Mock
    private DocumentChunkMapper documentChunkMapper;

    @Mock
    private DocumentTaskMapper documentTaskMapper;

    @Mock
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @AfterEach
    void clearUserContext() {
        // UserIdHolder 是 ThreadLocal，测试间必须清理，否则串号
        UserIdHolder.clear();
    }

    private DocumentServiceImpl newService() {
        return new DocumentServiceImpl(fileStorageService, documentMapper, documentChunkMapper,
                documentTaskMapper, knowledgeBaseMapper);
    }

    private Document documentInKb() {
        Document document = new Document();
        document.setId(100L);
        document.setKbId(KB_ID);
        document.setName("probe-doc.txt");
        document.setFileType("txt");
        return document;
    }

    @Test
    @DisplayName("知识库属于他人时列表返回 NOT_FOUND，且不触碰文档表")
    void list_shouldRejectWhenKnowledgeBaseBelongsToAnotherUser() {
        UserIdHolder.set(OTHER_USER_ID);
        when(knowledgeBaseMapper.selectByIdAndOwner(KB_ID, OTHER_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> newService().listByKbId(KB_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("knowledge base not found");

        verify(documentMapper, never()).selectByKbId(anyLong());
    }

    @Test
    @DisplayName("列表的越权文案与「知识库不存在」一致，不暴露存在性")
    void list_shouldNotLeakKnowledgeBaseExistence() {
        UserIdHolder.set(OTHER_USER_ID);
        when(knowledgeBaseMapper.selectByIdAndOwner(KB_ID, OTHER_USER_ID)).thenReturn(null);
        String notOwned = messageOf(() -> newService().listByKbId(KB_ID));

        // 换成「知识库压根不存在」，对外文案必须一模一样
        UserIdHolder.set(OTHER_USER_ID);
        when(knowledgeBaseMapper.selectByIdAndOwner(999L, OTHER_USER_ID)).thenReturn(null);
        String notExists = messageOf(() -> newService().listByKbId(999L));

        assertThat(notOwned).isEqualTo(notExists);
    }

    @Test
    @DisplayName("知识库属于当前用户时正常返回文档列表")
    void list_shouldReturnDocumentsForOwner() {
        UserIdHolder.set(OWNER_USER_ID);
        when(knowledgeBaseMapper.selectByIdAndOwner(KB_ID, OWNER_USER_ID)).thenReturn(new KnowledgeBase());
        when(documentMapper.selectByKbId(KB_ID)).thenReturn(List.of(documentInKb()));

        List<DocumentResponse> documents = newService().listByKbId(KB_ID);

        assertThat(documents).hasSize(1);
        assertThat(documents.get(0).getName()).isEqualTo("probe-doc.txt");
    }

    @Test
    @DisplayName("无登录上下文的内部调用放行，不校验归属")
    void list_shouldPassThroughWithoutLoginContext() {
        UserIdHolder.clear();
        when(documentMapper.selectByKbId(KB_ID)).thenReturn(List.of(documentInKb()));

        List<DocumentResponse> documents = newService().listByKbId(KB_ID);

        assertThat(documents).hasSize(1);
        // 离线评估、知识库级联删除等内部路径不应因为缺少登录态被打断
        verify(knowledgeBaseMapper, never()).selectByIdAndOwner(anyLong(), any());
    }

    @Test
    @DisplayName("文档详情与列表同口径：越权同样返回 NOT_FOUND")
    void detail_shouldRejectWhenKnowledgeBaseBelongsToAnotherUser() {
        UserIdHolder.set(OTHER_USER_ID);
        when(documentMapper.selectById(100L)).thenReturn(documentInKb());
        when(knowledgeBaseMapper.selectByIdAndOwner(KB_ID, OTHER_USER_ID)).thenReturn(null);

        assertThatThrownBy(() -> newService().getDetail(100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("document not found");

        verify(documentChunkMapper, never()).selectByDocId(anyLong());
    }

    private String messageOf(Runnable runnable) {
        try {
            runnable.run();
        } catch (BusinessException ex) {
            return ex.getMessage();
        }
        throw new AssertionError("expected BusinessException but nothing was thrown");
    }
}
