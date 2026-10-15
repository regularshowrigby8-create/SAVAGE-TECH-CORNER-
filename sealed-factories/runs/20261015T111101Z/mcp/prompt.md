# Factory stage: mcp

Source factory: awslabs/mcp (https://github.com/awslabs/mcp), licence Apache-2.0,
commit 8840003820a78dd018561d55c7ab12fcdd708464.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- by-step implementation plan

## Workers this factory defines

- must have trust relationship with `iotsitewise.amazonaws.com`
- and Objectives

## Checks this factory defines

- for any disassociated time series that might be relevant
- for missing property values or stale data
- Existing Jobs for Error Bucket (Recommended):**
- previous jobs: `list_bulk_import_jobs()`
- S3 error bucket for detailed error reports
- job status messages
- existing jobs: list_bulk_import_jobs()
- get_asset_property_value_history for historical data
- if this parameter is already added

## Prompt assets this factory ships

- src/aws-iot-sitewise-mcp-server/awslabs/aws_iot_sitewise_mcp_server/prompts/__init__.py
- src/aws-iot-sitewise-mcp-server/awslabs/aws_iot_sitewise_mcp_server/prompts/asset_hierarchy.py
- src/aws-iot-sitewise-mcp-server/awslabs/aws_iot_sitewise_mcp_server/prompts/bulk_import_workflow.py
- src/aws-iot-sitewise-mcp-server/awslabs/aws_iot_sitewise_mcp_server/prompts/data_ingestion.py
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/README.md
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/__init__.py
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/decorator.py
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/graviton_migration.py
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/savings_plans.py
- src/billing-cost-management-mcp-server/awslabs/billing_cost_management_mcp_server/prompts/types.py
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/dal_implementation/generate_dal_workflow_steps.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/json_generation_guide.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/dynamodb_data_model_schema_converter_complete.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/dynamodb_data_model_validation_complete.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/dynamodb_data_modeling_complete.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/generate_data_access_layer_complete.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/generate_data_access_layer_schema_not_found.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/next_steps/generate_resources_complete.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/transform_model_validation_result.md
- src/dynamodb-mcp-server/awslabs/dynamodb_mcp_server/prompts/usage_data_generator.md
- src/openapi-mcp-server/awslabs/openapi_mcp_server/prompts/__init__.py
- src/openapi-mcp-server/awslabs/openapi_mcp_server/prompts/generators/__init__.py
- src/openapi-mcp-server/awslabs/openapi_mcp_server/prompts/generators/workflow_prompts.py
- src/openapi-mcp-server/awslabs/openapi_mcp_server/prompts/models.py

## What its own definition files describe

- Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
- # Licensed under the Apache License, Version 2.0 (the "License");
- you may not use this file except in compliance with the License.
- You may obtain a copy of the License at
- #     http://www.apache.org/licenses/LICENSE-2.0
- # Unless required by applicable law or agreed to in writing, software
- distributed under the License is distributed on an "AS IS" BASIS,
- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
- See the License for the specific language governing permissions and
- limitations under the License.
- 🎯 AWS IoT SiteWise Bulk Import Workflow
- **Initial Assessment Questions**
- **Step 1: Discovery & Assessment (CRITICAL FIRST STEPS)**
- **Step 2: Data Preparation Requirements**
- **Step 3: Error Bucket Strategy**
- **Step 4: IAM Role Setup**
- **Step 5: Job Configuration**
- **Step 6: Post-Job Execution (CRITICAL TIMING)**
- **Step 7: Storage Configuration (For Historical Data)**
- **Step 8: Ingestion Mode Selection**
- **Step 9: Best Practices Checklist**
- **Recommended Workflow Template:**
- Billing and Cost Management MCP Prompts
- What are Prompts?

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
