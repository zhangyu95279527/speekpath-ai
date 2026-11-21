-- =====================================================================
-- SpeakPath AI 种子数据：考试模板、题库、AI 模型配置、提示词、训练内容
-- =====================================================================

-- ---------------------------------------------------------------------
-- 考试模板（已发布）
-- ---------------------------------------------------------------------
INSERT INTO exam_templates (id, exam_code, version, display_name, config_json, rubric_json, status) VALUES
('10000000-0000-0000-0000-000000000001', 'IELTS_SPEAKING', 3, 'IELTS Speaking',
 '{"sections":[{"code":"PART_1","label":"Introduction & Interview","questionCount":5,"responseSeconds":45},
               {"code":"PART_2","label":"Long Turn","questionCount":5,"responseSeconds":120},
               {"code":"PART_3","label":"Discussion","questionCount":5,"responseSeconds":90}],
   "minTurns":10,"defaultTurns":12,"maxTurns":15,"totalMinutes":15,"scoreRange":{"min":0,"max":9}}',
 '{"version":"IELTS_2026_V1","scoreRange":{"min":0,"max":9},
   "dimensions":[{"code":"FLUENCY_COHERENCE","label":"Fluency & Coherence","weight":0.2},
                 {"code":"LEXICAL_RESOURCE","label":"Lexical Resource","weight":0.2},
                 {"code":"GRAMMAR_RANGE_ACCURACY","label":"Grammatical Range & Accuracy","weight":0.2},
                 {"code":"PRONUNCIATION","label":"Pronunciation","weight":0.2},
                 {"code":"TASK_ACHIEVEMENT","label":"Task Achievement","weight":0.2}]}',
 'PUBLISHED'),
('10000000-0000-0000-0000-000000000002', 'TOEFL_SPEAKING', 2, 'TOEFL Speaking',
 '{"sections":[{"code":"INDEPENDENT","label":"Independent Task","questionCount":6,"responseSeconds":45},
               {"code":"INTEGRATED","label":"Integrated Task","questionCount":6,"responseSeconds":60}],
   "minTurns":10,"defaultTurns":12,"maxTurns":15,"totalMinutes":20,"scoreRange":{"min":0,"max":30}}',
 '{"version":"TOEFL_2026_V1","scoreRange":{"min":0,"max":30},
   "dimensions":[{"code":"DELIVERY","label":"Delivery","weight":0.34},
                 {"code":"LANGUAGE_USE","label":"Language Use","weight":0.33},
                 {"code":"TOPIC_DEVELOPMENT","label":"Topic Development","weight":0.33}]}',
 'PUBLISHED');

