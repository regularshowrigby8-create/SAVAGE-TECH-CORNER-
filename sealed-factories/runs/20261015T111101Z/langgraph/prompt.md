# Factory stage: langgraph

Source factory: langchain-ai/langgraph (https://github.com/langchain-ai/langgraph), licence MIT,
commit ccb851effb7b4c6255add86dbeed577061475aff.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Workers this factory defines

- libs/cli/js-examples/src/agent/graph.ts
- state.messages[0]._getType(),
- libs/cli/js-examples/src/agent/state.ts
- libs/cli/js-monorepo-example/apps/agent/langgraph.json
- libs/cli/js-monorepo-example/apps/agent/package.json
- libs/cli/js-monorepo-example/apps/agent/src/graph.ts
- libs/cli/js-monorepo-example/apps/agent/src/state.ts
- libs/cli/js-monorepo-example/apps/agent/tsconfig.json
- libs/cli/python-monorepo-example/apps/agent/langgraph.json
- libs/cli/python-monorepo-example/apps/agent/pyproject.toml
- libs/cli/python-monorepo-example/apps/agent/src/agent/__init__.py
- libs/cli/python-monorepo-example/apps/agent/src/agent/graph.py
- libs/cli/python-monorepo-example/apps/agent/src/agent/state.py
- libs/cli/uv-examples/monorepo/apps/agent/.env.example
- libs/cli/uv-examples/monorepo/apps/agent/langgraph.json
- libs/cli/uv-examples/monorepo/apps/agent/pyproject.toml
- libs/cli/uv-examples/monorepo/apps/agent/src/agent/graph.py
- libs/cli/uv-examples/simple/src/agent/graph.py

## Checks this factory defines

- if the .env file exists in the same directory as the Dockerfile
- for unknown keys before validation so they show alongside any error.

## What its own definition files describe

- AGENTS Instructions
- Corridor security analysis
- Libraries
- Dependency map
- Why use LangGraph?
- LangGraph ecosystem
- Documentation
- Additional resources
- Acknowledgements
- Define the function that determines whether to continue or not
- Define the function that calls the model
- Define the function to execute tools
- Define a new graph
- Define the two nodes we will cycle between
- Set the entrypoint as `agent`
- This means that this node is the first one called
- We now add a conditional edge
- We now add a normal edge from `tools` to `agent`.
- This means that after `tools` is called, `agent` node is called next.
- Finally, we compile it!
- This compiles it into a LangChain Runnable,
- meaning you can use it as you would any other runnable
- Build the graph
- Add the node

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
