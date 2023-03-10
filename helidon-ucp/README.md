# Helidon UCP

Helidon MP による ADB(ATP) + ucp を用いた Application Continuity の動作確認を行うサンプル実装。

## Pre-required

- Java 17+
- Autonomous Database の接続に必要な Wallet

## ATP 側の設定

`ADMIN` ユーザーで実行

```sql
-- Transparent Application Continuity(TAC) を有効化するサービス名の検索
SELECT name, failover_type FROM DBA_SERVICES;

-- TAC を有効化する
BEGIN
    DBMS_APP_CONT_ADMIN.ENABLE_TAC(
        'SYA6VPHK3PZLKHQ_SHUKAWAMATP_high.adb.oraclecloud.com', 'AUTO', 600
    );
END;
/

-- TAC が有効かどうか確認する
SELECT name, failover_type FROM DBA_SERVICES;

-- （検証に応じてオプション）TAC を無効化する
BEGIN
    DBMS_APP_CONT_ADMIN.DISABLE_FAILOVER(
        'SYA6VPHK3PZLKHQ_SHUKAWAMATP_high.adb.oraclecloud.com'
    );
END;
/
```

## Helidon(JPA + JTA) 側の設定

### application.yaml

- `fastConnectionFailoverEnabled: false`
  - ADB で試す場合は、FAN イベントを**明示的**に無効にしないと起動時に warning ログが出力されます

```yaml
ucp:
  jdbc:
    PoolDataSource:
      ds1:
        URL: jdbc:oracle:thin:@shukawamatp_high?TNS_ADMIN=/home/shukawam/work/wallet/Wallet_shukawamatp
        connectionFactoryClassName: oracle.jdbc.replay.OracleDataSourceImpl
        user: ADMIN
        password:
        connectionPoolName: JDBC_UCP_POOL
        initialPoolSize: 1
        minPoolSize: 1
        maxPoolSize: 1
        timeoutCheckInterval: 10
        inactiveConnectionTimeout: 10
        queryTimeout: 600
        fastConnectionFailoverEnabled: false
```

## 起動

アプリケーションをビルドします。

```bash
mvn clean package -DskipTests
```

