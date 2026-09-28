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
import java.util.Objects;

/**
 * 旧数据回填/重建：把 document_chunk.chunk_tokens 修正为当前分词算法的全量结果。
 *
 * <p>背景：关键词检索已从 ILIKE 全表扫描切换到 tsv（基于 chunk_tokens 的生成列）+ GIN 索引。
 * tsv 是生成列，重建索引 = 重写 chunk_tokens。需要处理的行有两类：</p>
 * <ul>
 *   <li>chunk_tokens 为 NULL：迁移前入库的切片，从未分词；</li>
 *   <li>疑似旧格式行：token 数 ≤ {@value KeywordTokenizer#MAX_QUERY_TOKENS} 且非空——
 *       历史版本把查询侧的 24-token 上限错误地用于入库，长切片只留下前 24 个
 *       token（覆盖切片头部 ~25 字，覆盖率 5.8%）。新算法下 500 字切片会产出数百
 *       token，只有「真的很短的切片」与「旧格式行」满足该谓词；前者重分词结果
 *       与现值相同，零写入，天然幂等。</li>
 * </ul>
 *
 * <p>分词失败写空串兜底，保证该行退出关键词召回集（tsv 为空）且不阻塞回填；
 * 空串行被排除在候选集之外，不会每轮重扫挤占批次。翻页用 id 游标（keyset）：
 * 幂等跳过的行不会被重写，游标仍能推进到末尾，不会在同一批行上空转。</p>
 *
 * <p>本 runner 与建表迁移同为 ApplicationRunner，Order 靠后保证先建列再回填。
 * 分批执行（每批 {@value #BATCH_SIZE} 行）避免一次性锁大量行。</p>
 */
@Component
@Order(10)
public class ChunkTokenBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ChunkTokenBackfillRunner.class);

    private static final int BATCH_SIZE = 500;
    /** 防御性上限：单次启动最多处理的批次数，超过即放弃并告警（正常远达不到） */
    private static final int MAX_BATCHES = 2000;

    /** 旧格式行判定：旧实现入库截断到 24 token，新格式长切片远超此数 */
    private static final String CANDIDATES_SQL = """
            SELECT id, chunk_text, chunk_tokens FROM document_chunk
            WHERE (chunk_tokens IS NULL
               OR (chunk_tokens <> '' AND cardinality(string_to_array(chunk_tokens, ' ')) <= 24))
              AND id > ?
            ORDER BY id
            LIMIT ?
            """;

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
        long cursor = 0;
        long scanned = 0;
        long rewritten = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(CANDIDATES_SQL, cursor, BATCH_SIZE);
            if (rows.isEmpty()) {
                if (scanned > 0 || rewritten > 0) {
                    log.info("chunk_tokens backfill finished: {} candidate rows scanned, {} rewritten", scanned, rewritten);
                }
                return;
            }
            for (Map<String, Object> row : rows) {
                cursor = Math.max(cursor, ((Number) row.get("id")).longValue());
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
                scanned++;
                // 幂等：真·短切片的重分词结果与现值相同，跳过写入
                String current = row.get("chunk_tokens") == null ? null : row.get("chunk_tokens").toString();
                if (Objects.equals(tokens, current)) {
                    continue;
                }
                jdbcTemplate.update("UPDATE document_chunk SET chunk_tokens = ? WHERE id = ?",
                        tokens, ((Number) row.get("id")).longValue());
                rewritten++;
            }
        }
        log.warn("chunk_tokens backfill stopped at batch limit ({} rows scanned, {} rewritten, more may remain)"
                + " — rerun to continue", scanned, rewritten);
    }
}
