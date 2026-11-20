# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44735246175  Commit: fd7c29055c30f8d97974cc7e993dd01eacbfdda9
Log: lint.log

## errors
```
73:> Task :app:lintTvDebug FAILED
112:FAILURE: Build failed with an exception.
114:* What went wrong:
115:Execution failed for task ':app:lintTvDebug'.
```

## last 80 lines
```
   lint.xml
   as a comma-delimited list of opted-in annotations:

   <lint>
       <issue id="UnsafeOptInUsageError">
           <option name="opt-in" value="com.foo.ExperimentalBarAnnotation" />
       </issue>
   </lint>

   Vendor: Android Open Source Project
   Identifier: androidx.annotation.experimental
   Feedback: https://issuetracker.google.com/issues/new?component=459778


The full lint text report is located at:
  /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/build/intermediates/lint_intermediate_text_report/tvDebug/lintReportTvDebug/lint-results-tvDebug.txt
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1795177861740.json

FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':app:lintTvDebug'.
> Lint found errors in the project; aborting build.
  
  Fix the issues identified by lint, or create a baseline to see only new errors.
  To create a baseline, run `gradlew updateLintBaseline` after adding the following to the module's build.gradle file:
  ```
  android {
      lint {
          baseline = file("lint-baseline.xml")
      }
  }
  ```
  For more details, see https://developer.android.com/studio/write/lint#snapshot
  
  Lint found 1 error, 73 warnings. First failure:
  
  /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/player/imported/SubtitleOffset.kt:53: Error: This declaration is opt-in and its usage should be marked with @androidx.media3.common.util.UnstableApi or @OptIn(markerClass = androidx.media3.common.util.UnstableApi.class) [UnsafeOptInUsageError from androidx.annotation.experimental]
          val now = cueGroup.presentationTimeUs.takeIf { it != C.TIME_UNSET }?.div(1_000) ?: player.currentPosition
                             ~~~~~~~~~~~~~~~~~~
  
     Explanation for issues of type "UnsafeOptInUsageError":
     This API has been flagged as opt-in with error-level severity.
  
     Any declaration annotated with this marker is considered part of an
     unstable or
     otherwise non-standard API surface and its call sites should accept the
     opt-in
     aspect of it by using the @OptIn annotation, using the marker annotation
     --
     effectively causing further propagation of the opt-in aspect -- or
     configuring
     the UnsafeOptInUsageError check's options for project-wide opt-in.
  
     To configure project-wide opt-in, specify the opt-in option value in
     lint.xml
     as a comma-delimited list of opted-in annotations:
  
     <lint>
         <issue id="UnsafeOptInUsageError">
             <option name="opt-in" value="com.foo.ExperimentalBarAnnotation" />
         </issue>
     </lint>
  
     Vendor: Android Open Source Project
     Identifier: androidx.annotation.experimental
     Feedback: https://issuetracker.google.com/issues/new?component=459778
  
  
  The full lint text report is located at:
    /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/build/intermediates/lint_intermediate_text_report/tvDebug/lintReportTvDebug/lint-results-tvDebug.txt

* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 2m 2s
55 actionable tasks: 13 executed, 2 from cache, 40 up-to-date
```