起動します。デフォルトのトランザクションタイムアウトが 60 秒なのに対し、1 秒 sleep を挟みながら 100 行 insert するため、起動時のパラメータでタイムアウトの設定を変更しています。（参考: [https://github.com/helidon-io/helidon/wiki/FAQ#jta-related-topics](https://github.com/helidon-io/helidon/wiki/FAQ#jta-related-topics)）

```bash
java \
    -Dcom.arjuna.ats.arjuna.coordinator.defaultTimeout=200 \
    -jar target/helidon-ucp.jar
```

Helidon CLI がインストールされている場合は、以下コマンドで代用可能です。

```bash
helidon dev \
    --app-jvm-args -Dcom.arjuna.ats.arjuna.coordinator.defaultTimeout=200
```

## 実験

本アプリケーションは、以下のエンドポイントが実装されています。

- `/ucp/ac/start` - 1 秒の sleep を挟みながら 100 行のダミーデータを insert します
- `/ucp/ac/count` - 該当テーブル（AC_TEST_TABLE）に含まれている行数をカウントして返します
- `/ucp/ac/delete` - 該当テーブル（AC_TEST_TABLE）に含まれている全ての行を削除します

これらの API を用いて、100 行の insert 時に ATP に再起動をかけ、クライアントに例外が返されないことを確認します。

まずは、TAC が無効な状態で `/ucp/ac/start` を実行し、ATP に再起動を仕掛けます。

```bash
curl -X POST http://localhost:8080/ucp/ac/start
```

アプリケーションのログが以下のように出力され例外（ロールバック）が発生していることが確認できます。

```bash
# ... omit
2023.03.04 16:41:02 INFO com.oracle.jp.ACUCPService Thread[#32,helidon-server-1,5,server]: insert 1 row...
# ... omit
2023.03.04 16:42:41 INFO com.oracle.jp.ACUCPService Thread[#32,helidon-server-1,5,server]: insert 100 row...
2023.03.04 16:42:42 WARN org.hibernate.engine.jdbc.spi.SqlExceptionHelper Thread[#32,helidon-server-1,5,server]: SQL Error: 17002, SQLState: 08006
2023.03.04 16:42:42 ERROR org.hibernate.engine.jdbc.spi.SqlExceptionHelper Thread[#32,helidon-server-1,5,server]: IO Error: Connection closed
2023.03.04 16:42:42 INFO org.hibernate.engine.jdbc.batch.internal.AbstractBatchImpl Thread[#32,helidon-server-1,5,server]: HHH000010: On release of batch it still contained JDBC statements
2023.03.04 16:42:42 WARN com.arjuna.ats.arjuna Thread[#32,helidon-server-1,5,server]: ARJUNA012125: TwoPhaseCoordinator.beforeCompletion - failed for SynchronizationImple< 0:ffff7f000101:85dd:6402f60d:5, org.hibernate.resource.transaction.backend.jta.internal.synchronization.RegisteredSynchronization@7b0eff29 >
jakarta.persistence.PersistenceException: Converting `org.hibernate.exception.JDBCConnectionException` to JPA `PersistenceException` : could not execute statement
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:165)
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:175)
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:182)
        at org.hibernate.internal.SessionImpl.doFlush(SessionImpl.java:1426)
        at org.hibernate.internal.SessionImpl.managedFlush(SessionImpl.java:476)
        at org.hibernate.internal.SessionImpl.flushBeforeTransactionCompletion(SessionImpl.java:2233)
        at org.hibernate.internal.SessionImpl.beforeTransactionCompletion(SessionImpl.java:1929)
        at org.hibernate.engine.jdbc.internal.JdbcCoordinatorImpl.beforeTransactionCompletion(JdbcCoordinatorImpl.java:439)
        at org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorImpl.beforeCompletion(JtaTransactionCoordinatorImpl.java:356)
        at org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackCoordinatorNonTrackingImpl.beforeCompletion(SynchronizationCallbackCoordinatorNonTrackingImpl.java:47)
        at org.hibernate.resource.transaction.backend.jta.internal.synchronization.RegisteredSynchronization.beforeCompletion(RegisteredSynchronization.java:37)
        at com.arjuna.ats.internal.jta.resources.arjunacore.SynchronizationImple.beforeCompletion(SynchronizationImple.java:76)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.beforeCompletion(TwoPhaseCoordinator.java:360)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.end(TwoPhaseCoordinator.java:91)
        at com.arjuna.ats.arjuna.AtomicAction.commit(AtomicAction.java:162)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple.commitAndDisassociate(TransactionImple.java:1295)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.BaseTransaction.commit(BaseTransaction.java:128)
        at com.arjuna.ats.jta.cdi.DelegatingTransactionManager.commit(DelegatingTransactionManager.java:126)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager.commit(NarayanaTransactionManager.java:335)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager$Proxy$_$$_WeldClientProxy.commit(Unknown Source)
        at com.arjuna.ats.jta.cdi.TransactionHandler.endTransaction(TransactionHandler.java:83)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:206)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:183)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.doIntercept(TransactionalInterceptorRequired.java:53)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.intercept(TransactionalInterceptorBase.java:90)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.intercept(TransactionalInterceptorRequired.java:47)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.jboss.weld.interceptor.reader.SimpleInterceptorInvocation$SimpleMethodInvocation.invoke(SimpleInterceptorInvocation.java:73)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeAroundInvoke(InterceptorMethodHandler.java:84)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeInterception(InterceptorMethodHandler.java:72)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.invoke(InterceptorMethodHandler.java:56)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:79)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:68)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldSubclass.exec(Unknown Source)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldClientProxy.exec(Unknown Source)
        at com.oracle.jp.ACUCPStarter.start(ACUCPStarter.java:29)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.glassfish.jersey.server.model.internal.ResourceMethodInvocationHandlerFactory.lambda$static$0(ResourceMethodInvocationHandlerFactory.java:52)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher$1.run(AbstractJavaResourceMethodDispatcher.java:134)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.invoke(AbstractJavaResourceMethodDispatcher.java:177)
        at org.glassfish.jersey.server.model.internal.JavaResourceMethodDispatcherProvider$TypeOutInvoker.doDispatch(JavaResourceMethodDispatcherProvider.java:219)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.dispatch(AbstractJavaResourceMethodDispatcher.java:81)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.invoke(ResourceMethodInvoker.java:478)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:400)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:81)
        at org.glassfish.jersey.server.ServerRuntime$1.run(ServerRuntime.java:256)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:248)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:244)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:292)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:274)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:244)
        at org.glassfish.jersey.process.internal.RequestScope.runInScope(RequestScope.java:265)
        at org.glassfish.jersey.server.ServerRuntime.process(ServerRuntime.java:235)
        at org.glassfish.jersey.server.ApplicationHandler.handle(ApplicationHandler.java:684)
        at io.helidon.webserver.jersey.JerseySupport$JerseyHandler.lambda$doAccept$4(JerseySupport.java:335)
        at io.helidon.common.context.Contexts.runInContext(Contexts.java:117)
        at io.helidon.common.context.ContextAwareExecutorImpl.lambda$wrap$7(ContextAwareExecutorImpl.java:154)
        at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
        at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
        at java.base/java.lang.Thread.run(Thread.java:1589)
Caused by: org.hibernate.exception.JDBCConnectionException: could not execute statement
        at org.hibernate.exception.internal.SQLStateConversionDelegate.convert(SQLStateConversionDelegate.java:98)
        at org.hibernate.exception.internal.StandardSQLExceptionConverter.convert(StandardSQLExceptionConverter.java:56)
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:109)
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:95)
        at org.hibernate.engine.jdbc.internal.ResultSetReturnImpl.executeUpdate(ResultSetReturnImpl.java:200)
        at org.hibernate.engine.jdbc.batch.internal.NonBatchingBatch.addToBatch(NonBatchingBatch.java:39)
        at org.hibernate.persister.entity.AbstractEntityPersister.insert(AbstractEntityPersister.java:3383)
        at org.hibernate.persister.entity.AbstractEntityPersister.insert(AbstractEntityPersister.java:3988)
        at org.hibernate.action.internal.EntityInsertAction.execute(EntityInsertAction.java:103)
        at org.hibernate.engine.spi.ActionQueue.executeActions(ActionQueue.java:612)
        at org.hibernate.engine.spi.ActionQueue.lambda$executeActions$1(ActionQueue.java:483)
        at java.base/java.util.LinkedHashMap.forEach(LinkedHashMap.java:729)
        at org.hibernate.engine.spi.ActionQueue.executeActions(ActionQueue.java:480)
        at org.hibernate.event.internal.AbstractFlushingEventListener.performExecutions(AbstractFlushingEventListener.java:329)
        at org.hibernate.event.internal.DefaultFlushEventListener.onFlush(DefaultFlushEventListener.java:39)
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:107)
        at org.hibernate.internal.SessionImpl.doFlush(SessionImpl.java:1422)
        ... 58 more
Caused by: java.sql.SQLRecoverableException: IO Error: Connection closed
        at oracle.jdbc.driver.T4CPreparedStatement.executeForRows(T4CPreparedStatement.java:1062)
        at oracle.jdbc.driver.OracleStatement.executeSQLStatement(OracleStatement.java:1531)
        at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(OracleStatement.java:1311)
        at oracle.jdbc.driver.OraclePreparedStatement.executeInternal(OraclePreparedStatement.java:3746)
        at oracle.jdbc.driver.OraclePreparedStatement.executeLargeUpdate(OraclePreparedStatement.java:3918)
        at oracle.jdbc.driver.OraclePreparedStatement.executeUpdate(OraclePreparedStatement.java:3897)
        at oracle.jdbc.driver.OraclePreparedStatementWrapper.executeUpdate(OraclePreparedStatementWrapper.java:992)
        at oracle.ucp.jdbc.proxy.oracle$1ucp$1jdbc$1proxy$1oracle$1StatementProxy$2oracle$1jdbc$1internal$1OraclePreparedStatement$$$Proxy.executeUpdate(Unknown Source)
        at io.helidon.integrations.jdbc.DelegatingPreparedStatement.executeUpdate(DelegatingPreparedStatement.java:96)
        at org.hibernate.engine.jdbc.internal.ResultSetReturnImpl.executeUpdate(ResultSetReturnImpl.java:197)
        ... 70 more
Caused by: java.io.IOException: Connection closed
        at oracle.net.nt.SSLSocketChannel.readFromSocket(SSLSocketChannel.java:734)
        at oracle.net.nt.SSLSocketChannel.fillReadBuffer(SSLSocketChannel.java:350)
        at oracle.net.nt.SSLSocketChannel.fillAndUnwrap(SSLSocketChannel.java:280)
        at oracle.net.nt.SSLSocketChannel.read(SSLSocketChannel.java:130)
        at oracle.net.ns.NSProtocolNIO.doSocketRead(NSProtocolNIO.java:1119)
        at oracle.net.ns.NIOPacket.readHeader(NIOPacket.java:267)
        at oracle.net.ns.NIOPacket.readPacketFromSocketChannel(NIOPacket.java:199)
        at oracle.net.ns.NIOPacket.readFromSocketChannel(NIOPacket.java:141)
        at oracle.net.ns.NIOPacket.readFromSocketChannel(NIOPacket.java:114)
        at oracle.net.ns.NIONSDataChannel.readDataFromSocketChannel(NIONSDataChannel.java:98)
        at oracle.jdbc.driver.T4CMAREngineNIO.prepareForUnmarshall(T4CMAREngineNIO.java:834)
        at oracle.jdbc.driver.T4CMAREngineNIO.unmarshalUB1(T4CMAREngineNIO.java:487)
        at oracle.jdbc.driver.T4CTTIfun.receive(T4CTTIfun.java:622)
        at oracle.jdbc.driver.T4CTTIfun.doRPC(T4CTTIfun.java:299)
        at oracle.jdbc.driver.T4C8Oall.doOALL(T4C8Oall.java:498)
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:152)
        at oracle.jdbc.driver.T4CPreparedStatement.executeForRows(T4CPreparedStatement.java:1052)
        ... 79 more

2023.03.04 16:42:42 WARN com.arjuna.ats.jta Thread[#32,helidon-server-1,5,server]: ARJUNA016029: SynchronizationImple.afterCompletion - failed for io.helidon.integrations.jta.jdbc.JtaDataSource@62f768ef with exception
java.lang.IllegalStateException: The connection is closed
        at io.helidon.integrations.jta.jdbc.JtaDataSource.complete(JtaDataSource.java:261)
        at io.helidon.integrations.jta.jdbc.JtaDataSource.afterCompletion(JtaDataSource.java:210)
        at com.arjuna.ats.internal.jta.resources.arjunacore.SynchronizationImple.afterCompletion(SynchronizationImple.java:96)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.afterCompletion(TwoPhaseCoordinator.java:545)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.end(TwoPhaseCoordinator.java:101)
        at com.arjuna.ats.arjuna.AtomicAction.commit(AtomicAction.java:162)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple.commitAndDisassociate(TransactionImple.java:1295)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.BaseTransaction.commit(BaseTransaction.java:128)
        at com.arjuna.ats.jta.cdi.DelegatingTransactionManager.commit(DelegatingTransactionManager.java:126)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager.commit(NarayanaTransactionManager.java:335)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager$Proxy$_$$_WeldClientProxy.commit(Unknown Source)
        at com.arjuna.ats.jta.cdi.TransactionHandler.endTransaction(TransactionHandler.java:83)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:206)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:183)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.doIntercept(TransactionalInterceptorRequired.java:53)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.intercept(TransactionalInterceptorBase.java:90)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.intercept(TransactionalInterceptorRequired.java:47)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.jboss.weld.interceptor.reader.SimpleInterceptorInvocation$SimpleMethodInvocation.invoke(SimpleInterceptorInvocation.java:73)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeAroundInvoke(InterceptorMethodHandler.java:84)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeInterception(InterceptorMethodHandler.java:72)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.invoke(InterceptorMethodHandler.java:56)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:79)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:68)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldSubclass.exec(Unknown Source)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldClientProxy.exec(Unknown Source)
        at com.oracle.jp.ACUCPStarter.start(ACUCPStarter.java:29)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.glassfish.jersey.server.model.internal.ResourceMethodInvocationHandlerFactory.lambda$static$0(ResourceMethodInvocationHandlerFactory.java:52)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher$1.run(AbstractJavaResourceMethodDispatcher.java:134)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.invoke(AbstractJavaResourceMethodDispatcher.java:177)
        at org.glassfish.jersey.server.model.internal.JavaResourceMethodDispatcherProvider$TypeOutInvoker.doDispatch(JavaResourceMethodDispatcherProvider.java:219)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.dispatch(AbstractJavaResourceMethodDispatcher.java:81)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.invoke(ResourceMethodInvoker.java:478)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:400)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:81)
        at org.glassfish.jersey.server.ServerRuntime$1.run(ServerRuntime.java:256)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:248)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:244)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:292)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:274)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:244)
        at org.glassfish.jersey.process.internal.RequestScope.runInScope(RequestScope.java:265)
        at org.glassfish.jersey.server.ServerRuntime.process(ServerRuntime.java:235)
        at org.glassfish.jersey.server.ApplicationHandler.handle(ApplicationHandler.java:684)
        at io.helidon.webserver.jersey.JerseySupport$JerseyHandler.lambda$doAccept$4(JerseySupport.java:335)
        at io.helidon.common.context.Contexts.runInContext(Contexts.java:117)
        at io.helidon.common.context.ContextAwareExecutorImpl.lambda$wrap$7(ContextAwareExecutorImpl.java:154)
        at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
        at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
        at java.base/java.lang.Thread.run(Thread.java:1589)
        Suppressed: java.sql.SQLRecoverableException: The connection is closed
                at oracle.ucp.jdbc.proxy.oracle.ConnectionProxy.pre(ConnectionProxy.java:238)
                at oracle.ucp.jdbc.proxy.oracle$1ucp$1jdbc$1proxy$1oracle$1ConnectionProxy$2oracle$1jdbc$1internal$1OracleConnection$$$Proxy.setAutoCommit(Unknown Source)
                at io.helidon.integrations.jdbc.DelegatingConnection.setAutoCommit(DelegatingConnection.java:103)
                at io.helidon.integrations.jdbc.ConditionallyCloseableConnection.setAutoCommit(ConditionallyCloseableConnection.java:426)
                at io.helidon.integrations.jta.jdbc.JtaDataSource$TransactionSpecificConnection.restoreAutoCommit(JtaDataSource.java:558)
                at io.helidon.integrations.jta.jdbc.JtaDataSource.complete(JtaDataSource.java:267)
                ... 52 more
Caused by: java.sql.SQLRecoverableException: The connection is closed
        at oracle.ucp.jdbc.proxy.oracle.ConnectionProxy.pre(ConnectionProxy.java:238)
        at oracle.ucp.jdbc.proxy.oracle$1ucp$1jdbc$1proxy$1oracle$1ConnectionProxy$2oracle$1jdbc$1internal$1OracleConnection$$$Proxy.rollback(Unknown Source)
        at io.helidon.integrations.jdbc.DelegatingConnection.rollback(DelegatingConnection.java:118)
        at io.helidon.integrations.jdbc.ConditionallyCloseableConnection.rollback(ConditionallyCloseableConnection.java:444)
        at io.helidon.integrations.jta.jdbc.JtaDataSource.complete(JtaDataSource.java:252)
        ... 52 more

2023.03.04 16:42:42 WARN com.arjuna.ats.arjuna Thread[#32,helidon-server-1,5,server]: ARJUNA012127: TwoPhaseCoordinator.afterCompletion - returned failure for SynchronizationImple< 0:ffff7f000101:85dd:6402f60d:3, io.helidon.integrations.jta.jdbc.JtaDataSource@62f768ef >
2023.03.04 16:42:42 WARNING io.helidon.microprofile.server.JaxRsCdiExtension Thread[#32,helidon-server-1,5,server]: Internal server error
jakarta.transaction.RollbackException: ARJUNA016053: Could not commit transaction.
        at com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple.commitAndDisassociate(TransactionImple.java:1307)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.BaseTransaction.commit(BaseTransaction.java:128)
        at com.arjuna.ats.jta.cdi.DelegatingTransactionManager.commit(DelegatingTransactionManager.java:126)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager.commit(NarayanaTransactionManager.java:335)
        at com.arjuna.ats.jta.cdi.NarayanaTransactionManager$Proxy$_$$_WeldClientProxy.commit(Unknown Source)
        at com.arjuna.ats.jta.cdi.TransactionHandler.endTransaction(TransactionHandler.java:83)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:206)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.invokeInOurTx(TransactionalInterceptorBase.java:183)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.doIntercept(TransactionalInterceptorRequired.java:53)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorBase.intercept(TransactionalInterceptorBase.java:90)
        at com.arjuna.ats.jta.cdi.transactional.TransactionalInterceptorRequired.intercept(TransactionalInterceptorRequired.java:47)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.jboss.weld.interceptor.reader.SimpleInterceptorInvocation$SimpleMethodInvocation.invoke(SimpleInterceptorInvocation.java:73)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeAroundInvoke(InterceptorMethodHandler.java:84)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.executeInterception(InterceptorMethodHandler.java:72)
        at org.jboss.weld.interceptor.proxy.InterceptorMethodHandler.invoke(InterceptorMethodHandler.java:56)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:79)
        at org.jboss.weld.bean.proxy.CombinedInterceptorAndDecoratorStackMethodHandler.invoke(CombinedInterceptorAndDecoratorStackMethodHandler.java:68)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldSubclass.exec(Unknown Source)
        at com.oracle.jp.ACUCPService$Proxy$_$$_WeldClientProxy.exec(Unknown Source)
        at com.oracle.jp.ACUCPStarter.start(ACUCPStarter.java:29)
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104)
        at java.base/java.lang.reflect.Method.invoke(Method.java:578)
        at org.glassfish.jersey.server.model.internal.ResourceMethodInvocationHandlerFactory.lambda$static$0(ResourceMethodInvocationHandlerFactory.java:52)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher$1.run(AbstractJavaResourceMethodDispatcher.java:134)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.invoke(AbstractJavaResourceMethodDispatcher.java:177)
        at org.glassfish.jersey.server.model.internal.JavaResourceMethodDispatcherProvider$TypeOutInvoker.doDispatch(JavaResourceMethodDispatcherProvider.java:219)
        at org.glassfish.jersey.server.model.internal.AbstractJavaResourceMethodDispatcher.dispatch(AbstractJavaResourceMethodDispatcher.java:81)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.invoke(ResourceMethodInvoker.java:478)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:400)
        at org.glassfish.jersey.server.model.ResourceMethodInvoker.apply(ResourceMethodInvoker.java:81)
        at org.glassfish.jersey.server.ServerRuntime$1.run(ServerRuntime.java:256)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:248)
        at org.glassfish.jersey.internal.Errors$1.call(Errors.java:244)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:292)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:274)
        at org.glassfish.jersey.internal.Errors.process(Errors.java:244)
        at org.glassfish.jersey.process.internal.RequestScope.runInScope(RequestScope.java:265)
        at org.glassfish.jersey.server.ServerRuntime.process(ServerRuntime.java:235)
        at org.glassfish.jersey.server.ApplicationHandler.handle(ApplicationHandler.java:684)
        at io.helidon.webserver.jersey.JerseySupport$JerseyHandler.lambda$doAccept$4(JerseySupport.java:335)
        at io.helidon.common.context.Contexts.runInContext(Contexts.java:117)
        at io.helidon.common.context.ContextAwareExecutorImpl.lambda$wrap$7(ContextAwareExecutorImpl.java:154)
        at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
        at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
        at java.base/java.lang.Thread.run(Thread.java:1589)
Caused by: jakarta.persistence.PersistenceException: Converting `org.hibernate.exception.JDBCConnectionException` to JPA `PersistenceException` : could not execute statement
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:165)
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:175)
        at org.hibernate.internal.ExceptionConverterImpl.convert(ExceptionConverterImpl.java:182)
        at org.hibernate.internal.SessionImpl.doFlush(SessionImpl.java:1426)
        at org.hibernate.internal.SessionImpl.managedFlush(SessionImpl.java:476)
        at org.hibernate.internal.SessionImpl.flushBeforeTransactionCompletion(SessionImpl.java:2233)
        at org.hibernate.internal.SessionImpl.beforeTransactionCompletion(SessionImpl.java:1929)
        at org.hibernate.engine.jdbc.internal.JdbcCoordinatorImpl.beforeTransactionCompletion(JdbcCoordinatorImpl.java:439)
        at org.hibernate.resource.transaction.backend.jta.internal.JtaTransactionCoordinatorImpl.beforeCompletion(JtaTransactionCoordinatorImpl.java:356)
        at org.hibernate.resource.transaction.backend.jta.internal.synchronization.SynchronizationCallbackCoordinatorNonTrackingImpl.beforeCompletion(SynchronizationCallbackCoordinatorNonTrackingImpl.java:47)
        at org.hibernate.resource.transaction.backend.jta.internal.synchronization.RegisteredSynchronization.beforeCompletion(RegisteredSynchronization.java:37)
        at com.arjuna.ats.internal.jta.resources.arjunacore.SynchronizationImple.beforeCompletion(SynchronizationImple.java:76)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.beforeCompletion(TwoPhaseCoordinator.java:360)
        at com.arjuna.ats.arjuna.coordinator.TwoPhaseCoordinator.end(TwoPhaseCoordinator.java:91)
        at com.arjuna.ats.arjuna.AtomicAction.commit(AtomicAction.java:162)
        at com.arjuna.ats.internal.jta.transaction.arjunacore.TransactionImple.commitAndDisassociate(TransactionImple.java:1295)
        ... 46 more
Caused by: org.hibernate.exception.JDBCConnectionException: could not execute statement
        at org.hibernate.exception.internal.SQLStateConversionDelegate.convert(SQLStateConversionDelegate.java:98)
        at org.hibernate.exception.internal.StandardSQLExceptionConverter.convert(StandardSQLExceptionConverter.java:56)
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:109)
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:95)
        at org.hibernate.engine.jdbc.internal.ResultSetReturnImpl.executeUpdate(ResultSetReturnImpl.java:200)
        at org.hibernate.engine.jdbc.batch.internal.NonBatchingBatch.addToBatch(NonBatchingBatch.java:39)
        at org.hibernate.persister.entity.AbstractEntityPersister.insert(AbstractEntityPersister.java:3383)
        at org.hibernate.persister.entity.AbstractEntityPersister.insert(AbstractEntityPersister.java:3988)
        at org.hibernate.action.internal.EntityInsertAction.execute(EntityInsertAction.java:103)
        at org.hibernate.engine.spi.ActionQueue.executeActions(ActionQueue.java:612)
        at org.hibernate.engine.spi.ActionQueue.lambda$executeActions$1(ActionQueue.java:483)
        at java.base/java.util.LinkedHashMap.forEach(LinkedHashMap.java:729)
        at org.hibernate.engine.spi.ActionQueue.executeActions(ActionQueue.java:480)
        at org.hibernate.event.internal.AbstractFlushingEventListener.performExecutions(AbstractFlushingEventListener.java:329)
        at org.hibernate.event.internal.DefaultFlushEventListener.onFlush(DefaultFlushEventListener.java:39)
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:107)
        at org.hibernate.internal.SessionImpl.doFlush(SessionImpl.java:1422)
        ... 58 more
Caused by: java.sql.SQLRecoverableException: IO Error: Connection closed
        at oracle.jdbc.driver.T4CPreparedStatement.executeForRows(T4CPreparedStatement.java:1062)
        at oracle.jdbc.driver.OracleStatement.executeSQLStatement(OracleStatement.java:1531)
        at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(OracleStatement.java:1311)
        at oracle.jdbc.driver.OraclePreparedStatement.executeInternal(OraclePreparedStatement.java:3746)
        at oracle.jdbc.driver.OraclePreparedStatement.executeLargeUpdate(OraclePreparedStatement.java:3918)
        at oracle.jdbc.driver.OraclePreparedStatement.executeUpdate(OraclePreparedStatement.java:3897)
        at oracle.jdbc.driver.OraclePreparedStatementWrapper.executeUpdate(OraclePreparedStatementWrapper.java:992)
        at oracle.ucp.jdbc.proxy.oracle$1ucp$1jdbc$1proxy$1oracle$1StatementProxy$2oracle$1jdbc$1internal$1OraclePreparedStatement$$$Proxy.executeUpdate(Unknown Source)
        at io.helidon.integrations.jdbc.DelegatingPreparedStatement.executeUpdate(DelegatingPreparedStatement.java:96)
        at org.hibernate.engine.jdbc.internal.ResultSetReturnImpl.executeUpdate(ResultSetReturnImpl.java:197)
        ... 70 more
Caused by: java.io.IOException: Connection closed
        at oracle.net.nt.SSLSocketChannel.readFromSocket(SSLSocketChannel.java:734)
        at oracle.net.nt.SSLSocketChannel.fillReadBuffer(SSLSocketChannel.java:350)
        at oracle.net.nt.SSLSocketChannel.fillAndUnwrap(SSLSocketChannel.java:280)
        at oracle.net.nt.SSLSocketChannel.read(SSLSocketChannel.java:130)
        at oracle.net.ns.NSProtocolNIO.doSocketRead(NSProtocolNIO.java:1119)
        at oracle.net.ns.NIOPacket.readHeader(NIOPacket.java:267)
        at oracle.net.ns.NIOPacket.readPacketFromSocketChannel(NIOPacket.java:199)
        at oracle.net.ns.NIOPacket.readFromSocketChannel(NIOPacket.java:141)
        at oracle.net.ns.NIOPacket.readFromSocketChannel(NIOPacket.java:114)
        at oracle.net.ns.NIONSDataChannel.readDataFromSocketChannel(NIONSDataChannel.java:98)
        at oracle.jdbc.driver.T4CMAREngineNIO.prepareForUnmarshall(T4CMAREngineNIO.java:834)
        at oracle.jdbc.driver.T4CMAREngineNIO.unmarshalUB1(T4CMAREngineNIO.java:487)
        at oracle.jdbc.driver.T4CTTIfun.receive(T4CTTIfun.java:622)
        at oracle.jdbc.driver.T4CTTIfun.doRPC(T4CTTIfun.java:299)
        at oracle.jdbc.driver.T4C8Oall.doOALL(T4C8Oall.java:498)
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:152)
        at oracle.jdbc.driver.T4CPreparedStatement.executeForRows(T4CPreparedStatement.java:1052)
        ... 79 more
```

テーブルに含まれている行数を確認してみると、0 行なことが確認できます。

```bash
curl http://localhost:8080/ucp/ac/count
Include 0 rows.
```

次に、TAC を有効にし同じように `/ucp/ac/start` を実行し。ATP に再起動を仕掛けると、今度は例外（ロールバック）が発生せずにすべての行の insert が行われていることが確認できます。

```bash
curl -X POST http://localhost:8080/ucp/ac/start
ok
```

テーブルに含まれている行数を確認してみます。今度は、100 行含まれていることが確認できます。

```bash
curl http://localhost:8080/ucp/ac/count
Include 100 rows.
```

## 参考

- [Autonomous Database でのアプリケーション・コンティニュイティの構成](https://docs.oracle.com/cd//E83857_01/paas/autonomous-database/adbsa/application-continuity-configure.html)
- [Helidon FAQ - JTA-Related Topics](https://github.com/helidon-io/helidon/wiki/FAQ#jta-related-topics)
