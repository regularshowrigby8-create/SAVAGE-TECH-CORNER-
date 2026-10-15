# Factory stage: unicomai-wanwu

Source factory: UnicomAI/wanwu (https://github.com/UnicomAI/wanwu), licence Apache-2.0,
commit f21cd89bfe658c2ad6a246b2eaaec050beb689db.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- configs/microservice/bff-service/configs/agent-skills/clawhub/wechat-article-publisher/agents/openai.yaml
- configs/microservice/bff-service/configs/wga/agents/DeepResearch/config.yaml
- configs/microservice/bff-service/configs/wga/agents/SkillImport/config.yaml
- configs/microservice/bff-service/configs/wga/agents/SkillPreview/config.yaml
- pkg/wga-sandbox/internal/runner/eino/agent/shared/sanitizer.go
- web/src/views/agent/components/prompt/compare.vue
- web/src/views/agent/components/prompt/index.vue
- web/src/views/agent/components/prompt/promptDialog.vue
- web/src/views/agent/components/prompt/promptField.vue
- web/src/views/agent/constants.js

## Prompt assets this factory ships

- callback/static/prompts/docqa_prompt.txt
- configs/microservice/bff-service/configs/agent-skills/clawhub/tencent-docs/doc/doc_format/prompt/pure_text_system_prompt.txt
- configs/microservice/bff-service/configs/agent-skills/clawhub/tencent-docs/doc/doc_format/prompt/scenario_recognition_prompt.txt
- configs/microservice/bff-service/configs/agent-skills/clawhub/tencent-docs/doc/doc_format/prompt/style_customization_prompt.txt
- internal/agent-service/service/agent-message-flow/prompt/system_prompt.go
- internal/agent-service/service/agent-message-flow/prompt/system_prompt_knowledge.go
- internal/agent-service/service/agent-message-flow/prompt/system_prompt_new.go
- internal/agent-service/service/agent-message-flow/prompt/system_prompt_no_citation_knowledge.go
- internal/agent-service/service/agent-message-flow/prompt/systemt_supervisor_prompt.go
- web/src/views/adminCenter/prompt/detail.vue
- web/src/views/adminCenter/prompt/index.vue
- web/src/views/agent/components/prompt/compare.vue
- web/src/views/agent/components/prompt/index.vue
- web/src/views/agent/components/prompt/promptDialog.vue
- web/src/views/agent/components/prompt/promptField.vue
- web/src/views/generalAgent/components/skills/workspaceConstants.js
- web/src/views/templateSquare/prompt/promptTempSquare.vue
- web/src/views/templateSquare/skills/skillWorkshop.vue
- web/src/views/tool/prompt/index.vue

## What its own definition files describe

- 纯文本转XML结构化任务
- 输入格式
- 示例说明
- 示例1：标签映射
- 示例2：结构补充
- 示例3：嵌套结构处理
- 模板结构说明
- 输出格式
- 文档场景识别与标题生成任务
- 支持的场景
- 需要识别的文本内容
- 可用样式名
- 中文字号对应关系
- 可用颜色对应关系
- 对齐方式对应关系
- 行距对应关系
- 用户请求

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
