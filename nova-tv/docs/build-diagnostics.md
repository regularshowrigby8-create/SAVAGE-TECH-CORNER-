# nova-tv build diagnostics

Written automatically by the "nova-tv Android" workflow.
Run: 44653228981  Commit: 1913fc4317b10b0d66a0a76c830c65b0cb7fc9ea
Log: build.log

## errors
```
18:FAILURE: Build failed with an exception.
20:* What went wrong:
683:Caused by: org.gradle.api.internal.artifacts.ivyservice.TypedResolveException: Could not resolve all artifacts for configuration 'classpath'.
874:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'.
1125:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
1149:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'.
1403:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
1430:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.0.21/kotlin-reflect-2.0.21.pom'.
1684:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.0.21/kotlin-reflect-2.0.21.pom'. Received status code 429 from server: Too Many Requests
1708:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-reflect/2.0.21/kotlin-reflect-2.0.21.pom'.
1962:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-reflect/2.0.21/kotlin-reflect-2.0.21.pom'. Received status code 429 from server: Too Many Requests
1989:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom'.
2243:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom'. Received status code 429 from server: Too Many Requests
2267:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/annotations/13.0/annotations-13.0.pom'.
2521:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/annotations/13.0/annotations-13.0.pom'. Received status code 429 from server: Too Many Requests
2548:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/kotlin-gradle-plugin-2.0.21.pom'.
2802:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/kotlin-gradle-plugin-2.0.21.pom'. Received status code 429 from server: Too Many Requests
2826:Caused by: org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParseException: Could not parse POM https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.0.21/kotlin-gradle-plugin-2.0.21.pom
3078:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
3079:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
3080:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'.
3374:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'. Received status code 429 from server: Too Many Requests
3401:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.0.21/compose-compiler-gradle-plugin-2.0.21.pom'.
3655:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.0.21/compose-compiler-gradle-plugin-2.0.21.pom'. Received status code 429 from server: Too Many Requests
3679:Caused by: org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParseException: Could not parse POM https://plugins.gradle.org/m2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.0.21/compose-compiler-gradle-plugin-2.0.21.pom
3931:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
3932:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
3933:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'.
4227:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'. Received status code 429 from server: Too Many Requests
4254:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-serialization/2.0.21/kotlin-serialization-2.0.21.pom'.
4508:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-serialization/2.0.21/kotlin-serialization-2.0.21.pom'. Received status code 429 from server: Too Many Requests
4532:Caused by: org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.MetaDataParseException: Could not parse POM https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-serialization/2.0.21/kotlin-serialization-2.0.21.pom
4784:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
4785:Caused by: org.gradle.internal.resolve.ModuleVersionResolveException: Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugins-bom:2.0.21.
4786:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'.
5080:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugins-bom/2.0.21/kotlin-gradle-plugins-bom-2.0.21.pom'. Received status code 429 from server: Too Many Requests
5114:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'.
5365:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
5389:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'.
5643:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.pom'. Received status code 429 from server: Too Many Requests
5672:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpmime/4.5.6/httpmime-4.5.6.pom'.
5926:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpmime/4.5.6/httpmime-4.5.6.pom'. Received status code 429 from server: Too Many Requests
5950:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/apache/httpcomponents/httpmime/4.5.6/httpmime-4.5.6.pom'.
6204:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/apache/httpcomponents/httpmime/4.5.6/httpmime-4.5.6.pom'. Received status code 429 from server: Too Many Requests
6232:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.pom'.
6486:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.pom'. Received status code 429 from server: Too Many Requests
6510:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/commons-io/commons-io/2.16.1/commons-io-2.16.1.pom'.
6764:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/commons-io/commons-io/2.16.1/commons-io-2.16.1.pom'. Received status code 429 from server: Too Many Requests
6793:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.pom'.
7047:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.pom'. Received status code 429 from server: Too Many Requests
7071:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/ow2/asm/asm/9.8/asm-9.8.pom'.
7325:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.8/asm-9.8.pom'. Received status code 429 from server: Too Many Requests
7352:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.pom'.
7606:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.pom'. Received status code 429 from server: Too Many Requests
7630:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.pom'.
7884:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.pom'. Received status code 429 from server: Too Many Requests
7912:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.pom'.
8166:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.pom'. Received status code 429 from server: Too Many Requests
8190:Caused by: org.gradle.api.resources.ResourceException: Could not get resource 'https://plugins.gradle.org/m2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.pom'.
8444:Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.8/asm-commons-9.8.pom'. Received status code 429 from server: Too Many Requests
```

