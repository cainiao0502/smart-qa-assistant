package com.nailinai.ragent.config;

import com.nailinai.ragent.chat.retrieve.KeywordTokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 旧数据回填：为 chunk_tokens 为 NULL 的历史切片补写分词结果。
 *
 * <p>背景：关键词检索已从 ILIKE 全表扫描切换到 tsv（基于 chunk_tokens 的生成列）+ GIN 索引。
 * 迁移前入库的切片没有 chunk_tokens，tsv 为空，关键词通道对它们召回归零——
 * 回填必须在服务可用前完成（本 runner 与建表迁移同为 ApplicationRunner，Order 靠后保证
 * 先建列再回填）。新入库切片由 {@code DocumentTaskServiceImpl#buildChunks} 直接写入，
 * 不经过这里。</p>
 *
 * <p>分批执行（每批 500 行）避免一次性锁大量行；单行分词失败写空串兜底，
 * 保证该行退出候选集（tsv 为空）而不会让回填死循环。</p>
 */
@Component
@Order(10)
public class ChunkTokenBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkTokenBackfillRunner.class);

    private static final int BATCH_SIZE = 500;
    /** 防御性上限：单次启动最多处理的批次数，超过即放弃并告警（正常远达不到） */
    private static final int MAX_BATCHES = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final boolean enabled;

    public ChunkTokenBackfillRunner(JdbcTemplate jdbcTemplate,
                                    @Value("${app.rag.chunk-token-backfill.enabled:true}") boolean enabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        long total = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, chunk_text FROM document_chunk WHERE chunk_tokens IS NULL ORDER BY id LIMIT ?",
                    BATCH_SIZE);
            if (rows.isEmpty()) {
                if (total > 0) {
                    log.info("chunk_tokens backfill finished: {} rows updated", total);
                }
                return;
            }
            jdbcTemplate.batchUpdate(
                    "UPDATE document_chunk SET chunk_tokens = ? WHERE id = ?",
                    rows,
                    rows.size(),
                    (ps, row) -> {
                        String chunkText = row.get("chunk_text") == null ? "" : row.get("chunk_text").toString();
                        String tokens;
                        try {
                            tokens = KeywordTokenizer.toTokenString(chunkText);
                        } catch (RuntimeException ex) {
                            // 分词失败写空串：该行 tsv 为空、退出关键词召回集，但不阻塞回填
                            log.warn("chunk_tokens backfill: tokenize failed for id={}, falling back to empty",
                                    row.get("id"), ex);
                            tokens = "";
                        }
                        ps.setString(1, tokens);
                        ps.setLong(2, ((Number) row.get("id")).longValue());
                    });
            total += rows.size();
        }
        log.warn("chunk_tokens backfill stopped at batch limit ({} rows done, more may remain) — rerun to continue", total);
    }
}
