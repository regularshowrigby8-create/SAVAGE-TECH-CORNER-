# Nova patch build diagnostics

Written automatically by the "Nova patch build" workflow.

- run: 44783019659
- sources: `arena/nova-final-debug` @ `3347198`
- patched compile: FAILED

## compiler errors
```
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:151:46 No parameter with name 'lastError' found.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:181:24 Type checking has run into a recursive problem. Easiest workaround: specify the types of your declarations explicitly.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:181:31 Unresolved reference 'value'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:9 No parameter with name 'lastError' found.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:21 Type checking has run into a recursive problem. Easiest workaround: specify the types of your declarations explicitly.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:28 Unresolved reference 'value'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:151:46 No parameter with name 'lastError' found.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:181:24 Type checking has run into a recursive problem. Easiest workaround: specify the types of your declarations explicitly.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:181:31 Unresolved reference 'value'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:9 No parameter with name 'lastError' found.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:21 Type checking has run into a recursive problem. Easiest workaround: specify the types of your declarations explicitly.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt:182:28 Unresolved reference 'value'.
```

## applied patch
```
  ~ README.md  (133 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/NovaApplication.kt  (64 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/data/store/AtomicFileStore.kt  (new file, 1637 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt  (116 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerHarvester.kt  (763 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerPool.kt  (54 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt  (new file, 7750 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaSources.kt  (91 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaViewModel.kt  (86 changed lines)
  ~ nova-tv/android/app/src/mobile/java/dev/nova/tv/mobile/MobileSources.kt  (40 changed lines)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolFillTest.kt  (new file, 7832 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt  (new file, 8007 B)
  + nova-tv/tools/server_crawler.py  (new file, 34210 B)
  + nova-tv/tools/site_crawler.py  (new file, 26708 B)
  + nova-tv/tools/test_server_crawler.py  (new file, 10115 B)
```

## baseline (unpatched) compile
```
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.
==============================================================================

2: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':app:compileMobileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.
==============================================================================

BUILD FAILED in 29s
34 actionable tasks: 2 executed, 32 up-to-date
```
