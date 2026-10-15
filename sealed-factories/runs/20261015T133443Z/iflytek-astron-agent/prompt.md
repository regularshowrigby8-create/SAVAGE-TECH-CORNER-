# Factory stage: iflytek-astron-agent

Source factory: iflytek/astron-agent (https://github.com/iflytek/astron-agent), licence Apache-2.0,
commit 5e27fc7afc598c3b8a30694288671584118b38dd.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- core/agent/.gitignore
- core/agent/Dockerfile
- core/agent/README.md
- core/agent/api/router.py
- core/agent/api/schemas/llm_message.py
- core/agent/exceptions/__init__.py
- core/agent/exceptions/agent_exc.py
- core/agent/exceptions/base.py
- core/agent/exceptions/cot_exc.py
- core/agent/exceptions/middleware_exc.py
- core/agent/service/plugin/base.py

## Prompt assets this factory ships

- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillDirectoryUploadResultDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillImportDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillImportResourceDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillSandboxConfigDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillSandboxRuntimeCredentialDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/dto/skill/SkillSandboxRuntimeRefDto.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillFileCreateFolderReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillFileCreateReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillFileMoveReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillFileRenameReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillFileUpdateContentReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/entity/vo/skill/SkillSandboxConfigReq.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/mapper/skill/SkillFileMapper.java
- console/backend/toolkit/src/main/java/com/iflytek/astron/console/toolkit/mapper/skill/SkillSandboxConfigMapper.java

## What its own definition files describe

- Python-generated files
- Virtual environments
- Use unified common package import module

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
