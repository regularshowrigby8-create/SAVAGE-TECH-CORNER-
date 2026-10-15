# Factory stage: unity-mcp

Source factory: CoplayDev/unity-mcp (https://github.com/CoplayDev/unity-mcp), licence MIT,
commit 142ea0af4890337f0ff6658df61db23982313717.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Get face info with directions
- 2: Use the direction labels to pick faces

## Checks this factory defines

- structural integrity: `validate_script(level:"standard")`
- result.data.healthy -- if false, repair
- ready_for_tools == true
- `read_console` regularly to catch errors, warnings, and compilation status

## Prompt assets this factory ships

- .claude/prompts/nl-gameobject-suite.md
- .claude/prompts/nl-unity-suite-nl.md
- .claude/prompts/nl-unity-suite-t.md
- .claude/skills/blender-to-unity/SKILL.md
- .claude/skills/blender-to-unity/manual-verify.md
- .claude/skills/blender-to-unity/references/bridge-fidelity.md
- .claude/skills/mcp-source/SKILL.md
- .claude/skills/unity-mcp-skill/references/probuilder-guide.md
- .claude/skills/unity-mcp-skill/references/resources-reference.md

## What its own definition files describe

- Blender → Unity Model Handoff
- Preconditions
- Steps
- Notes
- Manual Verify — blender-to-unity
- Blender → Unity Bridge — Fidelity Test Results
- Results
- Verdict
- Gotchas discovered (worth automating around)
- Recovering procedural / node materials (bake)
- Capture more in one export (GLB flags)
- Practical rules
- Switch MCP for Unity Package Source
- allowed-tools
- Instructions
- 1. Parse arguments
- 2. Detect repo context
- 3. Discover target Unity projects
- 4. Build the package URL
- 5. Update each manifest
- 6. Report results
- ProBuilder Workflow Guide
- Availability
- Core Workflow: Always Get Info First

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
