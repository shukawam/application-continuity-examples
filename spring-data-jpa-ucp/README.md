# Spring Data JPA UCP

Spring Boot による ADB(ATP) + ucp を用いた Application Continuity の動作確認を行うサンプル実装。

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

## Spring Boot(Spring Data JPA) 側の設定

### application.yaml

以下が設定ポイント。その他は環境に合わせて設定してください。

- `connection-factor-class-name: oracle.jdbc.replay.OracleDataSourceImpl`

```yaml
spring:
  datasource:
    # Provide the database URL, username, password.
    url:
    username:
    password:
    # Properties for Universal Connection Pool(UCP)
    driver-class-name: oracle.jdbc.OracleDriver
    # For using Replay datasource
    oracleucp:
      connection-factory-class-name: oracle.jdbc.replay.OracleDataSourceImpl
      sql-for-validate-connection: select * from dual
      connection-pool-name: JDBC_UCP_POOL
      initial-pool-size: 1
      min-pool-size: 1
      max-pool-size: 1
      inactive-connection-timeout: 10
      query-timeout: 600
```

### FAN の無効化

pom.xml から依存を抜くことで対応可能。

```xml
<dependency>
    <groupId>com.oracle.database.jdbc</groupId>
    <artifactId>ojdbc10-production</artifactId>
    <version>${ojdbc.version}</version>
    <type>pom</type>
    <!-- FAN 関係を依存から抜く -->
    <exclusions>
        <exclusion>
            <groupId>com.oracle.database.ha</groupId>
            <artifactId>simplefan</artifactId>
        </exclusion>
        <exclusion>
            <groupId>com.oracle.database.ha</groupId>
            <artifactId>ons</artifactId>
        </exclusion>
    </exclusions>
    <!-- FAN 関係を依存から抜く -->
</dependency>
```

## 起動

アプリケーションをビルドします。

```bash
./mvnw clean package -DskipTests
```

