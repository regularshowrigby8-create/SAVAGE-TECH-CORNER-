# Factory stage: mcp-chrome

Source factory: hangwin/mcp-chrome (https://github.com/hangwin/mcp-chrome), licence MIT,
commit f48e71751e00bc09725c7e173423cff4f2ccd12a.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- app/chrome-extension/entrypoints/sidepanel/components/agent/AttachmentPreview.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/ChatInput.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/CliSettings.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/ConnectionStatus.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/MessageItem.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/MessageList.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/ProjectCreateForm.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/ProjectSelector.vue
- app/chrome-extension/entrypoints/sidepanel/components/agent/index.ts
- Chat Components
- app/native-server/src/agent/db/index.ts
- app/native-server/src/agent/db/schema.ts
- app/native-server/src/agent/directory-picker.ts
- app/native-server/src/agent/engines/types.ts
- app/native-server/src/agent/message-service.ts
- app/native-server/src/agent/project-types.ts
- app/native-server/src/agent/storage.ts
- app/native-server/src/agent/tool-bridge.ts
- app/native-server/src/agent/types.ts
- 你是一名资深的【信息架构与视觉沟通专家】。
- 你是一名顶级的【浏览器自动化与扩展开发专家】。

## Prompt assets this factory ships

- prompt/content-analize.md
- prompt/excalidraw-prompt.md
- prompt/modify-web.md

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
