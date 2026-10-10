# Factory 2 changes

- Made quality and architecture checks branch-aware, eliminating false Factory 1 cross-branch findings.
- Made the critic fail closed: it reports `FIX-FIRST` for blocking evidence and `SHIP` only for clean generated reports.
- Made the single-job runner execute every check and propagate failures.
- Rewrote the gate to validate syntax, exactly one workflow job, fresh pipeline results, Markdown path claims, and script wiring.
- Rebuilt `factories/factory-setup.zip` with separate `factory-1/` and `factory-2/` roots, excluding Git metadata and nested archives.
- Local commit: `daeb11c`.
