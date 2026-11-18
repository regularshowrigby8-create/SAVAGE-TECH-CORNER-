# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44348703491  Commit: ce9d053b686d30c359209657cbfb1a0d2686c1d0

## compiler errors
```
18:FAILURE: Build failed with an exception.
20:* What went wrong:
181:Caused by: com.android.builder.errors.EvalIssueException: compileSdkVersion is not specified. Please add it to build.gradle
```

## last 60 lines
```
	at org.gradle.internal.buildtree.DefaultBuildTreeContext.execute(DefaultBuildTreeContext.java:40)
	at org.gradle.launcher.exec.BuildTreeLifecycleBuildActionExecutor.lambda$execute$0(BuildTreeLifecycleBuildActionExecutor.java:71)
	at org.gradle.internal.buildtree.BuildTreeState.run(BuildTreeState.java:60)
	at org.gradle.launcher.exec.BuildTreeLifecycleBuildActionExecutor.execute(BuildTreeLifecycleBuildActionExecutor.java:71)
	at org.gradle.launcher.exec.RunAsBuildOperationBuildActionExecutor$3.call(RunAsBuildOperationBuildActionExecutor.java:61)
	at org.gradle.launcher.exec.RunAsBuildOperationBuildActionExecutor$3.call(RunAsBuildOperationBuildActionExecutor.java:57)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:209)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:204)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:66)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:166)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:59)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:53)
	at org.gradle.launcher.exec.RunAsBuildOperationBuildActionExecutor.execute(RunAsBuildOperationBuildActionExecutor.java:57)
	at org.gradle.launcher.exec.RunAsWorkerThreadBuildActionExecutor.lambda$execute$0(RunAsWorkerThreadBuildActionExecutor.java:36)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:267)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:131)
	at org.gradle.launcher.exec.RunAsWorkerThreadBuildActionExecutor.execute(RunAsWorkerThreadBuildActionExecutor.java:36)
	at org.gradle.tooling.internal.provider.continuous.ContinuousBuildActionExecutor.execute(ContinuousBuildActionExecutor.java:110)
	at org.gradle.tooling.internal.provider.SubscribableBuildActionExecutor.execute(SubscribableBuildActionExecutor.java:64)
	at org.gradle.internal.session.DefaultBuildSessionContext.execute(DefaultBuildSessionContext.java:46)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor$ActionImpl.apply(BuildSessionLifecycleBuildActionExecutor.java:92)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor$ActionImpl.apply(BuildSessionLifecycleBuildActionExecutor.java:80)
	at org.gradle.internal.session.BuildSessionState.run(BuildSessionState.java:71)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor.execute(BuildSessionLifecycleBuildActionExecutor.java:62)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor.execute(BuildSessionLifecycleBuildActionExecutor.java:41)
	at org.gradle.internal.buildprocess.execution.StartParamsValidatingActionExecutor.execute(StartParamsValidatingActionExecutor.java:64)
	at org.gradle.internal.buildprocess.execution.StartParamsValidatingActionExecutor.execute(StartParamsValidatingActionExecutor.java:32)
	at org.gradle.internal.buildprocess.execution.SessionFailureReportingActionExecutor.execute(SessionFailureReportingActionExecutor.java:51)
	at org.gradle.internal.buildprocess.execution.SessionFailureReportingActionExecutor.execute(SessionFailureReportingActionExecutor.java:39)
	at org.gradle.internal.buildprocess.execution.SetupLoggingActionExecutor.execute(SetupLoggingActionExecutor.java:47)
	at org.gradle.internal.buildprocess.execution.SetupLoggingActionExecutor.execute(SetupLoggingActionExecutor.java:31)
	at org.gradle.launcher.daemon.server.exec.ExecuteBuild.doBuild(ExecuteBuild.java:70)
	at org.gradle.launcher.daemon.server.exec.BuildCommandOnly.execute(BuildCommandOnly.java:37)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.WatchForDisconnection.execute(WatchForDisconnection.java:39)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.ResetDeprecationLogger.execute(ResetDeprecationLogger.java:29)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.RequestStopIfSingleUsedDaemon.execute(RequestStopIfSingleUsedDaemon.java:35)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.ForwardClientInput.lambda$execute$0(ForwardClientInput.java:40)
	at org.gradle.internal.daemon.clientinput.ClientInputForwarder.forwardInput(ClientInputForwarder.java:80)
	at org.gradle.launcher.daemon.server.exec.ForwardClientInput.execute(ForwardClientInput.java:37)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.LogAndCheckHealth.execute(LogAndCheckHealth.java:53)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.LogToClient.doBuild(LogToClient.java:63)
	at org.gradle.launcher.daemon.server.exec.BuildCommandOnly.execute(BuildCommandOnly.java:37)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.EstablishBuildEnvironment.doBuild(EstablishBuildEnvironment.java:84)
	at org.gradle.launcher.daemon.server.exec.BuildCommandOnly.execute(BuildCommandOnly.java:37)
	at org.gradle.launcher.daemon.server.api.DaemonCommandExecution.proceed(DaemonCommandExecution.java:104)
	at org.gradle.launcher.daemon.server.exec.StartBuildOrRespondWithBusy$1.run(StartBuildOrRespondWithBusy.java:52)
	at org.gradle.launcher.daemon.server.DaemonStateCoordinator.lambda$runCommand$0(DaemonStateCoordinator.java:320)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)


BUILD FAILED in 1m 11s
```
