# Factory 2 Subsystem Changes

## Components Updated
- `agents/agent_correctness.py`: Added multi-file package scope resolution to prevent cross-file private function dead code false positives.
- `manager/guidance.py`: Updated manager evidence review engine to explicitly treat `Verdict: FIX-FIRST` as a fatal blocker (`has_fatal_blocker = True`).
- `scripts/model_debugger.py`: Upgraded `process_inbox()` to generate real, non-empty candidate modifications via AST `ReturnTypeAnnotator` and diff validation.
- `scripts/model_finder.py`: Integrated `fetch_curated_sources()` into `scan_for_models()` for continuous community model ingestion.
- `app/src/browser_shell.py`: Refactored theme tokens to `ModernLuminescenceUITheme` (`ModernLuminescenceGlass2`), eliminating foreign token collisions and wiring `ACCENT_CYAN` into layout specs.
