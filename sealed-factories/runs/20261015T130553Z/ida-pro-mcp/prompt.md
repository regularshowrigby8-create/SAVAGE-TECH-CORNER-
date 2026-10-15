# Factory stage: ida-pro-mcp

Source factory: mrexodia/ida-pro-mcp (https://github.com/mrexodia/ida-pro-mcp), licence MIT,
commit c133c3853faa111a9b00ee615c013b720d0c4acd.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- skills/idapython/docs/ida_bitrange.md
- skills/idapython/docs/ida_entry.md
- skills/idapython/docs/ida_idaapi.md
- skills/idapython/docs/ida_idc.md
- skills/idapython/docs/ida_idc.rst
- skills/idapython/docs/ida_idp.md
- skills/idapython/docs/ida_ieee.md
- skills/idapython/docs/ida_libfuncs.md
- skills/idapython/docs/ida_lines.md
- skills/idapython/docs/ida_merge.md
- skills/idapython/docs/ida_mergemod.md
- skills/idapython/docs/ida_netnode.md
- skills/idapython/docs/ida_offset.md
- skills/idapython/docs/ida_pro.md
- skills/idapython/docs/ida_problems.md
- skills/idapython/docs/ida_search.md
- skills/idapython/docs/ida_srclang.md
- skills/idapython/docs/ida_strlist.md
- skills/idapython/docs/ida_tryblks.md
- skills/idapython/docs/ida_undo.md
- skills/idapython/docs/ida_undo.rst
- skills/idapython/docs/idaapi.md
- skills/idapython/docs/idaapi.rst
- skills/idapython/docs/init.md

## What its own definition files describe

- ida_bitrange
- Key Class
- bitrange_t
- See Also
- ida_entry
- Key Functions
- Flags (AEF_*)
- ida_idaapi
- Key Constants
- Address Types
- Plugin Return Codes
- Key Classes
- plugin_t
- plugmod_t
- ida_idc
- ida_idp
- Key Classes/Functions
- Instruction Analysis
- Processor Info
- processor_t
- Instruction Features (CF_*)
- ida_ieee
- fpvalue_t
- Error Codes

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
