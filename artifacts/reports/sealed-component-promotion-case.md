# The case for promoting the sealed component to `main` - and what is still missing

The sealed factory set is on the branch it owns, `sealed-factory`, published there by the pipeline
rather than by hand. It does not go to `main` on the strength of this document: it goes to `main`
when its own evidence says it is functional, and this file is the list of what that evidence would
have to be, what of it exists today, and what does not. Read it as the answer to one question - "is
it 100% functional?" - with the answer broken into the parts that can be checked.

## 1. What would be promoted

One removable component: the sealed factory set (171 records with their payloads, licence texts and
vendor records, sealed and hash-verified), the runtime that runs inside it, the licence store and
its index, the self-check it carries, and the tooling it is removed by. It is one directory plus
the files its own removal record names, which is what makes it removable as one unit rather than as
a directory that leaves pieces behind.

## 2. What the gates already prove

Every claim below is a result of a hosted run on the commit the branch was published from; nothing
here was measured anywhere else.

- **It is intact.** The seal verifies: every payload, every licence text and every runtime module
  hashes to what the seal records, and the seal's root hash is `e229a4742f0447…` as recorded in
  its own `SEAL.sha256`. Published: `sealed-factory` at `ae9d7c1`, from `78afd64`, by publish run
  `38735965140`.
- **It is fit.** The self-check the component carries reports `FUNCTIONAL` with 0 blocking
  findings, and names what it is not counting as fitness instead of hiding it: 36 records whose
  content is withheld because their licences do not permit copying, and 2 carried slices that
  define no stage, worker, gate or prompt this runtime can run. Growth run `38735135387` reads that
  verdict back before it commits.
- **It runs itself, in the tree that is published.** The publish gates run the sealed environment
  inside the snapshot that is about to be pushed and refuse a run that skipped a role, failed a
  step or failed a check. On the branch under review the same environment reports 48 tools, 201
  agent roles and 201 agent instances executed - one per role - with 201 checks passed and no
  failed step.
- **It works with the network switched off.** A separate job runs the whole environment with
  `SEALED_NO_NETWORK=1` and judges the run against the switch's own evidence in the researcher's
  artefact, not merely against its exit status.
- **It tests itself where it lands.** The component's own tests run in the publish job and in the
  belt, and the belt runs the sealed set as its first station: the set feeds the belt, it is not
  fed by it.
- **The floors hold.** The growth judge refuses a set that shrank: 171 records, 106 imported, 134
  operational in plan mode, and 135 carrying content under 149 licence texts are floors now rather
  than observations.
- **The licence wall holds.** 149 imported licence texts are stored and indexed, the index is
  pinned by the seal as component data, and the store tool cannot write a record's licence or its
  redistributability - so no import can widen the wall by storing a text.

## 3. What is not proven, and is not claimed

These are the reasons the component is on `sealed-factory` and not on `main`. Each is a fact about
this tree today, not a plan.

- **36 of the 171 records carry no content.** 35 of them are held back by a licence that does not
  permit copying (22 repositories publish no licence at all, 12 publish one that blocks
  redistribution, one is unclassifiable). Their records are blueprints: the metadata is there, the
  code is not, and no amount of importing changes that. What they lack is permission, not
  structure.
- **2 carried slices define nothing this runtime reads.** They are named in the self-check's
  states and in the report, and they are carried because they are useful references, not because
  they run.
- **The GitHub data tools need the network to answer.** Offline they refuse with the reason, which
  is what the air-gapped tests assert. Nothing here claims GitHub data is available air-gapped.
- **14 of the 42 catalogued MCP servers are not installed on the runner**, and 10 of the installed
  entries have no verified launch command. The environment binds none of them by default and
  passes every check anyway.
- **The component has never been merged to `main`**, so no run has ever exercised it from the
  default branch. Its evidence is from `arena/5223ef6f-savage-tech-corner` and from
  `sealed-factory`.

## 4. What would make the case complete

`main` is off-limits until the component is functional beyond doubt, and "beyond doubt" has a
shape:

1. **A green belt on the commit that would be merged.** The belt's first station is the sealed set,
   so a green belt on the merge commit is the component running inside the belt that guards
   `main`.
2. **A publish that ran with the copy's own environment as a gate** - which is now one of the
   publish gates rather than an intention, and its result belongs in section 2 of this file when it
   has run.
3. **The same evidence read from the default branch** once it is there: the air-gapped job, the
   publish job and the growth run all run on `main` today for this repository, and a component that
   works from a feature branch and not from the default branch is a component with a branch
   problem.
4. **A decision about the 35 licence-held records**, made and recorded rather than implied: either
   their blueprints stay in the set with the reason named, or they leave it. That is a judgement
   about what the component is for, and it is the user's to make, not this file's.
5. **A stated rollback.** The removal record is the rollback: one directory and the files it names,
   and the case is complete when that record has been exercised once on a tree that is not the one
   that wrote it.

Until at least the first three hold, the honest description of the component is the one the
published branch already carries: functional, self-verifying, on its own branch, and not yet on
`main`.
