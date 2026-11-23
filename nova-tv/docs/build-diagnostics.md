# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 45265043893  Commit: 21bd31a29bbd7663a66b82db736bbf41caa38c94
Log: build.log

## errors
```
```

## last 80 lines
```

> Task :app:compileMobileDebugKotlin
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/NovaRenderersFactory.kt:80:14 'fun setEnableAudioTrackPlaybackParams(p0: Boolean): DefaultAudioSink.Builder' is deprecated. Deprecated in Java.
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/SurroundAudioRecovery.kt:59:24 'static fun isDirectPlaybackSupported(p0: AudioFormat, p1: AudioAttributes): Boolean' is deprecated. Deprecated in Java.

> Task :app:compileMobileDebugJavaWithJavac
> Task :app:mergeLibDexMobileDebug
> Task :app:dexBuilderMobileDebug
> Task :app:mergeMobileDebugGlobalSynthetics
> Task :app:processMobileDebugJavaRes
> Task :app:mergeMobileDebugJavaResource
> Task :app:mergeMobileDebugJniLibFolders
> Task :app:mergeProjectDexMobileDebug
> Task :app:mergeMobileDebugNativeLibs
> Task :app:validateSigningMobileDebug
> Task :app:writeMobileDebugAppMetadata
> Task :app:writeMobileDebugSigningConfigVersions
> Task :app:preTvDebugBuild UP-TO-DATE
> Task :app:mergeTvDebugNativeDebugMetadata NO-SOURCE
> Task :app:generateTvDebugBuildConfig

> Task :app:stripMobileDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libffmpegJNI.so. Run with --info option to learn more.

> Task :app:checkTvDebugAarMetadata
> Task :app:processTvDebugNavigationResources
> Task :app:compileTvDebugNavigationResources FROM-CACHE
> Task :app:generateTvDebugResValues FROM-CACHE
> Task :app:mapTvDebugSourceSetPaths
> Task :app:generateTvDebugResources
> Task :app:packageMobileDebug
> Task :app:createMobileDebugApkListingFileRedirect
> Task :app:assembleMobileDebug
> Task :app:packageTvDebugResources
> Task :app:parseTvDebugLocalResources
> Task :app:createTvDebugCompatibleScreenManifests
> Task :app:extractDeepLinksTvDebug
> Task :app:processTvDebugMainManifest
> Task :app:processTvDebugManifest
> Task :app:processTvDebugManifestForPackage
> Task :app:javaPreCompileTvDebug FROM-CACHE
> Task :app:mergeTvDebugShaders
> Task :app:compileTvDebugShaders NO-SOURCE
> Task :app:generateTvDebugAssets UP-TO-DATE
> Task :app:mergeTvDebugAssets
> Task :app:compressTvDebugAssets FROM-CACHE
> Task :app:checkTvDebugDuplicateClasses
> Task :app:desugarTvDebugFileDependencies FROM-CACHE
> Task :app:mergeTvDebugResources
> Task :app:mergeExtDexTvDebug FROM-CACHE
> Task :app:mergeLibDexTvDebug FROM-CACHE
> Task :app:mergeTvDebugJniLibFolders
> Task :app:mergeTvDebugNativeLibs

> Task :app:stripTvDebugDebugSymbols
Unable to strip the following libraries, packaging them as they are: libandroidx.graphics.path.so, libffmpegJNI.so. Run with --info option to learn more.

> Task :app:validateSigningTvDebug
> Task :app:writeTvDebugAppMetadata
> Task :app:writeTvDebugSigningConfigVersions
> Task :app:processTvDebugResources

> Task :app:compileTvDebugKotlin
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/NovaRenderersFactory.kt:80:14 'fun setEnableAudioTrackPlaybackParams(p0: Boolean): DefaultAudioSink.Builder' is deprecated. Deprecated in Java.
w: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/exoplayer/SurroundAudioRecovery.kt:59:24 'static fun isDirectPlaybackSupported(p0: AudioFormat, p1: AudioAttributes): Boolean' is deprecated. Deprecated in Java.

> Task :app:compileTvDebugJavaWithJavac
> Task :app:dexBuilderTvDebug
> Task :app:mergeTvDebugGlobalSynthetics FROM-CACHE
> Task :app:processTvDebugJavaRes
> Task :app:mergeProjectDexTvDebug
> Task :app:mergeTvDebugJavaResource
> Task :app:packageTvDebug
> Task :app:createTvDebugApkListingFileRedirect
> Task :app:assembleTvDebug
> Task :app:assembleDebug
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_3-1795467604530.json

BUILD SUCCESSFUL in 4m 37s
78 actionable tasks: 70 executed, 8 from cache
```
