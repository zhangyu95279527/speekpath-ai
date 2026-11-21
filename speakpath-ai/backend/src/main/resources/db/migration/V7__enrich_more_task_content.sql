-- ---------------------------------------------------------------------------
-- 补齐更多任务内容项的练习材料（统一 items 结构，前端通用渲染 + 跟读评测）
-- ---------------------------------------------------------------------------

-- 昨日词汇快问快答：词汇问答（句子中嵌入目标词，note 给出释义）
UPDATE content_items SET content_json = '{
  "instructions": "Read each sentence aloud and recall the meaning of the target word in the note.",
  "exerciseType": "vocabulary",
  "items": [
    {"text": "I tend to procrastinate when a deadline feels far away.",
     "note": "procrastinate = 拖延，耽搁"},
    {"text": "The new policy will inevitably lead to higher prices.",
     "note": "inevitably = 不可避免地"},
    {"text": "She gave a concise summary of the report in two minutes.",
     "note": "concise = 简洁的，简明的"},
    {"text": "Regular exercise can alleviate stress and improve sleep.",
     "note": "alleviate = 缓解，减轻"}
  ]
}'::jsonb WHERE id = '50000000-0000-0000-0000-000000000001';

-- 同义替换与搭配扩展：同义替换 + 固定搭配
UPDATE content_items SET content_json = '{
  "instructions": "Read each sentence aloud, then replace the key words with the synonyms in the note.",
  "exerciseType": "synonym",
  "items": [
    {"text": "The results were very good, which exceeded our expectations.",
     "note": "very good → excellent / remarkable；exceed one''s expectations 为固定搭配"},
    {"text": "Many people think that money is the key to happiness.",
     "note": "think → believe / argue；the key to + 名词"},
    {"text": "We should solve the problem as soon as possible.",
     "note": "solve → address / tackle；as soon as possible → at the earliest opportunity"},
    {"text": "The government has made a big change to the education system.",
     "note": "big → significant / substantial；make a change to 为固定搭配"}
  ]
}'::jsonb WHERE id = '50000000-0000-0000-0000-000000000006';

-- 纠错复述：搭配错误集（正确句子 + 常见错误搭配提示）
UPDATE content_items SET content_json = '{
  "instructions": "Read the correct sentence aloud. The note shows the common collocation mistake to avoid.",
  "exerciseType": "collocation",
  "items": [
    {"text": "We decided to throw a party for her birthday.",
     "note": "常见错误：make a party → 正确：throw a party"},
    {"text": "He did me a great favor last week.",
     "note": "常见错误：make a favor → 正确：do a favor"},
    {"text": "I need to make a decision before Friday.",
     "note": "常见错误：do a decision → 正确：make a decision"},
    {"text": "She takes pride in her work.",
     "note": "常见错误：has pride in → 正确：take pride in"}
  ]
}'::jsonb WHERE id = '50000000-0000-0000-0000-000000000015';

-- 今日总结：自我复述（引导式复述句）
UPDATE content_items SET content_json = '{
  "instructions": "Read each prompt aloud, then use it as a model to summarize your own practice today.",
  "exerciseType": "summary",
  "items": [
    {"text": "Today I practiced shadowing sentences to improve my intonation.",
     "note": "开头：今天练了什么"},
    {"text": "The most useful expression I learned was ''take pride in something''.",
     "note": "中间：最有收获的表达"},
    {"text": "Tomorrow I will focus on speaking more fluently without long pauses.",
     "note": "结尾：明天的计划"},
    {"text": "Overall, I felt more confident speaking English today.",
     "note": "总结：今天的感受"}
  ]
}'::jsonb WHERE id = '50000000-0000-0000-0000-000000000019';
