/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.spark;

import com.facebook.airlift.configuration.AbstractConfigurationAwareModule;
import com.facebook.airlift.node.NodeConfig;
import com.facebook.airlift.node.NodeInfo;
import com.facebook.presto.GroupByHashPageIndexerFactory;
import com.facebook.presto.PagesIndexPageSorter;
import com.facebook.presto.SystemSessionProperties;
import com.facebook.presto.block.BlockEncodingManager;
import com.facebook.presto.block.BlockJsonSerde;
import com.facebook.presto.client.NodeVersion;
import com.facebook.presto.connector.ConnectorManager;
import com.facebook.presto.cost.CostCalculator;
import com.facebook.presto.cost.CostCalculatorUsingExchanges;
import com.facebook.presto.cost.CostCalculatorWithEstimatedExchanges;
import com.facebook.presto.cost.CostComparator;
import com.facebook.presto.cost.StatsCalculatorModule;
import com.facebook.presto.cost.TaskCountEstimator;
import com.facebook.presto.event.QueryMonitor;
import com.facebook.presto.event.QueryMonitorConfig;
import com.facebook.presto.execution.DataDefinitionTask;
import com.facebook.presto.execution.ExecutionFailureInfo;
import com.facebook.presto.execution.ExplainAnalyzeContext;
import com.facebook.presto.execution.QueryIdGenerator;
import com.facebook.presto.execution.QueryManagerConfig;
import com.facebook.presto.execution.QueryPreparer;
import com.facebook.presto.execution.StageInfo;
import com.facebook.presto.execution.TaskManagerConfig;
import com.facebook.presto.execution.resourceGroups.InternalResourceGroupManager;
import com.facebook.presto.execution.resourceGroups.LegacyResourceGroupConfigurationManager;
import com.facebook.presto.execution.resourceGroups.ResourceGroupManager;
import com.facebook.presto.execution.scheduler.NodeSchedulerConfig;
import com.facebook.presto.index.IndexManager;
import com.facebook.presto.memory.MemoryManagerConfig;
import com.facebook.presto.memory.NodeMemoryConfig;
import com.facebook.presto.metadata.AnalyzePropertyManager;
import com.facebook.presto.metadata.CatalogManager;
import com.facebook.presto.metadata.ColumnPropertyManager;
import com.facebook.presto.metadata.FunctionManager;
import com.facebook.presto.metadata.HandleJsonModule;
import com.facebook.presto.metadata.InternalNodeManager;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataManager;
import com.facebook.presto.metadata.SchemaPropertyManager;
import com.facebook.presto.metadata.SessionPropertyManager;
import com.facebook.presto.metadata.StaticCatalogStore;
import com.facebook.presto.metadata.StaticCatalogStoreConfig;
import com.facebook.presto.metadata.StaticFunctionNamespaceStore;
import com.facebook.presto.metadata.StaticFunctionNamespaceStoreConfig;
import com.facebook.presto.metadata.TablePropertyManager;
import com.facebook.presto.metadata.ViewDefinition;
import com.facebook.presto.operator.LookupJoinOperators;
import com.facebook.presto.operator.OperatorStats;
import com.facebook.presto.operator.PagesIndex;
import com.facebook.presto.operator.TableCommitContext;
import com.facebook.presto.operator.TaskStats;
import com.facebook.presto.operator.index.IndexJoinLookupStats;
import com.facebook.presto.server.PluginManager;
import com.facebook.presto.server.PluginManagerConfig;
import com.facebook.presto.server.QuerySessionSupplier;
import com.facebook.presto.server.ServerConfig;
import com.facebook.presto.server.SessionPropertyDefaults;
import com.facebook.presto.server.security.ServerSecurityModule;
import com.facebook.presto.spark.execution.PrestoSparkTaskExecutorFactory;
import com.facebook.presto.spark.node.PrestoSparkInternalNodeManager;
import com.facebook.presto.spark.node.PrestoSparkNodePartitioningManager;
import com.facebook.presto.spark.planner.PrestoSparkPlanFragmenter;
import com.facebook.presto.spark.planner.PrestoSparkQueryPlanner;
import com.facebook.presto.spark.planner.PrestoSparkRddFactory;
import com.facebook.presto.spark.planner.PrestoSparkSplitEnumerator;
import com.facebook.presto.spi.PageIndexerFactory;
import com.facebook.presto.spi.PageSorter;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockEncoding;
import com.facebook.presto.spi.block.BlockEncodingSerde;
import com.facebook.presto.spi.memory.ClusterMemoryPoolManager;
import com.facebook.presto.spi.relation.DeterminismEvaluator;
import com.facebook.presto.spi.relation.DomainTranslator;
import com.facebook.presto.spi.relation.PredicateCompiler;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.facebook.presto.spiller.FileSingleStreamSpillerFactory;
import com.facebook.presto.spiller.GenericPartitioningSpillerFactory;
import com.facebook.presto.spiller.GenericSpillerFactory;
import com.facebook.presto.spiller.NodeSpillConfig;
import com.facebook.presto.spiller.PartitioningSpillerFactory;
import com.facebook.presto.spiller.SingleStreamSpillerFactory;
import com.facebook.presto.spiller.SpillerFactory;
import com.facebook.presto.spiller.SpillerStats;
import com.facebook.presto.split.PageSinkManager;
import com.facebook.presto.split.PageSinkProvider;
import com.facebook.presto.split.PageSourceManager;
import com.facebook.presto.split.PageSourceProvider;
import com.facebook.presto.split.SplitManager;
import com.facebook.presto.sql.Serialization.VariableReferenceExpressionDeserializer;
import com.facebook.presto.sql.Serialization.VariableReferenceExpressionSerializer;
import com.facebook.presto.sql.SqlEnvironmentConfig;
import com.facebook.presto.sql.analyzer.FeaturesConfig;
import com.facebook.presto.sql.analyzer.QueryExplainer;
import com.facebook.presto.sql.gen.ExpressionCompiler;
import com.facebook.presto.sql.gen.JoinCompiler;
import com.facebook.presto.sql.gen.JoinFilterFunctionCompiler;
import com.facebook.presto.sql.gen.OrderingCompiler;
import com.facebook.presto.sql.gen.PageFunctionCompiler;
import com.facebook.presto.sql.gen.RowExpressionPredicateCompiler;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.parser.SqlParserOptions;
import com.facebook.presto.sql.planner.CompilerConfig;
import com.facebook.presto.sql.planner.ConnectorPlanOptimizerManager;
import com.facebook.presto.sql.planner.LocalExecutionPlanner;
import com.facebook.presto.sql.planner.NodePartitioningManager;
import com.facebook.presto.sql.planner.PartitioningProviderManager;
import com.facebook.presto.sql.planner.PlanFragmenter;
import com.facebook.presto.sql.planner.PlanOptimizers;
import com.facebook.presto.sql.relational.RowExpressionDeterminismEvaluator;
import com.facebook.presto.sql.relational.RowExpressionDomainTranslator;
import com.facebook.presto.sql.tree.Statement;
import com.facebook.presto.transaction.InMemoryTransactionManager;
import com.facebook.presto.transaction.TransactionManager;
import com.facebook.presto.transaction.TransactionManagerConfig;
import com.facebook.presto.type.TypeDeserializer;
import com.facebook.presto.type.TypeRegistry;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.multibindings.MapBinder;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.testing.TestingMBeanServer;

