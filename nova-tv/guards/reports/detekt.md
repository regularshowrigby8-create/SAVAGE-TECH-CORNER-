# detekt

## Metrics

* 1,235 number of properties

* 561 number of functions

* 207 number of classes

* 19 number of packages

* 64 number of kt files

## Complexity Report

* 12,545 lines of code (loc)

* 10,199 source lines of code (sloc)

* 7,559 logical lines of code (lloc)

* 1,308 comment lines of code (cloc)

* 2,221 cyclomatic complexity (mcc)

* 2,191 cognitive complexity

* 5 number of total code smells

* 12% comment source ratio

* 293 mcc per 1,000 lloc

* 0 code smells per 1,000 lloc

## Findings (5)

### style, UnusedImports (1)

Unused Imports are dead code and should be removed.

[Documentation](https://detekt.dev/docs/rules/style#unusedimports)

* /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/mobile/java/dev/nova/tv/mobile/MobilePlayer.kt:78:1
```
The import 'androidx.compose.ui.graphics.vector.path' is unused.
```
```kotlin
75 import androidx.compose.ui.graphics.graphicsLayer
76 import androidx.compose.ui.graphics.vector.ImageVector
77 import androidx.compose.ui.graphics.vector.addPathNodes
78 import androidx.compose.ui.graphics.vector.path
!! ^ error
79 import androidx.compose.ui.graphics.SolidColor
80 import androidx.compose.ui.input.pointer.pointerInput
81 import androidx.compose.ui.layout.ContentScale

```

### style, UnusedParameter (3)

Function parameter is unused and should be removed.

[Documentation](https://detekt.dev/docs/rules/style#unusedparameter)

* /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/engine/PlayerEngineFactory.kt:15:9
```
Function parameter `context` is unused.
```
```kotlin
12 object PlayerEngineFactory {
13     fun createEngine(
14         type: PlayerEngineType,
15         context: Context,
!!         ^ error
16         exoPlayer: ExoPlayer,
17         scope: CoroutineScope
18     ): PlayerEngine {

```

* /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:260:5
```
Function parameter `buffering` is unused.
```
```kotlin
257     position: Long,
258     duration: Long,
259     playing: Boolean,
260     buffering: Boolean,
!!!     ^ error
261     sourceLabel: String?,
262     sourceIndex: Int,
263     sourceCount: Int,

```

* /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:262:5
```
Function parameter `sourceIndex` is unused.
```
```kotlin
259     playing: Boolean,
260     buffering: Boolean,
261     sourceLabel: String?,
262     sourceIndex: Int,
!!!     ^ error
263     sourceCount: Int,
264     playFocus: FocusRequester,
265     viewModel: NovaViewModel

```

### style, UnusedPrivateProperty (1)

Property is unused and should be removed.

[Documentation](https://detekt.dev/docs/rules/style#unusedprivateproperty)

* /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:67:13
```
Private property `SheetShape` is unused.
```
```kotlin
64  */
65 
66 private val CardShape = RoundedCornerShape(NovaShape.card)
67 private val SheetShape = RoundedCornerShape(NovaShape.sheet)
!!             ^ error
68 private val PillShape = RoundedCornerShape(percent = NovaShape.pillPercent)
69 
70 // ---------------------------------------------------------------------------

```

generated with [detekt version 1.23.8](https://detekt.dev/) on 2026-11-19 10:06:04 UTC
