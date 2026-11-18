# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44344506757  Commit: df8821770ff0e16761c161a86f911974c6642ab1

## compiler errors
```
43:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:78:20 Type 'androidx.compose.runtime.MutableState<kotlin.Boolean>' has no method 'setValue(Nothing?, KMutableProperty0<*>, ERROR CLASS: Ambiguity: getValue, [kotlin/getValue, kotlin/getValue, kotlin/getValue, kotlin/collections/getValue, kotlin/collections/getValue, kotlin/collections/getValue])', so it cannot serve as a delegate for var (read-write property).
44:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:78:20 Property delegate must have a 'getValue(Nothing?, KMutableProperty0<*>)' method. None of the following functions is applicable:
51:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:79:18 Type 'androidx.compose.runtime.State<kotlin.Float>' has no method 'getValue(Nothing?, KProperty0<*>)', so it cannot serve as a delegate.
52:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:91:17 Argument type mismatch: actual type is 'androidx.compose.foundation.shape.RoundedCornerShape', but 'androidx.tv.material3.CardShape' was expected.
53:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:100:13 No parameter with name 'selectedBorder' found.
54:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:107:13 No parameter with name 'selectedGlow' found.
55:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:110:9 @Composable invocations can only happen from the context of a @Composable function
56:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:193:17 Argument type mismatch: actual type is 'androidx.compose.foundation.shape.RoundedCornerShape', but 'androidx.tv.material3.ClickableSurfaceShape' was expected.
57:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:201:9 @Composable invocations can only happen from the context of a @Composable function
58:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:220:17 Argument type mismatch: actual type is 'androidx.compose.foundation.shape.RoundedCornerShape', but 'androidx.tv.material3.ClickableSurfaceShape' was expected.
59:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:228:9 @Composable invocations can only happen from the context of a @Composable function
60:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:248:17 Argument type mismatch: actual type is 'androidx.compose.foundation.shape.RoundedCornerShape', but 'androidx.tv.material3.ClickableSurfaceShape' was expected.
61:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/components/NovaComponents.kt:256:9 @Composable invocations can only happen from the context of a @Composable function
62:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:59:13 Unresolved reference 'NovaPlayer'.
63:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:64:20 Cannot infer type for this parameter. Please specify it explicitly.
64:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:64:20 Property delegate must have a 'getValue(Nothing?, KProperty0<ERROR CLASS: Cannot infer argument for type parameter T>)' method. None of the following functions is applicable:
66:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:64:27 Unresolved reference 'failure'.
67:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:64:35 Cannot infer type for this parameter. Please specify it explicitly.
68:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:65:26 Type 'androidx.compose.runtime.MutableState<kotlin.Boolean>' has no method 'setValue(Nothing?, KMutableProperty0<*>, Boolean)', so it cannot serve as a delegate for var (read-write property).
69:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:66:23 Type 'androidx.compose.runtime.MutableState<kotlin.Boolean>' has no method 'setValue(Nothing?, KMutableProperty0<*>, Boolean)', so it cannot serve as a delegate for var (read-write property).
70:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:77:14 Cannot infer type for this parameter. Please specify it explicitly.
71:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:77:14 Not enough information to infer type argument for 'T'.
72:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:77:14 Not enough information to infer type argument for 'R'.
73:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:77:20 Cannot infer type for this parameter. Please specify it explicitly.
74:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:85:26 Unresolved reference 'message'.
75:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:88:36 Unresolved reference 'retryable'.
76:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:89:40 Unresolved reference 'reason'.
77:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:91:40 Unresolved reference 'reason'.
78:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:100:29 Unresolved reference 'retryable'.
79:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:101:35 No value passed for parameter 'onClick'.
80:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:101:48 Argument type mismatch: actual type is 'kotlin.Function0<ERROR CLASS: Unknown return lambda parameter type>', but 'androidx.compose.ui.Modifier' was expected.
81:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:101:48 Unresolved reference 'play'.
82:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:101:57 Unresolved reference 'play'.
83:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:113:19 Unresolved reference 'PlayerTimings'.
84:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:125:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
85:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:125:58 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
86:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:126:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
87:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:127:32 Unresolved reference 'togglePlay'.
88:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:131:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
89:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:132:32 Unresolved reference 'seekBy'.
90:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:132:40 Unresolved reference 'SkipSeconds'.
91:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:136:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
92:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:137:32 Unresolved reference 'seekBy'.
93:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:137:39 Unresolved reference 'SkipSeconds'.
94:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:141:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
95:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:142:32 Unresolved reference 'seekBy'.
96:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:142:40 Unresolved reference 'SkipSeconds'.
97:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:146:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
98:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:147:32 Unresolved reference 'seekBy'.
99:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:147:39 Unresolved reference 'SkipSeconds'.
100:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:151:21 Incompatible types 'androidx.compose.ui.input.key.Key' and 'kotlin.Int'.
101:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:171:38 Unresolved reference 'player'.
102:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:196:43 Unresolved reference 'isPlaying'.
103:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:201:53 Unresolved reference 'SkipSeconds'.
104:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:208:35 No value passed for parameter 'onClick'.
105:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:208:46 Unresolved reference 'isPlaying'.
106:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:208:80 Argument type mismatch: actual type is 'kotlin.Function0<kotlin.Unit>', but 'androidx.compose.ui.Modifier' was expected.
107:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:209:32 Unresolved reference 'togglePlay'.
108:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:214:33 Argument type mismatch: actual type is 'androidx.compose.foundation.shape.RoundedCornerShape', but 'androidx.tv.material3.ClickableSurfaceShape' was expected.
109:e: file:///home/runner/work/SAVAGE-TECH-CORNER-/SAVAGE-TECH-CORNER-/nova-tv/android/app/src/main/java/dev/nova/tv/ui/player/PlayerSurface.kt:220:25 @Composable invocations can only happen from the context of a @Composable function
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


BUILD FAILED in 2m 11s
21 actionable tasks: 21 executed
```
