# Factory stage: gmail-mcp-server

Source factory: GongRzhe/Gmail-MCP-Server (https://github.com/GongRzhe/Gmail-MCP-Server), licence MIT,
commit a890d19189bbc1325b8728fab830fc278cfd8804.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Checks this factory defines

- file permissions
- the detailed error messages for specific failures

## What its own definition files describe

- Gmail AutoAuth MCP Server
- Features
- Installation & Authentication
- Installing via Smithery
- Installing Manually
- Docker Support
- Cloud Server Authentication
- Setup Instructions for Cloud Environment
- Available Tools
- 1. Send Email (`send_email`)
- 2. Draft Email (`draft_email`)
- 3. Read Email (`read_email`)
- 4. **Download Attachment (`download_attachment`)**
- 5. Search Emails (`search_emails`)
- 6. Modify Email (`modify_email`)
- 7. Delete Email (`delete_email`)
- 8. List Email Labels (`list_email_labels`)
- 9. Create Label (`create_label`)
- 10. Update Label (`update_label`)
- 11. Delete Label (`delete_label`)
- 12. Get or Create Label (`get_or_create_label`)
- 13. Batch Modify Emails (`batch_modify_emails`)
- 14. Batch Delete Emails (`batch_delete_emails`)
- 14. Create Filter (`create_filter`)

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
