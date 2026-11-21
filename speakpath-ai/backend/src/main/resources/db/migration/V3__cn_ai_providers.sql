-- ---------------------------------------------------------------------
-- 国内模型厂商接入（均走 OpenAI 兼容 /chat/completions 协议）。
--
-- 优先级：DeepSeek(5) → 通义千问(6) → 智谱 GLM(7) → Kimi(8) → MOCK(10)
-- 默认 DRAFT + 未启用；配置对应环境变量后，后端启动时会自动启用：
--   DEEPSEEK_API_KEY / DASHSCOPE_API_KEY / ZHIPU_API_KEY / MOONSHOT_API_KEY
-- 未配置密钥时 MOCK 继续提供确定性输出，保证端到端可运行。
-- ---------------------------------------------------------------------
INSERT INTO ai_model_configs (id, capability, provider_code, model_code, base_url, secret_ref, priority, enabled, timeout_ms, max_retries, parameters_json, region, data_policy, traffic_percent, version, status) VALUES
-- DeepSeek
('30000000-0000-0000-0000-000000000011','ASSESSMENT','DEEPSEEK','deepseek-chat','https://api.deepseek.com/v1','env:deepseek-api-key',5,FALSE,30000,1,'{"temperature":0.2,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000012','PLANNING','DEEPSEEK','deepseek-chat','https://api.deepseek.com/v1','env:deepseek-api-key',5,FALSE,30000,1,'{"temperature":0.3,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000013','CORRECTION','DEEPSEEK','deepseek-chat','https://api.deepseek.com/v1','env:deepseek-api-key',5,FALSE,30000,1,'{"temperature":0.1,"maxTokens":1500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000014','TUTOR','DEEPSEEK','deepseek-chat','https://api.deepseek.com/v1','env:deepseek-api-key',5,FALSE,30000,1,'{"temperature":0.6,"maxTokens":1200}','cn','STANDARD',100,1,'DRAFT'),
-- 通义千问（阿里云百炼 DashScope OpenAI 兼容模式）
('30000000-0000-0000-0000-000000000015','ASSESSMENT','DASHSCOPE','qwen-plus','https://dashscope.aliyuncs.com/compatible-mode/v1','env:dashscope-api-key',6,FALSE,30000,1,'{"temperature":0.2,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000016','PLANNING','DASHSCOPE','qwen-plus','https://dashscope.aliyuncs.com/compatible-mode/v1','env:dashscope-api-key',6,FALSE,30000,1,'{"temperature":0.3,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000017','CORRECTION','DASHSCOPE','qwen-plus','https://dashscope.aliyuncs.com/compatible-mode/v1','env:dashscope-api-key',6,FALSE,30000,1,'{"temperature":0.1,"maxTokens":1500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000018','TUTOR','DASHSCOPE','qwen-plus','https://dashscope.aliyuncs.com/compatible-mode/v1','env:dashscope-api-key',6,FALSE,30000,1,'{"temperature":0.6,"maxTokens":1200}','cn','STANDARD',100,1,'DRAFT'),
-- 智谱 GLM
('30000000-0000-0000-0000-000000000019','ASSESSMENT','ZHIPU','glm-4-plus','https://open.bigmodel.cn/api/paas/v4','env:zhipu-api-key',7,FALSE,30000,1,'{"temperature":0.2,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000020','PLANNING','ZHIPU','glm-4-plus','https://open.bigmodel.cn/api/paas/v4','env:zhipu-api-key',7,FALSE,30000,1,'{"temperature":0.3,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000021','CORRECTION','ZHIPU','glm-4-plus','https://open.bigmodel.cn/api/paas/v4','env:zhipu-api-key',7,FALSE,30000,1,'{"temperature":0.1,"maxTokens":1500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000022','TUTOR','ZHIPU','glm-4-plus','https://open.bigmodel.cn/api/paas/v4','env:zhipu-api-key',7,FALSE,30000,1,'{"temperature":0.6,"maxTokens":1200}','cn','STANDARD',100,1,'DRAFT'),
-- 月之暗面 Kimi
('30000000-0000-0000-0000-000000000023','ASSESSMENT','MOONSHOT','moonshot-v1-8k','https://api.moonshot.cn/v1','env:moonshot-api-key',8,FALSE,30000,1,'{"temperature":0.2,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000024','PLANNING','MOONSHOT','moonshot-v1-8k','https://api.moonshot.cn/v1','env:moonshot-api-key',8,FALSE,30000,1,'{"temperature":0.3,"maxTokens":2500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000025','CORRECTION','MOONSHOT','moonshot-v1-8k','https://api.moonshot.cn/v1','env:moonshot-api-key',8,FALSE,30000,1,'{"temperature":0.1,"maxTokens":1500}','cn','STANDARD',100,1,'DRAFT'),
('30000000-0000-0000-0000-000000000026','TUTOR','MOONSHOT','moonshot-v1-8k','https://api.moonshot.cn/v1','env:moonshot-api-key',8,FALSE,30000,1,'{"temperature":0.6,"maxTokens":1200}','cn','STANDARD',100,1,'DRAFT');
