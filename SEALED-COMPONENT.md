# The sealed factory, on the branch it owns

This branch carries the sealed factory component and nothing else. It is not `main`: the component is
published here so it can be reviewed, run and removed on its own, and it will only ever be proposed for
`main` once this branch's own evidence says it is functional. Nothing in this branch was pushed by hand.

- **Seal root**: `2f694c4a5b0f3e4e8606cd7c512bf5b73c044c8d95b6939089a8de67080362ea` (`sha256(SEAL.json)`, recorded in
  `sealed-factories/SEAL.sha256`)
- **Records**: 171 sealed factories, 135 carrying
  content, 149 licence texts in the seal, 149
  in the licence store
- **Self-check verdict**: `FUNCTIONAL` - 0 blocking finding(s);
  named states: {'content-withheld': 36, 'carried-without-definitions': 2} (a licence that does not permit copying, and slices that define no
  stage, worker, gate or prompt, are states and are named as such rather than counted as fitness)
- **Published from**: `f5cb0179061bc8e664de6b9fd841c3ab877c1b5f` on `arena/5223ef6f-savage-tech-corner`

## What it is

One removable component: the sealed factory set (`sealed-factories/`, 171
factories with their payloads and licence texts, sealed and hash-verified), the tooling that grows and
serves it (16 files), its tests
(9), its workflows (2) and its document.
Removing it is what `SEAL.json`'s `removal` record describes, and no file outside it is needed to run it.

## Running it

```
python3 sealed-factories/runtime/cli.py verify                      # recompute every sealed hash
python3 sealed-factories/runtime/cli.py selfcheck                   # recheck the set with the factories it carries
python3 sealed-factories/runtime/cli.py run --max-model-calls 0     # the swarm, plan mode, no keys
python3 -m unittest discover -s shell/tests                         # the component's own tests
```

The licence wall travels with the component: only factories whose licences permit copying have their
content carried, every carried record has its licence text beside it, and records whose licences do not
permit it are blueprint-only - named as withheld, never quietly dropped.
