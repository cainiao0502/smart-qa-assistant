-- ============================================================================
--  生成 CI 评估种子数据（data/eval/seed.sql）
--
--  用途：eval.yml 的评估门禁跑在 GitHub runner 的 postgres service 上，那是一
--  全新空库；评估集（eval-set.json）指向 kbId=2 的真实语料，空库上必然
--  「评估集与库脱节」而中止。本脚本从真实库中抽取该知识库的语料（含 embedding，
--  CI 因此无需调用 embedding 供应商灌数据）。
--
--  用法（在有真实语料的库上执行，输出即 seed.sql）：
--    docker exec -i ragent-pg psql -U postgres -d ragent -t -A -f - \
--      < data/eval/make-seed.sql > data/eval/seed.sql
--  注意：必须带 -t -A（tuples-only + unaligned），否则 psql 会输出表框；
--  不要在脚本里用 \pset——它会把 "Output format is unaligned." 一并写进 seed.sql。
--
--  设计要点：
--   * 只导出 kbId=2（不要整库导出——其余知识库属真实用户，不应进公开仓库）；
--   * tsv 是 GENERATED ALWAYS 列，不能也不应导出（由 chunk_tokens 自动派生）；
--   * 全部 INSERT 带 ON CONFLICT (id) DO NOTHING → 可重复执行；
--   * 末尾同步三个序列，避免后续插入撞主键。
-- ============================================================================

-- 知识库（owner_user_id 必须导出：评估 runner 会据此绑定归属，null 会被 fail-fast）
SELECT format(
    'INSERT INTO knowledge_base (id, name, description, created_at, updated_at, owner_user_id) '
    || 'VALUES (%L, %L, %L, %L, %L, %L) ON CONFLICT (id) DO NOTHING;',
    id, name, description, created_at, updated_at, owner_user_id)
FROM knowledge_base
WHERE id = 2;

-- 文档
SELECT format(
    'INSERT INTO document (id, kb_id, name, file_type, storage_path, content, status, error_message, created_at, updated_at) '
    || 'VALUES (%L, %L, %L, %L, %L, %L, %L, %L, %L, %L) ON CONFLICT (id) DO NOTHING;',
    id, kb_id, name, file_type, storage_path, content, status, error_message, created_at, updated_at)
FROM document
WHERE kb_id = 2
ORDER BY id;

-- 切片（向量以 text 字面量 + ::vector 转换导出）
SELECT format(
    'INSERT INTO document_chunk (id, kb_id, doc_id, chunk_index, chunk_text, chunk_tokens, token_estimate, paragraph_index, embedding) '
    || 'VALUES (%L, %L, %L, %L, %L, %L, %L, %L, %L::vector) ON CONFLICT (id) DO NOTHING;',
    id, kb_id, doc_id, chunk_index, chunk_text, chunk_tokens, token_estimate, paragraph_index, embedding::text)
FROM document_chunk
WHERE kb_id = 2
ORDER BY id;

-- 序列同步
SELECT format(
    'SELECT setval(pg_get_serial_sequence(%L, %L), GREATEST((SELECT COALESCE(max(id), 1) FROM %I), 1));',
    tbl, 'id', tbl)
FROM (VALUES ('knowledge_base'), ('document'), ('document_chunk')) AS t(tbl);