import javax.inject.Singleton;
import javax.management.MBeanServer;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static com.facebook.airlift.concurrent.Threads.daemonThreadsNamed;
import static com.facebook.airlift.configuration.ConfigBinder.configBinder;
import static com.facebook.airlift.json.JsonBinder.jsonBinder;
import static com.facebook.airlift.json.JsonCodecBinder.jsonCodecBinder;
import static com.google.inject.multibindings.MapBinder.newMapBinder;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class PrestoSparkModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        // configs
        // TODO: decouple configuration properties that don't make sense on Spark
        configBinder(binder).bindConfig(NodeSchedulerConfig.class);
        configBinder(binder).bindConfig(QueryManagerConfig.class);
        configBinder(binder).bindConfig(FeaturesConfig.class);
        configBinder(binder).bindConfig(MemoryManagerConfig.class);
        configBinder(binder).bindConfig(TaskManagerConfig.class);
        configBinder(binder).bindConfig(TransactionManagerConfig.class);
        configBinder(binder).bindConfig(NodeMemoryConfig.class);
        configBinder(binder).bindConfig(NodeSpillConfig.class);
        configBinder(binder).bindConfig(CompilerConfig.class);
        configBinder(binder).bindConfig(SqlEnvironmentConfig.class);
        configBinder(binder).bindConfig(StaticFunctionNamespaceStoreConfig.class);
        configBinder(binder).bindConfig(PrestoSparkConfig.class);

        // json codecs
        jsonCodecBinder(binder).bindJsonCodec(ViewDefinition.class);
        jsonCodecBinder(binder).bindJsonCodec(TaskStats.class);
        jsonCodecBinder(binder).bindJsonCodec(PrestoSparkTaskDescriptor.class);
        jsonCodecBinder(binder).bindJsonCodec(TableCommitContext.class);
        jsonCodecBinder(binder).bindJsonCodec(ExplainAnalyzeContext.class);
        jsonCodecBinder(binder).bindJsonCodec(ExecutionFailureInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(StageInfo.class);
        jsonCodecBinder(binder).bindJsonCodec(OperatorStats.class);

        // index manager
        binder.bind(IndexManager.class).in(Scopes.SINGLETON);

        // handle resolver
        binder.install(new HandleJsonModule());

        // plugin manager
        configBinder(binder).bindConfig(PluginManagerConfig.class);
        binder.bind(PluginManager.class).in(Scopes.SINGLETON);

        // catalog manager
        binder.bind(StaticCatalogStore.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(StaticCatalogStoreConfig.class);

        // catalog
        binder.bind(ConnectorManager.class).in(Scopes.SINGLETON);
        binder.bind(CatalogManager.class).in(Scopes.SINGLETON);

        // property managers
        binder.bind(SessionPropertyManager.class).toProvider(PrestoSparkSessionPropertyManagerProvider.class).in(Scopes.SINGLETON);
        binder.bind(SystemSessionProperties.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkSessionProperties.class).in(Scopes.SINGLETON);
        binder.bind(SessionPropertyDefaults.class).in(Scopes.SINGLETON);
        binder.bind(SchemaPropertyManager.class).in(Scopes.SINGLETON);
        binder.bind(TablePropertyManager.class).in(Scopes.SINGLETON);
        binder.bind(ColumnPropertyManager.class).in(Scopes.SINGLETON);
        binder.bind(AnalyzePropertyManager.class).in(Scopes.SINGLETON);
        binder.bind(QuerySessionSupplier.class).in(Scopes.SINGLETON);

        // block encodings
        binder.bind(BlockEncodingManager.class).in(Scopes.SINGLETON);
        binder.bind(BlockEncodingSerde.class).to(BlockEncodingManager.class).in(Scopes.SINGLETON);
        newSetBinder(binder, BlockEncoding.class);
        jsonBinder(binder).addSerializerBinding(Block.class).to(BlockJsonSerde.Serializer.class);
        jsonBinder(binder).addDeserializerBinding(Block.class).to(BlockJsonSerde.Deserializer.class);

        // metadata
        binder.bind(FunctionManager.class).in(Scopes.SINGLETON);
        binder.bind(MetadataManager.class).in(Scopes.SINGLETON);
        binder.bind(Metadata.class).to(MetadataManager.class).in(Scopes.SINGLETON);
        binder.bind(StaticFunctionNamespaceStore.class).in(Scopes.SINGLETON);

        // type
        newSetBinder(binder, Type.class);
        binder.bind(TypeRegistry.class).in(Scopes.SINGLETON);
        binder.bind(TypeManager.class).to(TypeRegistry.class).in(Scopes.SINGLETON);
        jsonBinder(binder).addDeserializerBinding(Type.class).to(TypeDeserializer.class);

        // PageSorter
        binder.bind(PageSorter.class).to(PagesIndexPageSorter.class).in(Scopes.SINGLETON);

        // PageIndexer
        binder.bind(PagesIndex.Factory.class).to(PagesIndex.DefaultFactory.class);
        binder.bind(PageIndexerFactory.class).to(GroupByHashPageIndexerFactory.class).in(Scopes.SINGLETON);

        // compilers
        binder.bind(JoinFilterFunctionCompiler.class).in(Scopes.SINGLETON);
        newExporter(binder).export(JoinFilterFunctionCompiler.class).withGeneratedName();
        binder.bind(JoinCompiler.class).in(Scopes.SINGLETON);
        newExporter(binder).export(JoinCompiler.class).withGeneratedName();
        binder.bind(OrderingCompiler.class).in(Scopes.SINGLETON);
        newExporter(binder).export(OrderingCompiler.class).withGeneratedName();
        binder.bind(LookupJoinOperators.class).in(Scopes.SINGLETON);
        binder.bind(DomainTranslator.class).to(RowExpressionDomainTranslator.class).in(Scopes.SINGLETON);
        binder.bind(PredicateCompiler.class).to(RowExpressionPredicateCompiler.class).in(Scopes.SINGLETON);
        binder.bind(DeterminismEvaluator.class).to(RowExpressionDeterminismEvaluator.class).in(Scopes.SINGLETON);
        binder.bind(ExpressionCompiler.class).in(Scopes.SINGLETON);
        binder.bind(PageFunctionCompiler.class).in(Scopes.SINGLETON);

        // split manager
        binder.bind(SplitManager.class).in(Scopes.SINGLETON);

        // partitioning provider manager
        binder.bind(PartitioningProviderManager.class).in(Scopes.SINGLETON);

        // executors
        ExecutorService executor = newCachedThreadPool(daemonThreadsNamed("presto-spark-executor-%s"));
        binder.bind(Executor.class).toInstance(executor);
        binder.bind(ExecutorService.class).toInstance(executor);
        binder.bind(ScheduledExecutorService.class).toInstance(newScheduledThreadPool(0, daemonThreadsNamed("presto-spark-scheduled-executor-%s")));

        // data stream provider
        binder.bind(PageSourceManager.class).in(Scopes.SINGLETON);
        binder.bind(PageSourceProvider.class).to(PageSourceManager.class).in(Scopes.SINGLETON);

        // page sink provider
        binder.bind(PageSinkManager.class).in(Scopes.SINGLETON);
        binder.bind(PageSinkProvider.class).to(PageSinkManager.class).in(Scopes.SINGLETON);

        // query explainer
        binder.bind(QueryExplainer.class).in(Scopes.SINGLETON);

        // parser
        binder.bind(SqlParser.class).in(Scopes.SINGLETON);
        binder.bind(SqlParserOptions.class).toInstance(new SqlParserOptions());

        // planner
        binder.bind(PlanFragmenter.class).in(Scopes.SINGLETON);
        binder.bind(PlanOptimizers.class).in(Scopes.SINGLETON);
        binder.bind(ConnectorPlanOptimizerManager.class).in(Scopes.SINGLETON);
        binder.bind(LocalExecutionPlanner.class).in(Scopes.SINGLETON);
        binder.bind(IndexJoinLookupStats.class).in(Scopes.SINGLETON);
        binder.bind(QueryIdGenerator.class).in(Scopes.SINGLETON);
        binder.bind(QueryPreparer.class).in(Scopes.SINGLETON);
        jsonBinder(binder).addKeySerializerBinding(VariableReferenceExpression.class).to(VariableReferenceExpressionSerializer.class);
        jsonBinder(binder).addKeyDeserializerBinding(VariableReferenceExpression.class).to(VariableReferenceExpressionDeserializer.class);

        // statistics calculator / cost calculator
        binder.install(new StatsCalculatorModule());
        binder.bind(CostCalculator.class).to(CostCalculatorUsingExchanges.class).in(Scopes.SINGLETON);
        binder.bind(CostCalculator.class).annotatedWith(CostCalculator.EstimatedExchanges.class).to(CostCalculatorWithEstimatedExchanges.class).in(Scopes.SINGLETON);
        binder.bind(CostComparator.class).in(Scopes.SINGLETON);

        // JMX (Do not export to the real MXBean server, as the Presto context may be created multiple times per JVM)
        binder.bind(MBeanServer.class).toInstance(new TestingMBeanServer());
        binder.bind(MBeanExporter.class).in(Scopes.SINGLETON);

        // spill
        binder.bind(SpillerFactory.class).to(GenericSpillerFactory.class).in(Scopes.SINGLETON);
        binder.bind(SingleStreamSpillerFactory.class).to(FileSingleStreamSpillerFactory.class).in(Scopes.SINGLETON);
        binder.bind(PartitioningSpillerFactory.class).to(GenericPartitioningSpillerFactory.class).in(Scopes.SINGLETON);
        binder.bind(SpillerStats.class).in(Scopes.SINGLETON);

        // query monitoring
        binder.bind(QueryMonitor.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(QueryMonitorConfig.class);

        // Determine the NodeVersion
        ServerConfig serverConfig = buildConfigObject(ServerConfig.class);
        NodeVersion nodeVersion = new NodeVersion(serverConfig.getPrestoVersion());
        binder.bind(NodeVersion.class).toInstance(nodeVersion);

        // TODO: Support DDL statements
        MapBinder<Class<? extends Statement>, DataDefinitionTask<?>> taskBinder = newMapBinder(binder,
                new TypeLiteral<Class<? extends Statement>>() {}, new TypeLiteral<DataDefinitionTask<?>>() {});
        // taskBinder.addBinding(statement).to(task).in(Scopes.SINGLETON);

        // TODO: Support system connector
        // binder.install(new SystemConnectorModule());

        // TODO: support explain analyze for Spark
        binder.bind(new TypeLiteral<Optional<ExplainAnalyzeContext>>() {}).toInstance(Optional.of(new ExplainAnalyzeContext((queryId) -> {
            throw new UnsupportedOperationException("explain analyze is not supported");
        })));

        // TODO: support CBO, supply real nodes count
        binder.bind(TaskCountEstimator.class).toInstance(new TaskCountEstimator(() -> 1000));

        // TODO: Decouple and remove: required by ConnectorManager
        binder.bind(InternalNodeManager.class).toInstance(new PrestoSparkInternalNodeManager());

        // TODO: Decouple and remove: required by PluginManager
        binder.bind(InternalResourceGroupManager.class).in(Scopes.SINGLETON);
        binder.bind(ResourceGroupManager.class).to(InternalResourceGroupManager.class);
        binder.bind(new TypeLiteral<ResourceGroupManager<?>>() {}).to(new TypeLiteral<InternalResourceGroupManager<?>>() {});
        binder.bind(LegacyResourceGroupConfigurationManager.class).in(Scopes.SINGLETON);
        binder.bind(ClusterMemoryPoolManager.class).toInstance(((poolId, listener) -> {}));

        // TODO: Decouple and remove: required by SessionPropertyDefaults, PluginManager, InternalResourceGroupManager, ConnectorManager
        configBinder(binder).bindConfig(NodeConfig.class);
        binder.bind(NodeInfo.class).in(Scopes.SINGLETON);

        // TODO: Decouple and remove: required by LocalExecutionPlanner, PlanFragmenter
        binder.bind(NodePartitioningManager.class).to(PrestoSparkNodePartitioningManager.class).in(Scopes.SINGLETON);

        // TODO: Decouple and remove: required by PluginManager
        install(new ServerSecurityModule());

        // spark specific
        binder.bind(PrestoSparkQueryPlanner.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkPlanFragmenter.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkSplitEnumerator.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkRddFactory.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkTaskExecutorFactory.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkQueryExecutionFactory.class).in(Scopes.SINGLETON);
        binder.bind(PrestoSparkService.class).in(Scopes.SINGLETON);

        // extra credentials and authenticator for Presto-on-Spark
        newSetBinder(binder, PrestoSparkCredentialsProvider.class);
        newSetBinder(binder, PrestoSparkAuthenticatorProvider.class);
    }

    @Provides
    @Singleton
    public static TransactionManager createTransactionManager(
            TransactionManagerConfig config,
            CatalogManager catalogManager,
            ScheduledExecutorService scheduledExecutor,
            ExecutorService executor)
    {
        return InMemoryTransactionManager.create(config, scheduledExecutor, catalogManager, executor);
    }
}
