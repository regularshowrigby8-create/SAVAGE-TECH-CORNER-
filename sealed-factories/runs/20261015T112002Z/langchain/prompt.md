# Factory stage: langchain

Source factory: langchain-ai/langchain (https://github.com/langchain-ai/langchain), licence MIT,
commit 8ace87e7a6493d41d4c61bb41a637052367b27f3.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- libs/core/langchain_core/prompts/__init__.py
- libs/core/langchain_core/prompts/dict.py
- libs/core/langchain_core/prompts/image.py
- libs/core/langchain_core/prompts/message.py
- libs/core/langchain_core/prompts/structured.py
- libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/are_all_true_prompt.txt
- libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/check_facts.txt
- libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/create_facts.txt
- libs/langchain/langchain_classic/chains/llm_summarization_checker/prompts/revise_summary.txt
- libs/langchain/langchain_classic/indexes/prompts/__init__.py
- libs/langchain/langchain_classic/indexes/prompts/entity_extraction.py
- libs/langchain/langchain_classic/indexes/prompts/entity_summarization.py
- libs/langchain/langchain_classic/indexes/prompts/knowledge_triplet_extraction.py
- libs/langchain/langchain_classic/prompts/__init__.py
- libs/langchain/langchain_classic/prompts/base.py
- libs/langchain/langchain_classic/prompts/chat.py
- libs/langchain/langchain_classic/prompts/example_selector/__init__.py
- libs/langchain/langchain_classic/prompts/example_selector/base.py
- libs/langchain/langchain_classic/prompts/example_selector/length_based.py
- libs/langchain/langchain_classic/prompts/example_selector/ngram_overlap.py
- libs/langchain/langchain_classic/prompts/example_selector/semantic_similarity.py
- libs/langchain/langchain_classic/prompts/few_shot.py
- libs/langchain/langchain_classic/prompts/few_shot_with_templates.py
- libs/langchain/langchain_classic/prompts/loading.py

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
