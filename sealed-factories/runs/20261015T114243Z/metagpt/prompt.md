# Factory stage: metagpt

Source factory: geekan/MetaGPT (https://github.com/FoundationAgents/MetaGPT), licence MIT,
commit 11cdf466d042aece04fc6cfd13b28e1a70341b1f.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- metagpt/ext/android_assistant/prompts/__init__.py
- metagpt/ext/spo/prompts/evaluate_prompt.py
- metagpt/ext/stanford_town/prompts/__init__.py
- metagpt/ext/stanford_town/prompts/action_object_v2.txt
- metagpt/ext/stanford_town/prompts/daily_planning_v6.txt
- metagpt/ext/stanford_town/prompts/decide_to_talk_v2.txt
- metagpt/ext/stanford_town/prompts/generate_event_triple_v1.txt
- metagpt/ext/stanford_town/prompts/generate_focal_pt_v1.txt
- metagpt/ext/stanford_town/prompts/generate_hourly_schedule_v2.txt
- metagpt/ext/stanford_town/prompts/generate_obj_event_v1.txt
- metagpt/ext/stanford_town/prompts/generate_pronunciatio_v1.txt
- metagpt/ext/stanford_town/prompts/insight_and_evidence_v1.txt
- metagpt/ext/stanford_town/prompts/iterative_convo_v1.txt
- metagpt/ext/stanford_town/prompts/memo_on_convo_v1.txt
- metagpt/ext/stanford_town/prompts/new_decomp_schedule_v1.txt
- metagpt/ext/stanford_town/prompts/planning_thought_on_convo_v1.txt
- metagpt/ext/stanford_town/prompts/poignancy_action_v1.txt
- metagpt/ext/stanford_town/prompts/poignancy_chat_v1.txt
- metagpt/ext/stanford_town/prompts/poignancy_event_v1.txt
- metagpt/ext/stanford_town/prompts/poignancy_thought_v1.txt
- metagpt/ext/stanford_town/prompts/summarize_chat_relationship_v2.txt
- metagpt/ext/stanford_town/prompts/summarize_conversation_v1.txt
- metagpt/ext/stanford_town/prompts/wake_up_hour_v1.txt
- metagpt/ext/stanford_town/prompts/whisper_inner_thought_v1.txt

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