-- ---------------------------------------------------------------------
-- 题库（IELTS 15 题 / TOEFL 12 题，覆盖首期诊断轮数上限）
-- ---------------------------------------------------------------------
INSERT INTO question_bank (id, exam_code, section_code, question_text, difficulty, estimated_seconds, tags, status, version) VALUES
-- IELTS Part 1（熟悉话题）
('20000000-0000-0000-0000-000000000001','IELTS_SPEAKING','PART_1','Do you work or are you a student?',2,45,'["familiar","work-study"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000002','IELTS_SPEAKING','PART_1','What do you enjoy most about your work or studies?',3,45,'["familiar","work-study"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000003','IELTS_SPEAKING','PART_1','Describe the place where you live.',2,45,'["familiar","home"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000004','IELTS_SPEAKING','PART_1','How do you usually spend your weekends?',2,45,'["familiar","free-time"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000005','IELTS_SPEAKING','PART_1','Do you prefer reading books or watching movies? Why?',3,45,'["familiar","media"]','PUBLISHED',1),
-- IELTS Part 2（经历描述 / 长回合）
('20000000-0000-0000-0000-000000000006','IELTS_SPEAKING','PART_2','Describe a memorable trip you have taken. You should say: where you went, who you went with, what you did, and why it was memorable.',5,120,'["experience","narrative"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000007','IELTS_SPEAKING','PART_2','Describe a person who has influenced you. You should say: who the person is, how you know them, what they did, and how they influenced you.',5,120,'["experience","people"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000008','IELTS_SPEAKING','PART_2','Describe an important decision you made. You should say: what it was, when you made it, why you made it, and what happened as a result.',6,120,'["experience","decision"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000009','IELTS_SPEAKING','PART_2','Describe a skill you would like to learn. You should say: what it is, why you want to learn it, how you would learn it, and how it would help you.',4,120,'["experience","learning"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000010','IELTS_SPEAKING','PART_2','Describe a time you helped someone. You should say: who you helped, what the situation was, what you did, and how you felt afterwards.',4,120,'["experience","helping"]','PUBLISHED',1),
-- IELTS Part 3（观点论证 / 讨论）
('20000000-0000-0000-0000-000000000011','IELTS_SPEAKING','PART_3','Do you think people travel more now than in the past? Why?',6,90,'["opinion","society"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000012','IELTS_SPEAKING','PART_3','What are the advantages and disadvantages of working from home?',6,90,'["opinion","work"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000013','IELTS_SPEAKING','PART_3','How can governments encourage people to live healthier lives?',7,90,'["opinion","health"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000014','IELTS_SPEAKING','PART_3','Do you think technology has made communication better or worse?',7,90,'["opinion","technology"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000015','IELTS_SPEAKING','PART_3','Should children be taught to manage money at school? Why or why not?',6,90,'["opinion","education"]','PUBLISHED',1),
-- TOEFL
('20000000-0000-0000-0000-000000000016','TOEFL_SPEAKING','INDEPENDENT','Some people prefer to study alone, while others prefer to study in groups. Which do you prefer and why?',4,45,'["preference"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000017','TOEFL_SPEAKING','INDEPENDENT','Do you agree or disagree: it is better to take risks than to stay in your comfort zone?',5,45,'["opinion"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000018','TOEFL_SPEAKING','INDEPENDENT','Some people prefer living in big cities; others prefer small towns. Which do you prefer and why?',3,45,'["preference","city"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000019','TOEFL_SPEAKING','INDEPENDENT','Do you agree or disagree: students should be required to wear school uniforms?',4,45,'["opinion","education"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000020','TOEFL_SPEAKING','INDEPENDENT','Some people think social media has a positive effect on friendships. Do you agree?',4,45,'["opinion","technology"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000021','TOEFL_SPEAKING','INDEPENDENT','What is the most important quality of a good leader? Use reasons and examples.',5,45,'["opinion","leadership"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000022','TOEFL_SPEAKING','INTEGRATED','The university plans to build a new library instead of renovating the old one. Summarize the two student opinions and state which one you agree with.',5,60,'["integrated","campus"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000023','TOEFL_SPEAKING','INTEGRATED','A study shows that companies with flexible work hours are more productive. Explain the study findings and give your opinion.',5,60,'["integrated","work"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000024','TOEFL_SPEAKING','INTEGRATED','The professor describes two strategies animals use to survive winter. Summarize the strategies and the examples given.',6,60,'["integrated","biology"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000025','TOEFL_SPEAKING','INTEGRATED','A town proposes banning cars from the city center. Summarize the benefits and drawbacks mentioned and give your opinion.',5,60,'["integrated","city"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000026','TOEFL_SPEAKING','INTEGRATED','The lecturer explains how the printing press changed European society. Summarize the main points from the lecture.',6,60,'["integrated","history"]','PUBLISHED',1),
('20000000-0000-0000-0000-000000000027','TOEFL_SPEAKING','INTEGRATED','A psychology class discusses why people procrastinate. Summarize the reasons given and one suggested solution.',5,60,'["integrated","psychology"]','PUBLISHED',1);

-- ---------------------------------------------------------------------
-- AI 模型配置：MOCK 供应商全部能力可用，保证无密钥即可端到端运行
-- ---------------------------------------------------------------------
INSERT INTO ai_model_configs (id, capability, provider_code, model_code, base_url, secret_ref, priority, enabled, timeout_ms, max_retries, parameters_json, region, data_policy, traffic_percent, version, status) VALUES
('30000000-0000-0000-0000-000000000001','REALTIME','MOCK','mock-realtime-v1',NULL,NULL,10,TRUE,10000,0,'{"mode":"websocket-text"}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000002','ASR','MOCK','mock-asr-v1',NULL,NULL,10,TRUE,5000,0,'{}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000003','TTS','MOCK','mock-tts-v1',NULL,NULL,10,TRUE,5000,0,'{}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000004','PRONUNCIATION','MOCK','mock-pronunciation-v1',NULL,NULL,10,TRUE,5000,0,'{}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000005','ASSESSMENT','MOCK','mock-assessment-v1',NULL,NULL,10,TRUE,10000,0,'{"temperature":0.2}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000006','PLANNING','MOCK','mock-planning-v1',NULL,NULL,10,TRUE,10000,0,'{"temperature":0.3}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000007','CORRECTION','MOCK','mock-correction-v1',NULL,NULL,10,TRUE,10000,0,'{"temperature":0.1}','global','STANDARD',100,1,'ACTIVE'),
('30000000-0000-0000-0000-000000000008','TUTOR','MOCK','mock-tutor-v1',NULL,NULL,10,TRUE,10000,0,'{"temperature":0.6}','global','STANDARD',100,1,'ACTIVE'),
-- OpenAI-compatible 备用配置（DRAFT，配置密钥后可在后台激活）
('30000000-0000-0000-0000-000000000009','ASSESSMENT','OPENAI_COMPATIBLE','gpt-4o-mini','https://api.openai.com/v1','env:openai-api-key',50,FALSE,12000,1,'{"temperature":0.2,"maxTokens":2500}','global','STANDARD',0,1,'DRAFT'),
('30000000-0000-0000-0000-000000000010','CORRECTION','OPENAI_COMPATIBLE','gpt-4o-mini','https://api.openai.com/v1','env:openai-api-key',50,FALSE,12000,1,'{"temperature":0.1,"maxTokens":1500}','global','STANDARD',0,1,'DRAFT');