起動します。デフォルトのトランザクションタイムアウトが 60 秒なのに対し、1 秒 sleep を挟みながら 100 行 insert するため、起動時のパラメータでタイムアウトの設定を変更しています。（参考: [https://github.com/helidon-io/helidon/wiki/FAQ#jta-related-topics](https://github.com/helidon-io/helidon/wiki/FAQ#jta-related-topics)）

```bash
java -jar target/spring-data-jpa-ucp-1.0.0.jar
```

もしくは、Dev モードで実行可能です。

```bash
./mvnw spring-boot:run
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
2023-03-06T11:20:42.202+09:00  INFO 2463225 --- [nio-8080-exec-1] com.oracle.jp.service.ACUCPService       : insert 1 row...
# ... omit
2023-03-06T11:21:58.520+09:00  INFO 2463225 --- [nio-8080-exec-1] com.oracle.jp.service.ACUCPService       : insert 77 row...
2023-03-06T11:21:58.524+09:00  WARN 2463225 --- [nio-8080-exec-1] com.zaxxer.hikari.pool.ProxyConnection   : HikariPool-1 - Connection oracle.jdbc.driver.T4CConnection@176e5452 marked as broken because of SQLSTATE(08000), ErrorCode(17410)

java.sql.SQLRecoverableException: No more data to read from socket
        at oracle.jdbc.driver.T4CMAREngineNIO.prepareForUnmarshall(T4CMAREngineNIO.java:811) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CMAREngineNIO.unmarshalUB1(T4CMAREngineNIO.java:449) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.receive(T4CTTIfun.java:410) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.doRPC(T4CTTIfun.java:269) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4C8Oall.doOALL(T4C8Oall.java:655) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:270) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:91) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeForDescribe(T4CPreparedStatement.java:807) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.executeMaybeDescribe(OracleStatement.java:983) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(OracleStatement.java:1168) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeInternal(OraclePreparedStatement.java:3666) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeInternal(T4CPreparedStatement.java:1426) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeQuery(OraclePreparedStatement.java:3713) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatementWrapper.executeQuery(OraclePreparedStatementWrapper.java:1167) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at com.zaxxer.hikari.pool.ProxyPreparedStatement.executeQuery(ProxyPreparedStatement.java:52) ~[HikariCP-5.0.1.jar:na]
        at com.zaxxer.hikari.pool.HikariProxyPreparedStatement.executeQuery(HikariProxyPreparedStatement.java) ~[HikariCP-5.0.1.jar:na]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.executeQuery(DeferredResultSetAccess.java:217) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.getResultSet(DeferredResultSetAccess.java:146) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.advanceNext(JdbcValuesResultSetImpl.java:205) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.processNext(JdbcValuesResultSetImpl.java:85) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.AbstractJdbcValues.next(AbstractJdbcValues.java:29) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.internal.RowProcessingStateStandardImpl.next(RowProcessingStateStandardImpl.java:88) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:183) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:33) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.doExecuteQuery(JdbcSelectExecutorStandardImpl.java:443) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.executeQuery(JdbcSelectExecutorStandardImpl.java:166) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.list(JdbcSelectExecutorStandardImpl.java:91) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.spi.JdbcSelectExecutor.list(JdbcSelectExecutor.java:31) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:140) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl.load(SingleIdEntityLoaderStandardImpl.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.doLoad(AbstractEntityPersister.java:4416) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.load(AbstractEntityPersister.java:4406) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.loadFromDatasource(DefaultLoadEventListener.java:590) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doLoad(DefaultLoadEventListener.java:563) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.load(DefaultLoadEventListener.java:221) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.proxyOrLoad(DefaultLoadEventListener.java:358) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doOnLoad(DefaultLoadEventListener.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.onLoad(DefaultLoadEventListener.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:118) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoadNoChecks(SessionImpl.java:1244) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoad(SessionImpl.java:1232) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.doLoad(IdentifierLoadAccessImpl.java:195) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.lambda$load$1(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.perform(IdentifierLoadAccessImpl.java:108) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.load(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.get(SessionImpl.java:1028) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.lambda$entityIsDetached$0(DefaultMergeEventListener.java:344) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.spi.LoadQueryInfluencers.fromInternalFetchProfile(LoadQueryInfluencers.java:79) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.entityIsDetached(DefaultMergeEventListener.java:342) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:178) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:81) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:107) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireMerge(SessionImpl.java:830) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.merge(SessionImpl.java:816) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.ExtendedEntityManagerCreator$ExtendedEntityManagerInvocationHandler.invoke(ExtendedEntityManagerCreator.java:360) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.SharedEntityManagerCreator$SharedEntityManagerInvocationHandler.invoke(SharedEntityManagerCreator.java:311) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at org.springframework.data.jpa.repository.support.SimpleJpaRepository.save(SimpleJpaRepository.java:616) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker$RepositoryFragmentMethodInvoker.lambda$new$0(RepositoryMethodInvoker.java:288) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.doInvoke(RepositoryMethodInvoker.java:136) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.invoke(RepositoryMethodInvoker.java:120) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition$RepositoryFragments.invoke(RepositoryComposition.java:516) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition.invoke(RepositoryComposition.java:285) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryFactorySupport$ImplementationMethodExecutionInterceptor.invoke(RepositoryFactorySupport.java:628) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.doInvoke(QueryExecutorMethodInterceptor.java:168) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.invoke(QueryExecutorMethodInterceptor.java:143) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(PersistenceExceptionTranslationInterceptor.java:137) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.jpa.repository.support.CrudMethodMetadataPostProcessor$CrudMethodMetadataPopulatingMethodInterceptor.invoke(CrudMethodMetadataPostProcessor.java:163) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.interceptor.ExposeInvocationInterceptor.invoke(ExposeInvocationInterceptor.java:97) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.JdkDynamicAopProxy.invoke(JdkDynamicAopProxy.java:218) ~[spring-aop-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy110.save(Unknown Source) ~[na:na]
        at com.oracle.jp.service.ACUCPService.lambda$0(ACUCPService.java:33) ~[classes/:na]
        at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104) ~[na:na]
        at java.base/java.util.stream.IntPipeline$Head.forEach(IntPipeline.java:617) ~[na:na]
        at com.oracle.jp.service.ACUCPService.exec(ACUCPService.java:30) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:343) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:702) ~[spring-aop-6.0.6.jar:6.0.6]
        at com.oracle.jp.service.ACUCPService$$SpringCGLIB$$0.exec(<generated>) ~[classes/:na]
        at com.oracle.jp.controller.ACUCPStarter.start(ACUCPStarter.java:25) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:207) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:152) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:117) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:884) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:797) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1081) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:974) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1011) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:914) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:731) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:814) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:223) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:53) ~[tomcat-embed-websocket-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.FormContentFilter.doFilterInternal(FormContentFilter.java:93) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:201) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:177) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:97) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:542) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:119) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:92) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:78) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:357) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:400) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:65) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:859) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1734) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1191) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:659) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at java.base/java.lang.Thread.run(Thread.java:1589) ~[na:na]

2023-03-06T11:21:58.535+09:00  WARN 2463225 --- [nio-8080-exec-1] o.h.engine.jdbc.spi.SqlExceptionHelper   : SQL Error: 17410, SQLState: 08000
2023-03-06T11:21:58.535+09:00 ERROR 2463225 --- [nio-8080-exec-1] o.h.engine.jdbc.spi.SqlExceptionHelper   : No more data to read from socket
2023-03-06T11:21:58.539+09:00  INFO 2463225 --- [nio-8080-exec-1] o.h.e.internal.DefaultLoadEventListener  : HHH000327: Error performing load command

org.hibernate.exception.JDBCConnectionException: JDBC exception executing SQL [select a1_0.c1,a1_0.c2,a1_0.c3 from ac_test_table a1_0 where a1_0.c1=?]
        at org.hibernate.exception.internal.SQLStateConversionDelegate.convert(SQLStateConversionDelegate.java:98) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.exception.internal.StandardSQLExceptionConverter.convert(StandardSQLExceptionConverter.java:56) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:109) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:95) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.executeQuery(DeferredResultSetAccess.java:253) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.getResultSet(DeferredResultSetAccess.java:146) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.advanceNext(JdbcValuesResultSetImpl.java:205) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.processNext(JdbcValuesResultSetImpl.java:85) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.AbstractJdbcValues.next(AbstractJdbcValues.java:29) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.internal.RowProcessingStateStandardImpl.next(RowProcessingStateStandardImpl.java:88) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:183) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:33) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.doExecuteQuery(JdbcSelectExecutorStandardImpl.java:443) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.executeQuery(JdbcSelectExecutorStandardImpl.java:166) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.list(JdbcSelectExecutorStandardImpl.java:91) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.spi.JdbcSelectExecutor.list(JdbcSelectExecutor.java:31) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:140) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl.load(SingleIdEntityLoaderStandardImpl.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.doLoad(AbstractEntityPersister.java:4416) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.load(AbstractEntityPersister.java:4406) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.loadFromDatasource(DefaultLoadEventListener.java:590) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doLoad(DefaultLoadEventListener.java:563) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.load(DefaultLoadEventListener.java:221) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.proxyOrLoad(DefaultLoadEventListener.java:358) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doOnLoad(DefaultLoadEventListener.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.onLoad(DefaultLoadEventListener.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:118) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoadNoChecks(SessionImpl.java:1244) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoad(SessionImpl.java:1232) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.doLoad(IdentifierLoadAccessImpl.java:195) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.lambda$load$1(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.perform(IdentifierLoadAccessImpl.java:108) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.load(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.get(SessionImpl.java:1028) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.lambda$entityIsDetached$0(DefaultMergeEventListener.java:344) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.spi.LoadQueryInfluencers.fromInternalFetchProfile(LoadQueryInfluencers.java:79) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.entityIsDetached(DefaultMergeEventListener.java:342) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:178) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:81) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:107) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireMerge(SessionImpl.java:830) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.merge(SessionImpl.java:816) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.ExtendedEntityManagerCreator$ExtendedEntityManagerInvocationHandler.invoke(ExtendedEntityManagerCreator.java:360) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.SharedEntityManagerCreator$SharedEntityManagerInvocationHandler.invoke(SharedEntityManagerCreator.java:311) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at org.springframework.data.jpa.repository.support.SimpleJpaRepository.save(SimpleJpaRepository.java:616) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker$RepositoryFragmentMethodInvoker.lambda$new$0(RepositoryMethodInvoker.java:288) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.doInvoke(RepositoryMethodInvoker.java:136) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.invoke(RepositoryMethodInvoker.java:120) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition$RepositoryFragments.invoke(RepositoryComposition.java:516) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition.invoke(RepositoryComposition.java:285) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryFactorySupport$ImplementationMethodExecutionInterceptor.invoke(RepositoryFactorySupport.java:628) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.doInvoke(QueryExecutorMethodInterceptor.java:168) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.invoke(QueryExecutorMethodInterceptor.java:143) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(PersistenceExceptionTranslationInterceptor.java:137) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.jpa.repository.support.CrudMethodMetadataPostProcessor$CrudMethodMetadataPopulatingMethodInterceptor.invoke(CrudMethodMetadataPostProcessor.java:163) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.interceptor.ExposeInvocationInterceptor.invoke(ExposeInvocationInterceptor.java:97) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.JdkDynamicAopProxy.invoke(JdkDynamicAopProxy.java:218) ~[spring-aop-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy110.save(Unknown Source) ~[na:na]
        at com.oracle.jp.service.ACUCPService.lambda$0(ACUCPService.java:33) ~[classes/:na]
        at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104) ~[na:na]
        at java.base/java.util.stream.IntPipeline$Head.forEach(IntPipeline.java:617) ~[na:na]
        at com.oracle.jp.service.ACUCPService.exec(ACUCPService.java:30) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:343) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:702) ~[spring-aop-6.0.6.jar:6.0.6]
        at com.oracle.jp.service.ACUCPService$$SpringCGLIB$$0.exec(<generated>) ~[classes/:na]
        at com.oracle.jp.controller.ACUCPStarter.start(ACUCPStarter.java:25) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:207) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:152) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:117) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:884) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:797) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1081) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:974) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1011) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:914) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:731) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:814) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:223) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:53) ~[tomcat-embed-websocket-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.FormContentFilter.doFilterInternal(FormContentFilter.java:93) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:201) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:177) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:97) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:542) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:119) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:92) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:78) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:357) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:400) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:65) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:859) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1734) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1191) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:659) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at java.base/java.lang.Thread.run(Thread.java:1589) ~[na:na]
Caused by: java.sql.SQLRecoverableException: No more data to read from socket
        at oracle.jdbc.driver.T4CMAREngineNIO.prepareForUnmarshall(T4CMAREngineNIO.java:811) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CMAREngineNIO.unmarshalUB1(T4CMAREngineNIO.java:449) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.receive(T4CTTIfun.java:410) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.doRPC(T4CTTIfun.java:269) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4C8Oall.doOALL(T4C8Oall.java:655) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:270) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:91) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeForDescribe(T4CPreparedStatement.java:807) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.executeMaybeDescribe(OracleStatement.java:983) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(OracleStatement.java:1168) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeInternal(OraclePreparedStatement.java:3666) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeInternal(T4CPreparedStatement.java:1426) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeQuery(OraclePreparedStatement.java:3713) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatementWrapper.executeQuery(OraclePreparedStatementWrapper.java:1167) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at com.zaxxer.hikari.pool.ProxyPreparedStatement.executeQuery(ProxyPreparedStatement.java:52) ~[HikariCP-5.0.1.jar:na]
        at com.zaxxer.hikari.pool.HikariProxyPreparedStatement.executeQuery(HikariProxyPreparedStatement.java) ~[HikariCP-5.0.1.jar:na]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.executeQuery(DeferredResultSetAccess.java:217) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        ... 137 common frames omitted

2023-03-06T11:21:58.556+09:00 ERROR 2463225 --- [nio-8080-exec-1] o.s.t.i.TransactionInterceptor           : Application exception overridden by rollback exception

org.springframework.dao.DataAccessResourceFailureException: JDBC exception executing SQL [select a1_0.c1,a1_0.c2,a1_0.c3 from ac_test_table a1_0 where a1_0.c1=?]
        at org.springframework.orm.jpa.vendor.HibernateJpaDialect.convertHibernateAccessException(HibernateJpaDialect.java:254) ~[spring-orm-6.0.6.jar:6.0.6]
        at org.springframework.orm.jpa.vendor.HibernateJpaDialect.translateExceptionIfPossible(HibernateJpaDialect.java:233) ~[spring-orm-6.0.6.jar:6.0.6]
        at org.springframework.orm.jpa.AbstractEntityManagerFactoryBean.translateExceptionIfPossible(AbstractEntityManagerFactoryBean.java:550) ~[spring-orm-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.ChainedPersistenceExceptionTranslator.translateExceptionIfPossible(ChainedPersistenceExceptionTranslator.java:61) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.DataAccessUtils.translateIfNecessary(DataAccessUtils.java:242) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(PersistenceExceptionTranslationInterceptor.java:152) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.jpa.repository.support.CrudMethodMetadataPostProcessor$CrudMethodMetadataPopulatingMethodInterceptor.invoke(CrudMethodMetadataPostProcessor.java:163) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.interceptor.ExposeInvocationInterceptor.invoke(ExposeInvocationInterceptor.java:97) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.JdkDynamicAopProxy.invoke(JdkDynamicAopProxy.java:218) ~[spring-aop-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy110.save(Unknown Source) ~[na:na]
        at com.oracle.jp.service.ACUCPService.lambda$0(ACUCPService.java:33) ~[classes/:na]
        at java.base/java.util.stream.Streams$RangeIntSpliterator.forEachRemaining(Streams.java:104) ~[na:na]
        at java.base/java.util.stream.IntPipeline$Head.forEach(IntPipeline.java:617) ~[na:na]
        at com.oracle.jp.service.ACUCPService.exec(ACUCPService.java:30) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:343) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:196) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:163) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:702) ~[spring-aop-6.0.6.jar:6.0.6]
        at com.oracle.jp.service.ACUCPService$$SpringCGLIB$$0.exec(<generated>) ~[classes/:na]
        at com.oracle.jp.controller.ACUCPStarter.start(ACUCPStarter.java:25) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:207) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:152) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:117) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:884) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:797) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1081) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:974) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1011) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:914) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:731) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:814) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:223) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:53) ~[tomcat-embed-websocket-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.FormContentFilter.doFilterInternal(FormContentFilter.java:93) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:201) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:177) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:97) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:542) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:119) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:92) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:78) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:357) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:400) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:65) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:859) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1734) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1191) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:659) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at java.base/java.lang.Thread.run(Thread.java:1589) ~[na:na]
Caused by: org.hibernate.exception.JDBCConnectionException: JDBC exception executing SQL [select a1_0.c1,a1_0.c2,a1_0.c3 from ac_test_table a1_0 where a1_0.c1=?]
        at org.hibernate.exception.internal.SQLStateConversionDelegate.convert(SQLStateConversionDelegate.java:98) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.exception.internal.StandardSQLExceptionConverter.convert(StandardSQLExceptionConverter.java:56) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:109) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.jdbc.spi.SqlExceptionHelper.convert(SqlExceptionHelper.java:95) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.executeQuery(DeferredResultSetAccess.java:253) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.getResultSet(DeferredResultSetAccess.java:146) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.advanceNext(JdbcValuesResultSetImpl.java:205) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.JdbcValuesResultSetImpl.processNext(JdbcValuesResultSetImpl.java:85) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.jdbc.internal.AbstractJdbcValues.next(AbstractJdbcValues.java:29) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.internal.RowProcessingStateStandardImpl.next(RowProcessingStateStandardImpl.java:88) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:183) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.results.spi.ListResultsConsumer.consume(ListResultsConsumer.java:33) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.doExecuteQuery(JdbcSelectExecutorStandardImpl.java:443) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.executeQuery(JdbcSelectExecutorStandardImpl.java:166) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.internal.JdbcSelectExecutorStandardImpl.list(JdbcSelectExecutorStandardImpl.java:91) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.sql.exec.spi.JdbcSelectExecutor.list(JdbcSelectExecutor.java:31) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:140) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdLoadPlan.load(SingleIdLoadPlan.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.ast.internal.SingleIdEntityLoaderStandardImpl.load(SingleIdEntityLoaderStandardImpl.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.doLoad(AbstractEntityPersister.java:4416) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.persister.entity.AbstractEntityPersister.load(AbstractEntityPersister.java:4406) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.loadFromDatasource(DefaultLoadEventListener.java:590) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doLoad(DefaultLoadEventListener.java:563) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.load(DefaultLoadEventListener.java:221) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.proxyOrLoad(DefaultLoadEventListener.java:358) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.doOnLoad(DefaultLoadEventListener.java:110) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultLoadEventListener.onLoad(DefaultLoadEventListener.java:72) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:118) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoadNoChecks(SessionImpl.java:1244) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireLoad(SessionImpl.java:1232) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.doLoad(IdentifierLoadAccessImpl.java:195) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.lambda$load$1(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.perform(IdentifierLoadAccessImpl.java:108) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.loader.access.IdentifierLoadAccessImpl.load(IdentifierLoadAccessImpl.java:161) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.get(SessionImpl.java:1028) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.lambda$entityIsDetached$0(DefaultMergeEventListener.java:344) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.spi.LoadQueryInfluencers.fromInternalFetchProfile(LoadQueryInfluencers.java:79) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.entityIsDetached(DefaultMergeEventListener.java:342) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:178) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.internal.DefaultMergeEventListener.onMerge(DefaultMergeEventListener.java:81) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.event.service.internal.EventListenerGroupImpl.fireEventOnEachListener(EventListenerGroupImpl.java:107) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.fireMerge(SessionImpl.java:830) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.internal.SessionImpl.merge(SessionImpl.java:816) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.ExtendedEntityManagerCreator$ExtendedEntityManagerInvocationHandler.invoke(ExtendedEntityManagerCreator.java:360) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.orm.jpa.SharedEntityManagerCreator$SharedEntityManagerInvocationHandler.invoke(SharedEntityManagerCreator.java:311) ~[spring-orm-6.0.6.jar:6.0.6]
        at jdk.proxy4/jdk.proxy4.$Proxy106.merge(Unknown Source) ~[na:na]
        at org.springframework.data.jpa.repository.support.SimpleJpaRepository.save(SimpleJpaRepository.java:616) ~[spring-data-jpa-3.0.3.jar:3.0.3]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker$RepositoryFragmentMethodInvoker.lambda$new$0(RepositoryMethodInvoker.java:288) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.doInvoke(RepositoryMethodInvoker.java:136) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryMethodInvoker.invoke(RepositoryMethodInvoker.java:120) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition$RepositoryFragments.invoke(RepositoryComposition.java:516) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryComposition.invoke(RepositoryComposition.java:285) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.RepositoryFactorySupport$ImplementationMethodExecutionInterceptor.invoke(RepositoryFactorySupport.java:628) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.doInvoke(QueryExecutorMethodInterceptor.java:168) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.data.repository.core.support.QueryExecutorMethodInterceptor.invoke(QueryExecutorMethodInterceptor.java:143) ~[spring-data-commons-3.0.3.jar:3.0.3]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor$1.proceedWithInvocation(TransactionInterceptor.java:123) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:390) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.dao.support.PersistenceExceptionTranslationInterceptor.invoke(PersistenceExceptionTranslationInterceptor.java:137) ~[spring-tx-6.0.6.jar:6.0.6]
        ... 73 common frames omitted
Caused by: java.sql.SQLRecoverableException: No more data to read from socket
        at oracle.jdbc.driver.T4CMAREngineNIO.prepareForUnmarshall(T4CMAREngineNIO.java:811) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CMAREngineNIO.unmarshalUB1(T4CMAREngineNIO.java:449) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.receive(T4CTTIfun.java:410) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CTTIfun.doRPC(T4CTTIfun.java:269) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4C8Oall.doOALL(T4C8Oall.java:655) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:270) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.doOall8(T4CPreparedStatement.java:91) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeForDescribe(T4CPreparedStatement.java:807) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.executeMaybeDescribe(OracleStatement.java:983) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OracleStatement.doExecuteWithTimeout(OracleStatement.java:1168) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeInternal(OraclePreparedStatement.java:3666) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.T4CPreparedStatement.executeInternal(T4CPreparedStatement.java:1426) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatement.executeQuery(OraclePreparedStatement.java:3713) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at oracle.jdbc.driver.OraclePreparedStatementWrapper.executeQuery(OraclePreparedStatementWrapper.java:1167) ~[ojdbc10-19.18.0.0.jar:19.18.0.0.0]
        at com.zaxxer.hikari.pool.ProxyPreparedStatement.executeQuery(ProxyPreparedStatement.java:52) ~[HikariCP-5.0.1.jar:na]
        at com.zaxxer.hikari.pool.HikariProxyPreparedStatement.executeQuery(HikariProxyPreparedStatement.java) ~[HikariCP-5.0.1.jar:na]
        at org.hibernate.sql.results.jdbc.internal.DeferredResultSetAccess.executeQuery(DeferredResultSetAccess.java:217) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        ... 137 common frames omitted

2023-03-06T11:21:58.567+09:00 ERROR 2463225 --- [nio-8080-exec-1] o.a.c.c.C.[.[.[/].[dispatcherServlet]    : Servlet.service() for servlet [dispatcherServlet] in context with path [] threw exception [Request processing failed: org.springframework.transaction.TransactionSystemException: Could not roll back JPA transaction] with root cause

java.sql.SQLException: Connection is closed
        at com.zaxxer.hikari.pool.ProxyConnection$ClosedConnection.lambda$getClosedConnection$0(ProxyConnection.java:502) ~[HikariCP-5.0.1.jar:na]
        at jdk.proxy3/jdk.proxy3.$Proxy68.rollback(Unknown Source) ~[na:na]
        at com.zaxxer.hikari.pool.ProxyConnection.rollback(ProxyConnection.java:385) ~[HikariCP-5.0.1.jar:na]
        at com.zaxxer.hikari.pool.HikariProxyConnection.rollback(HikariProxyConnection.java) ~[HikariCP-5.0.1.jar:na]
        at org.hibernate.resource.jdbc.internal.AbstractLogicalConnectionImplementor.rollback(AbstractLogicalConnectionImplementor.java:121) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.resource.transaction.backend.jdbc.internal.JdbcResourceLocalTransactionCoordinatorImpl$TransactionDriverControlImpl.rollback(JdbcResourceLocalTransactionCoordinatorImpl.java:304) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.hibernate.engine.transaction.internal.TransactionImpl.rollback(TransactionImpl.java:142) ~[hibernate-core-6.1.7.Final.jar:6.1.7.Final]
        at org.springframework.orm.jpa.JpaTransactionManager.doRollback(JpaTransactionManager.java:588) ~[spring-orm-6.0.6.jar:6.0.6]
        at org.springframework.transaction.support.AbstractPlatformTransactionManager.processRollback(AbstractPlatformTransactionManager.java:835) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.support.AbstractPlatformTransactionManager.rollback(AbstractPlatformTransactionManager.java:809) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.completeTransactionAfterThrowing(TransactionAspectSupport.java:677) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionAspectSupport.invokeWithinTransaction(TransactionAspectSupport.java:394) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.transaction.interceptor.TransactionInterceptor.invoke(TransactionInterceptor.java:119) ~[spring-tx-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:184) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$CglibMethodInvocation.proceed(CglibAopProxy.java:750) ~[spring-aop-6.0.6.jar:6.0.6]
        at org.springframework.aop.framework.CglibAopProxy$DynamicAdvisedInterceptor.intercept(CglibAopProxy.java:702) ~[spring-aop-6.0.6.jar:6.0.6]
        at com.oracle.jp.service.ACUCPService$$SpringCGLIB$$0.exec(<generated>) ~[classes/:na]
        at com.oracle.jp.controller.ACUCPStarter.start(ACUCPStarter.java:25) ~[classes/:na]
        at java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:104) ~[na:na]
        at java.base/java.lang.reflect.Method.invoke(Method.java:578) ~[na:na]
        at org.springframework.web.method.support.InvocableHandlerMethod.doInvoke(InvocableHandlerMethod.java:207) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.method.support.InvocableHandlerMethod.invokeForRequest(InvocableHandlerMethod.java:152) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.ServletInvocableHandlerMethod.invokeAndHandle(ServletInvocableHandlerMethod.java:117) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.invokeHandlerMethod(RequestMappingHandlerAdapter.java:884) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.handleInternal(RequestMappingHandlerAdapter.java:797) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.mvc.method.AbstractHandlerMethodAdapter.handle(AbstractHandlerMethodAdapter.java:87) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1081) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.DispatcherServlet.doService(DispatcherServlet.java:974) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.processRequest(FrameworkServlet.java:1011) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at org.springframework.web.servlet.FrameworkServlet.doPost(FrameworkServlet.java:914) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:731) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.springframework.web.servlet.FrameworkServlet.service(FrameworkServlet.java:885) ~[spring-webmvc-6.0.6.jar:6.0.6]
        at jakarta.servlet.http.HttpServlet.service(HttpServlet.java:814) ~[tomcat-embed-core-10.1.5.jar:6.0]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:223) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.websocket.server.WsFilter.doFilter(WsFilter.java:53) ~[tomcat-embed-websocket-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.RequestContextFilter.doFilterInternal(RequestContextFilter.java:100) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.FormContentFilter.doFilterInternal(FormContentFilter.java:93) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.springframework.web.filter.CharacterEncodingFilter.doFilterInternal(CharacterEncodingFilter.java:201) ~[spring-web-6.0.6.jar:6.0.6]
        at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116) ~[spring-web-6.0.6.jar:6.0.6]
        at org.apache.catalina.core.ApplicationFilterChain.internalDoFilter(ApplicationFilterChain.java:185) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.ApplicationFilterChain.doFilter(ApplicationFilterChain.java:158) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardWrapperValve.invoke(StandardWrapperValve.java:177) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardContextValve.invoke(StandardContextValve.java:97) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.authenticator.AuthenticatorBase.invoke(AuthenticatorBase.java:542) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardHostValve.invoke(StandardHostValve.java:119) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.valves.ErrorReportValve.invoke(ErrorReportValve.java:92) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.core.StandardEngineValve.invoke(StandardEngineValve.java:78) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.catalina.connector.CoyoteAdapter.service(CoyoteAdapter.java:357) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.http11.Http11Processor.service(Http11Processor.java:400) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProcessorLight.process(AbstractProcessorLight.java:65) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.coyote.AbstractProtocol$ConnectionHandler.process(AbstractProtocol.java:859) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.NioEndpoint$SocketProcessor.doRun(NioEndpoint.java:1734) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.net.SocketProcessorBase.run(SocketProcessorBase.java:52) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1191) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:659) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at org.apache.tomcat.util.threads.TaskThread$WrappingRunnable.run(TaskThread.java:61) ~[tomcat-embed-core-10.1.5.jar:10.1.5]
        at java.base/java.lang.Thread.run(Thread.java:1589) ~[na:na]
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

## Special Thanks

- [Autonomous Database でのアプリケーション・コンティニュイティの構成](https://docs.oracle.com/cd//E83857_01/paas/autonomous-database/adbsa/application-continuity-configure.html)
- [Spring Common Application Properties - Data Properties(Oracle UCP)](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data.spring.datasource.oracleucp)
