# Factory stage: dbt-mcp

Source factory: dbt-labs/dbt-mcp (https://github.com/dbt-labs/dbt-mcp), licence Apache-2.0,
commit a9db3f0826f8cf2ed12fe787ef65d3204c57f251.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- src/dbt_mcp/prompts/__init__.py
- src/dbt_mcp/prompts/dbt_cli/args/full_refresh.md
- src/dbt_mcp/prompts/dbt_cli/args/sample.md
- src/dbt_mcp/prompts/dbt_cli/args/sql_query.md
- src/dbt_mcp/prompts/dbt_cli/build.md
- src/dbt_mcp/prompts/dbt_cli/docs.md
- src/dbt_mcp/prompts/dbt_cli/list.md
- src/dbt_mcp/prompts/dbt_cli/test.md
- src/dbt_mcp/prompts/dbt_codegen/args/case_sensitive_cols.md
- src/dbt_mcp/prompts/dbt_codegen/args/database_name.md
- src/dbt_mcp/prompts/dbt_codegen/args/generate_columns.md
- src/dbt_mcp/prompts/dbt_codegen/args/include_data_types.md
- src/dbt_mcp/prompts/dbt_codegen/args/include_descriptions.md
- src/dbt_mcp/prompts/dbt_codegen/args/leading_commas.md
- src/dbt_mcp/prompts/dbt_codegen/args/materialized.md
- src/dbt_mcp/prompts/dbt_codegen/args/model_name.md
- src/dbt_mcp/prompts/dbt_codegen/args/model_names.md
- src/dbt_mcp/prompts/dbt_codegen/args/schema_name.md
- src/dbt_mcp/prompts/dbt_codegen/args/source_name.md
- src/dbt_mcp/prompts/dbt_codegen/args/table_name.md
- src/dbt_mcp/prompts/dbt_codegen/args/table_names.md
- src/dbt_mcp/prompts/dbt_codegen/args/tables.md
- src/dbt_mcp/prompts/dbt_codegen/args/upstream_descriptions.md
- src/dbt_mcp/prompts/lsp/args/column_name.md

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
