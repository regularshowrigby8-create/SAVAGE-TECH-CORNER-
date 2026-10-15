# Factory stage: chrome-devtools-mcp

Source factory: ChromeDevTools/chrome-devtools-mcp (https://github.com/ChromeDevTools/chrome-devtools-mcp), licence Apache-2.0,
commit 28fb230dde70926a7167ce7d5057473453053bf8.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- 1: Record a Performance Trace
- 2: Analyze LCP Insights
- 3: Identify the LCP Element
- 4: Check the Network Waterfall
- 5: Inspect HTML for Common Issues
- 1: Find and Read Configuration
- 2: Triage Common Connection Errors
- 3: Read Known Issues
- 4: Formulate a Configuration
- 5: Run Diagnostic Commands
- 6: Check GitHub for Existing Issues

## Checks this factory defines

- `scores` (0-1 scale). A score < 1 indicates violations.
- `audits.failed` count.
- images for `alt` text.
- earlier login/handshake responses for `Set-Cookie` directives:
- for `CookieIssue` entries, such as:
- if `Domain` restricts subdomains.
- `list_console_messages(types: ["issue"])` for browser rejection reasons.

## Prompt assets this factory ships

- skills/a11y-debugging/SKILL.md
- skills/a11y-debugging/references/a11y-snippets.md
- skills/chrome-devtools-cli/SKILL.md
- skills/chrome-devtools-cli/references/installation.md
- skills/chrome-devtools/SKILL.md
- skills/cookie-debugging/SKILL.md
- skills/debug-optimize-lcp/SKILL.md
- skills/debug-optimize-lcp/references/elements-and-size.md
- skills/debug-optimize-lcp/references/lcp-breakdown.md
- skills/debug-optimize-lcp/references/lcp-snippets.md
- skills/debug-optimize-lcp/references/optimization-strategies.md
- skills/memory-leak-debugging/SKILL.md
- skills/memory-leak-debugging/references/common-leaks.md
- skills/troubleshooting/SKILL.md

## What its own definition files describe

- Instructions
- Rules for TypeScript
- Rules for Testing
- Structure and Separation of Concerns
- Mocking Guidelines (`tests/mocks.ts`)
- Assertions and Sinon Best Practices
- Test Cleanliness
- Chrome DevTools for agents
- Key features
- Disclaimers
- **Usage statistics**
- Update checks
- Requirements
- Getting started
- MCP Client configuration
- Your first prompt
- Tools
- Configuration
- Advanced Usage
- Integrating as a browser subagent
- Core Concepts
- Workflow Patterns
- 1. Automated Audit (Lighthouse)
- 2. Browser Issues & Audits

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
