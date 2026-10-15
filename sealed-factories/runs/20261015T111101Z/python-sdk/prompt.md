# Factory stage: python-sdk

Source factory: modelcontextprotocol/python-sdk (https://github.com/modelcontextprotocol/python-sdk), licence MIT,
commit 754254c08fe1c3eda18b69da3d336106fe8d72d6.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- for duplicates

## Prompt assets this factory ships

- docs_src/prompts/tutorial001.py
- docs_src/prompts/tutorial002.py
- docs_src/prompts/tutorial003.py
- docs_src/prompts/tutorial004.py
- docs_src/prompts/tutorial005.py
- docs_src/prompts/tutorial006.py
- docs_src/skills/tutorial001_client.py
- examples/stories/prompts/README.md
- examples/stories/prompts/client.py
- examples/stories/prompts/server.py
- examples/stories/prompts/server_lowlevel.py
- examples/stories/skills/README.md
- src/mcp/server/mcpserver/prompts/__init__.py
- src/mcp/server/mcpserver/prompts/base.py
- src/mcp/server/mcpserver/prompts/manager.py

## What its own definition files describe

- prompts
- Run it
- stdio (default — the client spawns the server as a subprocess)
- same, against the lowlevel-API server variant
- What to look at
- Caveats
- Spec
- See also
- skills

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