-- ---------------------------------------------------------------------
-- 提示词模板（含输出 schema）
-- ---------------------------------------------------------------------
INSERT INTO prompt_templates (id, capability, prompt_key, version, template_text, output_schema, status) VALUES
('40000000-0000-0000-0000-000000000001','ASSESSMENT','ielts-diagnostic',1,
 'You are an IELTS speaking examiner. Score the candidate turns against the locked rubric. Output dimension scores, evidence quotes from the transcript, confidence and recommendations. Never claim this is an official exam score.',
 '{"type":"object","required":["overallEstimate","confidence","dimensions","limitations"],"properties":{"overallEstimate":{"type":"object","required":["low","high"]},"confidence":{"type":"number"},"dimensions":{"type":"array"},"limitations":{"type":"array"}}}',
 'ACTIVE'),
('40000000-0000-0000-0000-000000000002','CORRECTION','speaking-feedback',1,
 'You are an English coach. For each user sentence, find grammar, tense, vocabulary, collocation and sentence-structure errors. Always quote the original text as evidence. Distinguish clear errors from style suggestions.',
 '{"type":"object","required":["summary","corrections"],"properties":{"summary":{"type":"string"},"corrections":{"type":"array"}}}',
 'ACTIVE'),
('40000000-0000-0000-0000-000000000003','PLANNING','study-plan',1,
 'You are a study planner. Generate a daily study plan within the daily minute budget, covering warm-up, skill drills, scenario speaking, correction shadowing and quiz, respecting rest days and prerequisites.',
 '{"type":"object","required":["days"],"properties":{"days":{"type":"array"}}}',
 'ACTIVE'),
('40000000-0000-0000-0000-000000000004','TUTOR','free-conversation',1,
 'You are a friendly English speaking tutor. Keep the conversation going, give one short correction if needed, and ask a follow-up question.',
 '{"type":"object","required":["reply","correction"],"properties":{"reply":{"type":"string"},"correction":{"type":["object","null"]}}}',
 'ACTIVE');

-- ---------------------------------------------------------------------
-- 训练内容库（计划生成使用）
-- ---------------------------------------------------------------------
INSERT INTO content_items (id, item_type, title, difficulty, duration_min, tags, prerequisites, content_json, status) VALUES
('50000000-0000-0000-0000-000000000001','WARMUP','昨日词汇快问快答',1,3,'["warmup","vocabulary"]','[]','{"instructions":"Answer the vocabulary questions aloud."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000002','WARMUP','高频句型朗读热身',1,3,'["warmup","sentence"]','[]','{"instructions":"Read the sentences aloud with natural stress."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000003','WARMUP','影子跟读 60 秒',1,3,'["warmup","shadowing"]','[]','{"instructions":"Shadow the audio and match intonation."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000004','SKILL','现在完成时 vs 一般过去时专项',2,8,'["grammar","tense"]','[]','{"instructions":"Complete the tense drill, then speak your answers."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000005','SKILL','连接词与逻辑表达训练',2,8,'["coherence","linking"]','[]','{"instructions":"Reorganize the ideas using linking words."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000006','SKILL','同义替换与搭配扩展',2,8,'["vocabulary","collocation"]','[]','{"instructions":"Replace underlined words with synonyms."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000007','SKILL','从句升级训练',3,8,'["grammar","complexity"]','[]','{"instructions":"Combine simple sentences into complex ones."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000008','SKILL','重音与语调专项',2,8,'["pronunciation","stress"]','[]','{"instructions":"Mark stress patterns and read aloud."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000009','SCENARIO','情景对话：机场值机',2,10,'["scenario","travel"]','[]','{"instructions":"Role-play checking in at the airport with the AI assistant."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000010','SCENARIO','情景对话：餐厅点餐与投诉',2,10,'["scenario","dining"]','[]','{"instructions":"Order a meal and make a polite complaint."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000011','SCENARIO','情景对话：工作面试',3,12,'["scenario","work"]','[]','{"instructions":"Answer common interview questions with the AI interviewer."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000012','SCENARIO','自由对话：周末计划',1,10,'["scenario","free-talk"]','[]','{"instructions":"Chat about your weekend plans."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000013','SCENARIO','情景对话：酒店入住',2,10,'["scenario","hotel"]','[]','{"instructions":"Check into a hotel and ask about facilities."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000014','CORRECTION','纠错复述：时态错误集',2,6,'["correction","tense"]','[]','{"instructions":"Listen, correct, and repeat the sentences."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000015','CORRECTION','纠错复述：搭配错误集',2,6,'["correction","collocation"]','[]','{"instructions":"Fix the collocations and repeat."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000016','CORRECTION','纠错复述：句型升级',3,6,'["correction","sentence"]','[]','{"instructions":"Upgrade the sentences and repeat them."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000017','QUIZ','今日小测：词汇',2,3,'["quiz","vocabulary"]','[]','{"instructions":"Quick vocabulary quiz."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000018','QUIZ','今日小测：语法',2,3,'["quiz","grammar"]','[]','{"instructions":"Quick grammar quiz."}','PUBLISHED'),
('50000000-0000-0000-0000-000000000019','QUIZ','今日总结：自我复述',2,4,'["quiz","summary"]','[]','{"instructions":"Summarize what you practiced today in one minute."}','PUBLISHED');
