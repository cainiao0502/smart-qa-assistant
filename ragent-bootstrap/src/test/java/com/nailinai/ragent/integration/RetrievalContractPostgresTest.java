package com.nailinai.ragent.integration;

import com.nailinai.ragent.chat.retrieve.KeywordTokenizer;
import com.nailinai.ragent.entity.DocumentChunk;
import com.nailinai.ragent.mapper.DocumentChunkMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.defaults.DefaultSqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检索 SQL 契约的 Postgres 集成测试（E1：148 个单测全绿而检索在真机坏掉的教训）。
 *
 * <p>用真实 Postgres+pgvector（仓库根 database.sql 建库）跑<strong>真实的 MyBatis 注解
 * SQL</strong>，覆盖三类单测覆盖不了的风险：</p>
 * <ul>
 *   <li>S0-①：长切片尾部术语必须能被关键词通道召回（旧入库截断使 tsv 只含头部 ~25 字，
 *       单测的短字符串样例永远发现不了）；</li>
 *   <li>S1：归属过滤在 SQL 层恒生效——owner A 查不到 owner B 的切片（跨租户读取）；</li>
 *   <li>查询侧截断后的 tsquery 仍合法可执行。</li>
 * </ul>
 *
 * <p>本机无 Docker 时自动跳过（{@code disabledWithoutDocker}）；CI 的 ubuntu runner
 * 必有 Docker，等价于「每次 push 都在真库上回归检索契约」。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RetrievalContractPostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(locateRepoFile("database.sql")),
                    "/docker-entrypoint-initdb.d/01-schema.sql");

    static DocumentChunkMapper chunkMapper;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void bootstrapMybatisOnRealPostgres() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // 检索 SQL 依赖 knowledge_base.owner_user_id；生产由 DatabaseSchemaInitializer 增量补齐
        jdbc.execute("ALTER TABLE knowledge_base ADD COLUMN IF NOT EXISTS owner_user_id BIGINT");

        // 直接以注解 mapper 装配 MyBatis：跑的就是生产 SQL 本体，复制一份 SQL 迟早漂移
        PooledDataSource dataSource = new PooledDataSource(
                "org.postgresql.Driver", postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Environment environment = new Environment("it", new JdbcTransactionFactory(), dataSource);
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(DocumentChunkMapper.class);
        SqlSessionFactory factory = new DefaultSqlSessionFactory(configuration);
        chunkMapper = factory.openSession(true).getMapper(DocumentChunkMapper.class);
    }

    @Test
    @DisplayName("S0-① 回归（SQL 层）：长切片尾部的术语能被关键词通道召回")
    void keywordChannel_shouldHitTailTermsOfLongChunk() {
        Long kbId = insertKbWithOneChunk("it-tail-kb", 1L, longChunkWithTailTerm());

        String tsQuery = KeywordTokenizer.toTsQueryOr("量子退火");
        List<DocumentChunk> hits = chunkMapper.selectTopKByKeyword(
                kbId, tsQuery, null, null, null, 5, 1L);

        assertThat(hits).hasSize(1);
    }

    @Test
    @DisplayName("S1 回归（SQL 层）：归属过滤恒生效——owner A 召回不到 owner B 的切片")
    void retrieval_shouldIsolateChunksByOwner() {
        Long kbOwner1 = insertKbWithOneChunk("it-iso-kb1", 1L, longChunkWithTailTerm());
        Long kbOwner2 = insertKbWithOneChunk("it-iso-kb2", 2L, longChunkWithTailTerm());

        // 关键词通道：owner 1 只能看到自己的库
        assertThat(chunkMapper.selectTopKByKeyword(kbOwner1, KeywordTokenizer.toTsQueryOr("量子退火"),
                null, null, null, 5, 1L)).hasSize(1);
        assertThat(chunkMapper.selectTopKByKeyword(kbOwner2, KeywordTokenizer.toTsQueryOr("量子退火"),
                null, null, null, 5, 1L)).isEmpty();

        // 向量通道：owner 1 查询 owner 2 的库 → 跨租户不可见
        assertThat(chunkMapper.selectTopKByKbId(kbOwner2, embeddingLiteral(),
                null, null, null, 5, 1L)).isEmpty();
        assertThat(chunkMapper.selectTopKByKbId(kbOwner1, embeddingLiteral(),
                null, null, null, 5, 1L)).hasSize(1);
    }

    @Test
    @DisplayName("S0-① 查询侧：超长查询先去重再截断，tsquery 合法可执行")
    void querySide_shouldCapTokensAndStillExecute() {
        Long kbId = insertKbWithOneChunk("it-cap-kb", 1L, longChunkWithTailTerm());

        StringBuilder longQuery = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            longQuery.append((char) (0x4E00 + i));
        }
        String tsQuery = KeywordTokenizer.toTsQueryOr(longQuery.toString());
        assertThat(tsQuery.split(" \\| ")).hasSize(KeywordTokenizer.MAX_QUERY_TOKENS);

        // 不抛 SQL 异常即视为契约成立（截断后的 tsquery 合法）
        assertThat(chunkMapper.selectTopKByKeyword(kbId, tsQuery, null, null, null, 5, 1L)).isEmpty();
    }

    /** 60 个填充汉字后接「量子退火」——旧入库截断下尾部 bigram 物理不在 tsv 里 */
    private static String longChunkWithTailTerm() {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            chunk.append((char) (0x4E00 + i));
        }
        return chunk.append("量子退火").toString();
    }

    private static Long insertKbWithOneChunk(String kbName, Long ownerUserId, String chunkText) {
        jdbc.update("INSERT INTO knowledge_base (name, description, owner_user_id) VALUES (?, 'it', ?)",
                kbName, ownerUserId);
        Long kbId = jdbc.queryForObject("SELECT id FROM knowledge_base WHERE name = ?", Long.class, kbName);
        jdbc.update("""
                INSERT INTO document (kb_id, name, file_type, storage_path, status)
                VALUES (?, 'it-doc.txt', 'txt', 'it/it-doc.txt', 'INDEXED')
                """, kbId);
        Long docId = jdbc.queryForObject("SELECT id FROM document WHERE kb_id = ? AND name = 'it-doc.txt'",
                Long.class, kbId);
        String tokens = KeywordTokenizer.toTokenString(chunkText);
        jdbc.update("""
                INSERT INTO document_chunk (kb_id, doc_id, chunk_index, chunk_text, chunk_tokens,
                                            token_estimate, embedding)
                VALUES (?, ?, 0, ?, ?, ?,
                        ('[' || array_to_string(array_fill(0.1, ARRAY[1024]), ',') || ']')::vector)
                """, kbId, docId, chunkText, tokens, tokens.split(" ").length);
        return kbId;
    }

    private static String embeddingLiteral() {
        StringBuilder dims = new StringBuilder("[");
        for (int i = 0; i < 1024; i++) {
            dims.append(i == 0 ? "0.1" : ",0.1");
        }
        return dims.append("]").toString();
    }

    /** 从当前工作目录逐级向上找仓库根的 database.sql（surefire 工作目录 = 模块根） */
    private static Path locateRepoFile(String fileName) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent()) {
            Path candidate = dir.resolve(fileName);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到 " + fileName + "（应在仓库根）");
    }
}