## last 80 lines
```
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
	at org.gradle.launcher.exec.RunAsBuildOperationBuildActionExecutor.execute(RunAsBuildOperationBuildActionExecutor.java:63)
	at org.gradle.launcher.exec.RunAsWorkerThreadBuildActionExecutor.lambda$execute$0(RunAsWorkerThreadBuildActionExecutor.java:36)
	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:263)
	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:127)
	at org.gradle.launcher.exec.RunAsWorkerThreadBuildActionExecutor.execute(RunAsWorkerThreadBuildActionExecutor.java:36)
	at org.gradle.tooling.internal.provider.continuous.ContinuousBuildActionExecutor.execute(ContinuousBuildActionExecutor.java:110)
	at org.gradle.tooling.internal.provider.SubscribableBuildActionExecutor.execute(SubscribableBuildActionExecutor.java:64)
	at org.gradle.internal.session.DefaultBuildSessionContext.execute(DefaultBuildSessionContext.java:46)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor$ActionImpl.apply(BuildSessionLifecycleBuildActionExecutor.java:92)
	at org.gradle.internal.buildprocess.execution.BuildSessionLifecycleBuildActionExecutor$ActionImpl.apply(BuildSessionLifecycleBuildActionExecutor.java:80)
	at org.gradle.internal.session.BuildSessionState.run(BuildSessionState.java:73)
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
	at org.gradle.launcher.daemon.server.DaemonStateCoordinator.lambda$runCommand$0(DaemonStateCoordinator.java:321)
	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:48)
	at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
	at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
	at java.base/java.lang.Thread.run(Thread.java:840)
Caused by: org.gradle.internal.resource.transport.http.HttpErrorStatusCodeException: Could not GET 'https://repo.maven.apache.org/maven2/com/google/code/findbugs/jsr305/3.0.2/jsr305-3.0.2.pom'. Received status code 429 from server: Too Many Requests
	at org.gradle.internal.resource.transport.http.HttpClientHelper.processResponse(HttpClientHelper.java:251)
	at org.gradle.internal.resource.transport.http.HttpClientHelper.performGet(HttpClientHelper.java:105)
	at org.gradle.internal.resource.transport.http.HttpResourceAccessor.openResource(HttpResourceAccessor.java:45)
	at org.gradle.internal.resource.transport.http.HttpResourceAccessor.openResource(HttpResourceAccessor.java:30)
	at org.gradle.internal.resource.transfer.AbstractExternalResourceAccessor.withContent(AbstractExternalResourceAccessor.java:32)
	at org.gradle.internal.resource.transfer.DefaultExternalResourceConnector.withContent(DefaultExternalResourceConnector.java:59)
	at org.gradle.internal.resource.transfer.ProgressLoggingExternalResourceAccessor$DownloadOperation.call(ProgressLoggingExternalResourceAccessor.java:136)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
	at org.gradle.internal.resource.transfer.ProgressLoggingExternalResourceAccessor.withContent(ProgressLoggingExternalResourceAccessor.java:49)
	at org.gradle.internal.resource.transfer.AccessorBackedExternalResource.withContentIfPresent(AccessorBackedExternalResource.java:103)
	at org.gradle.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor.copyToCache(DefaultCacheAwareExternalResourceAccessor.java:188)
	at org.gradle.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor.lambda$getResource$1(DefaultCacheAwareExternalResourceAccessor.java:86)
	at org.gradle.cache.internal.ProducerGuard$AdaptiveProducerGuard.guardByKey(ProducerGuard.java:97)
	at org.gradle.internal.resource.transfer.DefaultCacheAwareExternalResourceAccessor.getResource(DefaultCacheAwareExternalResourceAccessor.java:80)
	at org.gradle.api.internal.artifacts.repositories.resolver.DefaultExternalResourceArtifactResolver.downloadByCoords(DefaultExternalResourceArtifactResolver.java:149)
	... 250 more


BUILD FAILED in 5m 28s
```
