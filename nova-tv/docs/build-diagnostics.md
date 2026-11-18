# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44332525107  Commit: dad65a4a803018c0ca10fb989956a7ad90794815

## compiler errors
```
23:FAILURE: Build failed with an exception.
25:* What went wrong:
94:Caused by: org.gradle.api.internal.artifacts.ivyservice.TypedResolveException: Could not resolve all files for configuration ':app:debugRuntimeClasspath'.
210:Caused by: org.gradle.internal.resolve.ModuleVersionNotFoundException: Could not find androidx.tv:tv-foundation:1.1.0.
```

## last 60 lines
```
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
	at org.gradle.execution.plan.DefaultPlanExecutor.process(DefaultPlanExecutor.java:111)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.executeWithServices(DefaultTaskExecutionGraph.java:138)
	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph.execute(DefaultTaskExecutionGraph.java:123)
	at org.gradle.execution.SelectedTaskExecutionAction.execute(SelectedTaskExecutionAction.java:35)
	at org.gradle.execution.DryRunBuildExecutionAction.execute(DryRunBuildExecutionAction.java:51)
	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:54)
	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor$ExecuteTasks.call(BuildOperationFiringBuildWorkerExecutor.java:43)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:209)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:204)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:166)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.execution.BuildOperationFiringBuildWorkerExecutor.execute(BuildOperationFiringBuildWorkerExecutor.java:40)
	at org.gradle.internal.build.DefaultBuildLifecycleController.lambda$executeTasks$10(DefaultBuildLifecycleController.java:313)
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
Caused by: org.gradle.internal.resolve.ModuleVersionNotFoundException: Could not find androidx.tv:tv-foundation:1.1.0.
Searched in the following locations:
  - https://dl.google.com/dl/android/maven2/androidx/tv/tv-foundation/1.1.0/tv-foundation-1.1.0.pom
  - https://repo.maven.apache.org/maven2/androidx/tv/tv-foundation/1.1.0/tv-foundation-1.1.0.pom
Required by:
    project :app


BUILD FAILED in 55s
1 actionable task: 1 executed
```
