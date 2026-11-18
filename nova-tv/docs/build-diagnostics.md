# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44330301127  Commit: 396656da1dc34ded660e004a096c0a2a6d652615

## compiler errors
```
25:FAILURE: Build failed with an exception.
27:* What went wrong:
621:Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing com.android.build.gradle.internal.tasks.CheckAarMetadataWorkAction
778:Caused by: java.lang.RuntimeException: 40 issues were found when checking AAR metadata:
```

## last 60 lines
```

 38.  Dependency 'androidx.lifecycle:lifecycle-viewmodel-compose-android:2.9.4' requires Android Gradle plugin 8.6.0 or higher.

      This build currently uses Android Gradle plugin 8.5.2.

 39.  Dependency 'androidx.compose.ui:ui-android:1.10.3' requires libraries and applications that
      depend on it to compile against version 35 or later of the
      Android APIs.

      :app is currently compiled against android-34.

      Also, the maximum recommended compile SDK version for Android Gradle
      plugin 8.5.2 is 34.

      Recommended action: Update this project's version of the Android Gradle
      plugin to one that supports 35, then update this project to use
      compileSdk of at least 35.

      Note that updating a library or application's compileSdk (which
      allows newer APIs to be used) can be done separately from updating
      targetSdk (which opts the app in to new runtime behavior) and
      minSdk (which determines which devices the app can be installed
      on).

 40.  Dependency 'androidx.compose.ui:ui-android:1.10.3' requires Android Gradle plugin 8.6.0 or higher.

      This build currently uses Android Gradle plugin 8.5.2.
	at com.android.build.gradle.internal.tasks.CheckAarMetadataWorkAction.execute(CheckAarMetadataTask.kt:281)
	at org.gradle.workers.internal.DefaultWorkerServer.execute(DefaultWorkerServer.java:63)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:66)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:62)
	at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:100)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:62)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:200)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:195)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:157)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.internal.operations.DefaultBuildOperationExecutor.call(DefaultBuildOperationExecutor.java:73)
	at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:59)
	at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:174)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:187)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:120)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:162)
	at org.gradle.internal.Factories$1.create(Factories.java:31)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:264)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:128)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:133)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:157)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:126)
	... 2 more


BUILD FAILED in 1m 15s
3 actionable tasks: 3 executed
```
