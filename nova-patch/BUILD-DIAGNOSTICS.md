# Nova patch build diagnostics

Written automatically by the "Nova patch build" workflow.

- run: 44886402927
- sources: `arena/nova-final-debug` @ `3347198`
- patched compile status: 0
- unit tests: success
- APK assembly: success
- APK pool verification: failure

## compiler errors
```
no compiler errors captured
```

## test errors
```
e: The daemon has terminated unexpectedly on startup attempt #1 with error code: 0. The daemon process output:
e: The daemon has terminated unexpectedly on startup attempt #2 with error code: 0. The daemon process output:
> Task :app:parseMobileDebugLocalResources UP-TO-DATE
> Task :app:createMobileDebugCompatibleScreenManifests UP-TO-DATE
> Task :app:extractDeepLinksMobileDebug UP-TO-DATE
> Task :app:processMobileDebugMainManifest UP-TO-DATE
> Task :app:processMobileDebugManifest UP-TO-DATE
> Task :app:processMobileDebugManifestForPackage UP-TO-DATE
> Task :app:processMobileDebugResources UP-TO-DATE
> Task :app:compileMobileDebugKotlin UP-TO-DATE
> Task :app:javaPreCompileMobileDebug FROM-CACHE
> Task :app:compileMobileDebugJavaWithJavac
> Task :app:bundleMobileDebugClassesToRuntimeJar
> Task :app:bundleMobileDebugClassesToCompileJar

> Task :app:compileTvDebugUnitTestKotlin
e: The daemon has terminated unexpectedly on startup attempt #1 with error code: 0. The daemon process output:
    1. Kotlin compile daemon is ready
e: The daemon has terminated unexpectedly on startup attempt #2 with error code: 0. The daemon process output:
    1. Kotlin compile daemon is ready
Problems may have occurred during auto-selection of GC. The preferred GC is Parallel GC.
If the problems persist, try adding the JVM option to the Kotlin daemon JVM arguments: -XX:-UseParallelGC.
GC auto-selection logic is disabled temporary for the next daemon startup.

> Task :app:compileMobileDebugUnitTestKotlin
> Task :app:compileTvDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processTvDebugUnitTestJavaRes
> Task :app:testTvDebugUnitTest
> Task :app:preMobileDebugUnitTestBuild UP-TO-DATE
> Task :app:javaPreCompileMobileDebugUnitTest FROM-CACHE
> Task :app:compileMobileDebugUnitTestJavaWithJavac NO-SOURCE
> Task :app:processMobileDebugJavaRes
> Task :app:processMobileDebugUnitTestJavaRes
> Task :app:testMobileDebugUnitTest

BUILD SUCCESSFUL in 34s
52 actionable tasks: 16 executed, 2 from cache, 34 up-to-date
```

## APK packaging errors
```
no packaging errors captured
> Task :app:mergeExtDexMobileDebug FROM-CACHE
> Task :app:mergeLibDexMobileDebug FROM-CACHE
> Task :app:dexBuilderMobileDebug
> Task :app:mergeMobileDebugGlobalSynthetics FROM-CACHE
> Task :app:processMobileDebugJavaRes UP-TO-DATE
> Task :app:packageTvDebug
> Task :app:createTvDebugApkListingFileRedirect
> Task :app:assembleTvDebug
> Task :app:mergeMobileDebugJavaResource
> Task :app:mergeMobileDebugJniLibFolders
> Task :app:mergeMobileDebugNativeLibs

> Task :app:stripMobileDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libffmpegJNI.so. Run with --info option to learn more.

> Task :app:validateSigningMobileDebug
> Task :app:writeMobileDebugAppMetadata
> Task :app:writeMobileDebugSigningConfigVersions
> Task :app:mergeProjectDexMobileDebug
> Task :app:packageMobileDebug
> Task :app:createMobileDebugApkListingFileRedirect
> Task :app:assembleMobileDebug

BUILD SUCCESSFUL in 49s
78 actionable tasks: 33 executed, 5 from cache, 40 up-to-date
```

## APK pool verification
```
FAIL: site_pool.json: pool is empty
```

## applied patch
```
  + nova-tv/android/app/src/main/assets/server_pool.json  (new file, 185418 B)
  + nova-tv/android/app/src/main/assets/site_pool.json  (new file, 102225 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/NovaApplication.kt  (87 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/data/repo/LibraryStore.kt  (16 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/data/store/AtomicFileStore.kt  (new file, 1551 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/SourceEngine.kt  (136 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerHarvester.kt  (768 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/finder/pool/ServerPool.kt  (99 changed lines)
  + nova-tv/android/app/src/main/java/dev/nova/tv/finder/website/SitePool.kt  (new file, 10157 B)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaSources.kt  (94 changed lines)
  ~ nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaViewModel.kt  (90 changed lines)
  ~ nova-tv/android/app/src/mobile/java/dev/nova/tv/mobile/MobileSources.kt  (46 changed lines)
  + nova-tv/android/app/src/test/java/dev/nova/tv/data/store/AtomicFileStoreTest.kt  (new file, 1111 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolBundledTest.kt  (new file, 2615 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/pool/ServerPoolFillTest.kt  (new file, 8312 B)
  + nova-tv/android/app/src/test/java/dev/nova/tv/finder/website/SitePoolTest.kt  (new file, 11636 B)
  + nova-tv/tools/server_crawler.py  (new file, 40991 B)
  + nova-tv/tools/site_crawler.py  (new file, 45087 B)
  + nova-tv/tools/test_server_crawler.py  (new file, 14758 B)
  + nova-tv/tools/test_site_crawler.py  (new file, 12771 B)
```

## baseline (unpatched) compile
```
not run -- the patched build did not reach the comparison step
```
