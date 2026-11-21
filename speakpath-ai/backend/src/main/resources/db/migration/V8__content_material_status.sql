-- 内容项练习材料状态：PENDING（待生成）/ GENERATING（生成中）/ READY（可用）/ FAILED（生成失败，已降级）
ALTER TABLE content_items ADD COLUMN material_status VARCHAR(20) NOT NULL DEFAULT 'PENDING';

-- 已具备结构化练习材料的内容项直接标记为可用
UPDATE content_items
SET material_status = 'READY'
WHERE (jsonb_typeof(content_json -> 'sentences') = 'array' AND jsonb_array_length(content_json -> 'sentences') > 0)
   OR (jsonb_typeof(content_json -> 'words') = 'array' AND jsonb_array_length(content_json -> 'words') > 0)
   OR (jsonb_typeof(content_json -> 'items') = 'array' AND jsonb_array_length(content_json -> 'items') > 0)
   OR (item_type = 'SCENARIO' AND content_json ? 'topic');
