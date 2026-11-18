# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44345642494  Commit: f677056ef34c1ba68f416d284cca9eca59e7f236

## compiler errors
```
43:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:104:35 No value passed for parameter 'onClick'.
44:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:104:48 Argument type mismatch: actual type is 'kotlin.Function0<kotlin.Unit>', but 'androidx.compose.ui.Modifier' was expected.
45:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:215:35 No value passed for parameter 'onClick'.
46:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:215:80 Argument type mismatch: actual type is 'kotlin.Function0<kotlin.Unit>', but 'androidx.compose.ui.Modifier' was expected.
47:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:52:18 Type 'androidx.compose.runtime.State<dev.nova.tv.ui.screens.NovaState>' has no method 'getValue(Nothing?, KProperty0<*>)', so it cannot serve as a delegate.
48:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:100:28 Cannot infer type for this parameter. Please specify it explicitly.
49:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:100:34 Cannot infer type for this parameter. Please specify it explicitly.
50:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:101:17 @Composable invocations can only happen from the context of a @Composable function
51:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:103:29 Argument type mismatch: actual type is 'C', but 'kotlin.String' was expected.
52:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:103:48 Cannot infer type for this parameter. Please specify it explicitly.
53:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:103:65 Unresolved reference 'name'.
54:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:104:42 Inapplicable candidate(s): fun stopPlayback(): Unit
55:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:118:21 Type 'androidx.compose.runtime.MutableState<kotlin.Boolean>' has no method 'setValue(Nothing?, KMutableProperty0<*>, ERROR CLASS: Ambiguity: getValue, [kotlin/getValue, kotlin/getValue, kotlin/getValue, kotlin/collections/getValue, kotlin/collections/getValue, kotlin/collections/getValue])', so it cannot serve as a delegate for var (read-write property).
56:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaApp.kt:118:21 Property delegate must have a 'getValue(Nothing?, KMutableProperty0<*>)' method. None of the following functions is applicable:
63:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaPanes.kt:115:27 No value passed for parameter 'onClick'.
64:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/screens/NovaPanes.kt:115:40 Argument type mismatch: actual type is 'kotlin.Function0<kotlin.Unit>', but 'androidx.compose.ui.Modifier' was expected.
69:FAILURE: Build failed with an exception.
71:* What went wrong:
114:Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
243:Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException: Compilation error. See log for more details
```

## last 60 lines
```
	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:209)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:204)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:166)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
	at org.gradle.execution.plan.LocalTaskNodeExecutor.execute(LocalTaskNodeExecutor.java:42)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:331)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:318)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:314)
	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:85)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:314)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:303)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
Caused by: org.jetbrains.kotlin.gradle.tasks.CompilationErrorException: Compilation error. See log for more details
	at org.jetbrains.kotlin.gradle.tasks.TasksUtilsKt.throwExceptionIfCompilationFailed(tasksUtils.kt:21)
	at org.jetbrains.kotlin.compilerRunner.GradleKotlinCompilerWork.run(GradleKotlinCompilerWork.kt:119)
	at org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction.execute(GradleCompilerRunnerWithWorkers.kt:76)
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


BUILD FAILED in 2m 42s
21 actionable tasks: 21 executed
```
