-- ---------------------------------------------------------------------------
-- 补充影子跟读 / 重音与语调两个训练内容项的完整练习材料
-- （此前 content_json 仅有 instructions，前端无可展示内容）
-- ---------------------------------------------------------------------------

UPDATE content_items
SET content_json = '{
  "instructions": "Listen to the sample first, then shadow each sentence and match the stress and intonation.",
  "exerciseType": "shadowing",
  "sentences": [
    {"text": "The early bird catches the worm, but the second mouse gets the cheese.",
     "note": "重音落在 early / worm / cheese，注意并列对比语调"},
    {"text": "She sells seashells by the seashore every single summer.",
     "note": "s 与 sh 交替，语速放缓，保持清晰"},
    {"text": "I couldn''t believe how quickly the weekend had flown by.",
     "note": "couldn''t 与 quickly 重读，flown by 连读"},
    {"text": "Would you mind telling me where the nearest station is?",
     "note": "礼貌请求用升调，mind 与 nearest 重读"},
    {"text": "Practice makes progress, not perfection — just keep going.",
     "note": "对比重音：practice / progress / perfection"}
  ]
}'::jsonb
WHERE id = '50000000-0000-0000-0000-000000000003';

UPDATE content_items
SET content_json = '{
  "instructions": "Look at the stress pattern, listen to the example, then read the example aloud.",
  "exerciseType": "stress",
  "words": [
    {"word": "record",
     "pattern": "RE-cord (名词) / re-CORD (动词)",
     "example": "I need to reCORD a new REcord for my podcast."},
    {"word": "present",
     "pattern": "PRE-sent (名词) / pre-SENT (动词)",
     "example": "She will preSENT the PREsent at the ceremony."},
    {"word": "increase",
     "pattern": "IN-crease (名词) / in-CREASE (动词)",
     "example": "There was an INcrease in sales after we decided to inCREASE the budget."},
    {"word": "photograph",
     "pattern": "PHO-to-graph / pho-TO-gra-pher / pho-to-GRA-phic",
     "example": "The phoTOgrapher took a PHOtograph with photoGRAphic precision."},
    {"word": "economy",
     "pattern": "e-CO-no-my / e-co-NO-mic",
     "example": "The eCOnomy grew thanks to ecoNOmic reforms."}
  ]
}'::jsonb
WHERE id = '50000000-0000-0000-0000-000000000008';
