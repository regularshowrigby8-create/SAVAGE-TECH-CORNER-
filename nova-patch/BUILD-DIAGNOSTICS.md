# Nova patch build diagnostics

Written automatically by the "Nova patch build" workflow.

- run: 44804248288
- sources: `arena/nova-final-debug` @ `3347198`
- patched compile status: 0
- unit tests: failure
- APK assembly: skipped

## compiler errors
```
no compiler errors captured
```

## test errors
```
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:47:21 Unresolved reference 'addJsonObject'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:66:13 Unresolved reference 'putJsonObject'.
> Task :app:compileMobileDebugUnitTestKotlin FAILED
> Task :app:compileTvDebugUnitTestKotlin FAILED
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:47:21 Unresolved reference 'addJsonObject'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:66:13 Unresolved reference 'putJsonObject'.
FAILURE: Build completed with 2 failures.
* What went wrong:
* What went wrong:
BUILD FAILED in 34s
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:47:21 Unresolved reference 'addJsonObject'.
e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/work/nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt:66:13 Unresolved reference 'putJsonObject'.

FAILURE: Build completed with 2 failures.

1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':app:compileMobileDebugUnitTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.
==============================================================================

2: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':app:compileTvDebugUnitTestKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.
==============================================================================

BUILD FAILED in 34s
46 actionable tasks: 11 executed, 1 from cache, 34 up-to-date
```

## APK packaging errors
```
no packaging errors captured
```

## applied patch
```
  + BUILD-DIAGNOSTICS.md  (new file, 4986 B)
  + POOL-COUNTS.md  (new file, 2331 B)
  ~ README.md  (133 changed lines)
  + nova-tv/android/app/src/main/assets/site_pool.json  (new file, 224 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/NovaApplication.kt  (72 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/data/store/AtomicFileStore.kt  (new file, 1637 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt  (116 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerHarvester.kt  (763 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerPool.kt  (60 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt  (new file, 8705 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaSources.kt  (91 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaViewModel.kt  (86 changed lines)
  ~ nova-tv/android/app/src/mobile/java/dev/nova/tv/mobile/MobileSources.kt  (40 changed lines)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolFillTest.kt  (new file, 7876 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt  (new file, 9976 B)
  + nova-tv/tools/server_crawler.py  (new file, 34548 B)
  + nova-tv/tools/site_crawler.py  (new file, 27561 B)
  + nova-tv/tools/test_server_crawler.py  (new file, 10115 B)
  + pools/counts.json  (new file, 296 B)
```

## baseline (unpatched) compile
```
not run -- the patched build did not reach the comparison step
```
