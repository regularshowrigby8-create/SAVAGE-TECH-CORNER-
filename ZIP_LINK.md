# Factory setup archive

Download: `https://github.com/regularshowrigby8-create/SAVAGE-TECH-CORNER-/raw/factory-2/factories/factory-setup.zip`

The archive is generated from the repaired `factory-1` and `factory-2` branch worktrees and contains two top-level directories:

- `factory-1/`: scanner implementation, behavioral tests, and Factory 1 gate.
- `factory-2/`: agent pipeline, fail-closed critic, single-job workflow, gate, reports, and packaging sources.

It intentionally excludes Git metadata and nested ZIP archives. Verify the archive locally with `unzip -t factories/factory-setup.zip`.
