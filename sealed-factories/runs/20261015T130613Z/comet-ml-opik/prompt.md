# Factory stage: comet-ml-opik

Source factory: comet-ml/opik (https://github.com/comet-ml/opik), licence Apache-2.0,
commit b4b8a0604fcc4f0e8848e4b7250483d7666d9c44.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- sdks/opik_optimizer/scripts/arc_agi/prompts/user_prompt.md
- sdks/python/src/opik/api_objects/prompt/chat/__init__.py
- sdks/python/src/opik/api_objects/prompt/text/__init__.py
- sdks/python/src/opik/rest_api/prompts/__init__.py
- sdks/python/src/opik/rest_api/prompts/types/create_prompt_version_detail_template_structure.py
- sdks/python/src/opik/rest_api/prompts/types/prompt_write_template_structure.py
- sdks/python/src/opik/rest_api/prompts/types/prompt_write_type.py
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/index.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/DeletePromptRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/GetPromptByCommitRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/GetPromptVersionByIdRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/GetPromptVersionByNumberRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/GetPromptVersionsRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/GetPromptsRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/PromptUpdatable.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/PromptVersionCommitsRequestPublic.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/PromptVersionEnvironmentUpdate.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/PromptVersionIdsRequestDetail.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/client/requests/RestorePromptVersionRequest.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/index.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/types/PromptWriteType.ts
- sdks/typescript/src/opik/rest_api/api/resources/prompts/types/index.ts
- sdks/typescript/src/opik/rest_api/serialization/resources/prompts/client/index.ts
- sdks/typescript/src/opik/rest_api/serialization/resources/prompts/index.ts

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
