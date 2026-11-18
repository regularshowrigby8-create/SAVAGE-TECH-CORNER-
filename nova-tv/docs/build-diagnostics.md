# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44330777035  Commit: 75d6edc738a664e67986bbe46836283af7652329

## compiler errors
```
43:FAILURE: Build failed with an exception.
45:* What went wrong:
49:     ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource drawable/app_banner (aka dev.nova.tv:drawable/app_banner) not found.
51:     ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource mipmap/ic_launcher (aka dev.nova.tv:mipmap/ic_launcher) not found.
121:Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction
279:Caused by: com.android.builder.internal.aapt.v2.Aapt2Exception: Android resource linking failed
280:ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource drawable/app_banner (aka dev.nova.tv:drawable/app_banner) not found.
282:ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource mipmap/ic_launcher (aka dev.nova.tv:mipmap/ic_launcher) not found.
```

## last 60 lines
```
	at org.gradle.internal.model.StateTransitionController.doTransition(StateTransitionController.java:266)
	at org.gradle.internal.model.StateTransitionController.lambda$tryTransition$8(StateTransitionController.java:177)
	at org.gradle.internal.work.DefaultSynchronizer.withLock(DefaultSynchronizer.java:44)
	at org.gradle.internal.model.StateTransitionController.tryTransition(StateTransitionController.java:177)
	at org.gradle.internal.build.DefaultBuildLifecycleController.executeTasks(DefaultBuildLifecycleController.java:304)
	at org.gradle.internal.build.DefaultBuildWorkGraphController$DefaultBuildWorkGraph.runWork(DefaultBuildWorkGraphController.java:220)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:267)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:131)
	at org.gradle.composite.internal.DefaultBuildController.doRun(DefaultBuildController.java:181)
	at org.gradle.composite.internal.DefaultBuildController.access$000(DefaultBuildController.java:50)
	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.lambda$run$0(DefaultBuildController.java:198)
	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
	at org.gradle.composite.internal.DefaultBuildController$BuildOpRunnable.run(DefaultBuildController.java:198)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
Caused by: com.android.builder.internal.aapt.v2.Aapt2Exception: Android resource linking failed
ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource drawable/app_banner (aka dev.nova.tv:drawable/app_banner) not found.
    
ERROR: /home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/AndroidManifest.xml:16:5-40:19: AAPT: error: resource mipmap/ic_launcher (aka dev.nova.tv:mipmap/ic_launcher) not found.
    
	at com.android.builder.internal.aapt.v2.Aapt2Exception$Companion.create(Aapt2Exception.kt:45)
	at com.android.builder.internal.aapt.v2.Aapt2Exception$Companion.create$default(Aapt2Exception.kt:33)
	at com.android.build.gradle.internal.res.Aapt2ErrorUtils.rewriteException(Aapt2ErrorUtils.kt:262)
	at com.android.build.gradle.internal.res.Aapt2ErrorUtils.rewriteLinkException(Aapt2ErrorUtils.kt:133)
	at com.android.build.gradle.internal.res.Aapt2ProcessResourcesRunnableKt.processResources(Aapt2ProcessResourcesRunnable.kt:76)
	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:945)
	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.access$invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:799)
	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction.run(LinkApplicationAndroidResourcesTask.kt:432)
	at com.android.build.gradle.internal.profile.ProfileAwareWorkAction.execute(ProfileAwareWorkAction.kt:74)
	at org.gradle.workers.internal.DefaultWorkerServer.execute(DefaultWorkerServer.java:63)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:66)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:62)
	at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:100)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:62)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:209)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:204)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:166)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:59)
	at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:174)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:195)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:128)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:170)
	at org.gradle.internal.Factories$1.create(Factories.java:31)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:267)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:131)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:136)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:165)
	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:134)
	... 2 more


BUILD FAILED in 1m 16s
19 actionable tasks: 19 executed
```
