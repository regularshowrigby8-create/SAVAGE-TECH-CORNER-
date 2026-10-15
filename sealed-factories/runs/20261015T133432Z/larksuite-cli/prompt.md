# Factory stage: larksuite-cli

Source factory: larksuite/cli (https://github.com/larksuite/cli), licence MIT,
commit 017b19a793ad71794a02887ede04757f3d82e10d.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Prompt assets this factory ships

- internal/qualitygate/skillscan/testdata/skills/lark-demo/SKILL.md
- skills/lark-im/references/card/components/hr.md
- skills/lark-note/SKILL.md
- skills/lark-slides/references/cli/lark-slides-xml-presentation-slide-get.md
- skills/lark-slides/references/iconpark-index.json
- skills/lark-slides/references/iconpark.md
- skills/lark-slides/references/lark-slides-add-slide.md
- skills/lark-slides/references/lark-slides-create.md
- skills/lark-slides/references/lark-slides-delete-slide.md
- skills/lark-slides/references/lark-slides-edit-workflows.md
- skills/lark-slides/references/lark-slides-history.md
- skills/lark-slides/references/lark-slides-media-upload.md
- skills/lark-slides/references/lark-slides-pptx-template-workflows.md
- skills/lark-slides/references/lark-slides-replace-slide.md
- skills/lark-slides/references/lark-slides-screenshot.md
- skills/lark-slides/references/lark-slides-xml-presentation-slide-get.md
- skills/lark-slides/references/lark-slides-xml-presentation-slide-replace.md
- skills/lark-slides/references/lark-slides-xml-presentations-get.md
- skills/lark-slides/references/slides_chart_demo.xml
- skills/lark-slides/references/slides_xml_schema_definition.xml
- skills/lark-slides/references/troubleshooting.md
- skills/lark-slides/references/validation-checklist.md
- skills/lark-slides/references/xml-schema-quick-ref.md
- skills/lark-slides/scripts/xml_text_overlap_lint_test.py

## What its own definition files describe

- comment
- 分割线 `hr`
- 最小示例
- Compatibility entry
- slides +xml-get（单页读取兼容入口）
- IconPark 图标索引（兼容入口）
- IconPark 图标（兼容入口）
- slides +add-slide（兼容入口）
- slides +create（创建飞书幻灯片）（兼容入口）
- slides +delete-slide（兼容入口）
- 编辑已有 PPT：读-改-写闭环（兼容入口）
- slides history（历史版本与回滚）（兼容入口）
- slides +media-upload（兼容入口）
- PPT Template Rewrite Principles（兼容入口）
- slides +replace-slide（兼容入口）
- slides +screenshot（兼容入口）
- slides +xml-get（兼容入口）
- Slides 图表示例 XML（兼容入口）
- Slides XML Schema 定义（兼容入口）
- Troubleshooting（兼容入口）
- Validation Checklist（兼容入口）
- XML Schema 快速参考（兼容入口）
- Copyright (c) 2026 Lark Technologies Pte. Ltd.
- SPDX-License-Identifier: MIT

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
