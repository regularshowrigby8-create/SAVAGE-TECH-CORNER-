# Factory stage: ashishyesale7-lgflutterstarterkit

Source factory: AshishYesale7/LGFlutterStarterKit (https://github.com/AshishYesale7/LGFlutterStarterKit), licence MIT,
commit 2378a5276b876666c45ccd703ae9f7acbf04bb37.

You are running the stage this factory defines, inside a multi-factory pipeline. Work only from the brief and the factory's own definitions below; state plainly what you cannot do.

## Brief

Take this repository's product (the Savage Browser Android app and its factories) one stage further: name the stage you would add, the artefacts it produces, and the checks that would fail it.

## Stages this factory defines

- duration: 1.0–1.5 seconds for smooth orbit.
- 1: Analyze Source Code
- 2: Convert with Architecture Alignment
- 3: Verify
- 1: Device Detection
- 2: Launch Emulator
- 3: Run App on Emulator
- 4: Screenshots for Documentation
- 5: Screen Recording
- 6: Visual Debugging
- 7: Automated UI Testing
- 1: The Perfect Generation Prompt
- 2: Background Removal (Dart/Flutter)
- 3: Sizing & Integration for LG
- 1: Present the Plan Header
- 2: Build the Checklist Together — ONE Task at a Time
- 1**: Why we're touching these files
- 2**: Code pattern / interface
- 3**: Verification (`flutter analyze`, `flutter test`, visual check)
- 4**: `git commit -m "feat: [description]"`
- 3: Review the Complete Plan Together
- 1: Read Checkpoint
- 2: Display Progress Summary
- 3: Pre-Resume Validation

## Checks this factory defines

- Report
- - Install on device: `adb install build/app/outputs/flutter-apk/app-release.apk`
- it launches without crash
- - Open in browser, verify no console errors
- responsive layout
- - Run: `./build/linux/x64/release/bundle/<app_name>`
- - Test on simulator: `flutter run -d "iPhone 15 Pro"`
- network permissions in `Info.plist`
- Checklist

## Prompt assets this factory ships

- .agent/skills/lg-code-converter/SKILL.md
- .agent/skills/lg-code-reviewer/SKILL.md
- .agent/skills/lg-devops-agent/SKILL.md
- .agent/skills/lg-emulator-manager/SKILL.md
- .agent/skills/lg-flutter-build/SKILL.md
- .agent/skills/lg-kml-craftsman/SKILL.md
- .agent/skills/lg-kml-writer/SKILL.md
- .agent/skills/lg-nanobanana-sprite/SKILL.md
- .agent/skills/lg-plan-writer/SKILL.md
- .agent/skills/lg-quiz-master/SKILL.md
- .agent/skills/lg-resume-pipeline/SKILL.md
- .agent/skills/lg-shield/SKILL.md
- .agent/skills/lg-ssh-controller/SKILL.md
- .agent/skills/lg-ui-scaffolder/SKILL.md

## What its own definition files describe

- LG Flutter App Configuration Template
- Used by the generate-flutter-app workflow to bootstrap a new LG controller app.
- Students should copy this file and fill in their values.
- # NAMING CONVENTION: App name MUST follow LG-<TaskName> pattern.
- Good: LG-Task2-Demo, LG-Earthquake-Viz, LG-Satellite-Tracker
- Bad:  my_app, flutter_test, earthquake_app
- Student's home city for flyTo demo
- External APIs (empty for Task 2; add for advanced apps)
- Task 2 feature flags
- Asset recommendations (generated via lg-nanobanana-sprite)
- Reference implementation
- Dart Style Rules for LG Flutter Apps
- Formatting
- Naming Conventions
- Imports
- Documentation
- Null Safety
- State Management (Provider)
- Error Handling
- Constants
- Flutter Best Practices for LG Apps
- Project Structure
- Platform Awareness
- State Management

## Deliverable

Return, in order: (1) the stages you would run and why, (2) for each worker, the artefact it
produces, (3) the checks that would fail the work, (4) anything in the brief you cannot
determine from it. Do not claim results you did not compute.
