// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.meterware.simplestub.Memento;
import io.kubernetes.client.openapi.models.*;
import oracle.kubernetes.operator.*;
import oracle.kubernetes.operator.helpers.*;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo.ServerStartupInfo;
import oracle.kubernetes.operator.utils.WlsDomainConfigSupport;
import oracle.kubernetes.operator.wlsconfig.WlsClusterConfig;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.wlsconfig.WlsServerConfig;
import oracle.kubernetes.operator.work.*;
import oracle.kubernetes.utils.TestUtils;
import oracle.kubernetes.weblogic.domain.ClusterConfigurator;
import oracle.kubernetes.weblogic.domain.DomainConfigurator;
import oracle.kubernetes.weblogic.domain.DomainConfiguratorFactory;
import oracle.kubernetes.weblogic.domain.model.Domain;
import oracle.kubernetes.weblogic.domain.model.DomainSpec;
import org.jetbrains.annotations.NotNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.annotation.Nonnull;

import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class ManagedServerUpIteratorStepTest {

  private static final String DOMAIN = "domain";
  protected static final String DOMAIN_NAME = "domain1";
  private static final String NS = "namespace";
  private static final String UID = "uid1";
  protected static final String KUBERNETES_UID = "12345";
  private static final String ADMIN = "asName";
  private static final String CLUSTER = "cluster1";
  private static final boolean INCLUDE_SERVER_OUT_IN_POD_LOG = true;
  private static final String CREDENTIALS_SECRET_NAME = "webLogicCredentialsSecretName";
  private static final String LATEST_IMAGE = "image:latest";
  private static final String MS_PREFIX = "ms";
  private static final int MAX_SERVERS = 5;
  private static final String[] MANAGED_SERVER_NAMES =
          IntStream.rangeClosed(1, MAX_SERVERS).mapToObj(ManagedServerUpIteratorStepTest::getManagedServerName).toArray(String[]::new);

  @Nonnull
  private static String getManagedServerName(int n) {
    return MS_PREFIX + n;
  }

  private final Domain domain = createDomain();
  private final DomainConfigurator configurator = DomainConfiguratorFactory.forDomain(domain);
  private WlsDomainConfigSupport configSupport = new WlsDomainConfigSupport(DOMAIN);

  private Step nextStep = new TerminalStep();
  private KubernetesTestSupport k8sTestSupport = new KubernetesTestSupport();
  private List<Memento> mementos = new ArrayList<>();
  private DomainPresenceInfo domainPresenceInfo = createDomainPresenceInfoWithServers();
  private TestUtils.ConsoleHandlerMemento consoleHandlerMemento;
  private final WlsDomainConfig domainConfig = createDomainConfig();

  private static WlsDomainConfig createDomainConfig() {
    WlsClusterConfig clusterConfig = new WlsClusterConfig(CLUSTER);
    for (String serverName : MANAGED_SERVER_NAMES) {
      clusterConfig.addServerConfig(new WlsServerConfig(serverName, "domain1-" + serverName, 8001));
    }
    return new WlsDomainConfig("base_domain")
            .withAdminServer(ADMIN, "domain1-admin-server", 7001)
            .withCluster(clusterConfig);
  }

  private DomainPresenceInfo createDomainPresenceInfoWithServers(String... serverNames) {
    DomainPresenceInfo dpi = new DomainPresenceInfo(domain);
    addServer(dpi, ADMIN);
    Arrays.asList(serverNames).forEach(serverName -> addServer(dpi, serverName));
    return dpi;
  }

  private Domain createDomain() {
    return new Domain()
            .withApiVersion(KubernetesConstants.DOMAIN_VERSION)
            .withKind(KubernetesConstants.DOMAIN)
            .withMetadata(new V1ObjectMeta().namespace(NS).name(DOMAIN_NAME).uid(KUBERNETES_UID))
            .withSpec(createDomainSpec());
  }

  private DomainSpec createDomainSpec() {
    return new DomainSpec()
            .withDomainUid(UID)
            .withWebLogicCredentialsSecret(new V1SecretReference().name(CREDENTIALS_SECRET_NAME))
            .withIncludeServerOutInPodLog(INCLUDE_SERVER_OUT_IN_POD_LOG)
            .withImage(LATEST_IMAGE);
  }

  private static void addServer(DomainPresenceInfo domainPresenceInfo, String serverName) {
    if (serverName.equals(ADMIN)) {
      domainPresenceInfo.setServerPod(serverName, createReadyPod(serverName));
    } else {
      domainPresenceInfo.setServerPod(serverName, createPod(serverName));
    }
  }

  private static V1Pod createReadyPod(String serverName) {
    return new V1Pod().metadata(withNames(new V1ObjectMeta().namespace(NS), serverName))
            .spec(new V1PodSpec().nodeName("Node1"))
            .status(new V1PodStatus().phase("Running")
            .addConditionsItem(new V1PodCondition().type("Ready").status("True")));
  }

  private static V1Pod createPod(String serverName) {
    return new V1Pod().metadata(withNames(new V1ObjectMeta().namespace(NS), serverName));
  }

  private static V1ObjectMeta withNames(V1ObjectMeta objectMeta, String serverName) {
    return objectMeta
            .name(LegalNames.toPodName(UID, serverName))
            .putLabelsItem(LabelConstants.SERVERNAME_LABEL, serverName);
  }

  /**
   * Setup env for tests.
   * @throws NoSuchFieldException if TestStepFactory fails to install
   */
  @Before
  public void setUp() throws NoSuchFieldException {
    mementos.add(consoleHandlerMemento = TestUtils.silenceOperatorLogger());
    mementos.add(TuningParametersStub.install());
    mementos.add(k8sTestSupport.install());

    k8sTestSupport.defineResources(domain);
    k8sTestSupport
            .addToPacket(ProcessingConstants.DOMAIN_TOPOLOGY, domainConfig)
            .addDomainPresenceInfo(domainPresenceInfo);
  }

  /**
   * Cleanup env after tests.
   * @throws Exception if test support failed
   */
  @After
  public void tearDown() throws Exception {
    for (Memento memento : mementos) {
      memento.revert();
    }

    k8sTestSupport.throwOnCompletionFailure();
  }

  private void makePodReady(String serverName) {
    domainPresenceInfo.getServerPod(serverName).status(new V1PodStatus().phase("Running"));
    domainPresenceInfo.getServerPod(serverName).getStatus().addConditionsItem(new V1PodCondition().status("True").type("Ready"));
  }

  private void schedulePod(String serverName, String nodeName) {
    domainPresenceInfo.getServerPod(serverName).getSpec().setNodeName(nodeName);
  }

  @Test
  public void withConcurrencyOf1_bothClusteredServersStartedSequentially() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(1);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER,"ms1", "ms2"));

    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    schedulePod("ms1", "Node1");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), nullValue());
    makePodReady("ms1");
    k8sTestSupport.setTime(10, TimeUnit.SECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), notNullValue());
  }

  @Test
  public void withConcurrencyOf0_bothClusteredServersStartConcurrently() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(0);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER,"ms1", "ms2"));

    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), nullValue());
    schedulePod("ms1", "Node1");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), notNullValue());
  }

  @Test
  public void withConcurrencyOf2_bothClusteredServersStartedConcurrently() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(2);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2"));

    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), nullValue());
    schedulePod("ms1", "Node1");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), notNullValue());
  }

  @Test
  public void withConcurrencyOf2_4clusteredServersScheduledIn2Groups() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(2);
    addWlsCluster(CLUSTER, "ms1", "ms2", "ms3", "ms4");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2", "ms3", "ms4"));
    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), nullValue());
    schedulePod("ms1", "Node1");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), notNullValue());
    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), nullValue());
    schedulePod("ms2", "Node2");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), nullValue());
    makePodReady("ms1");
    k8sTestSupport.setTime(10, TimeUnit.SECONDS);
    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), notNullValue());
    assertThat("ms4" + " pod", domainPresenceInfo.getServerPod("ms4"), nullValue());
    makePodReady("ms2");
    schedulePod("ms3", "Node3");
    k8sTestSupport.setTime(10, TimeUnit.SECONDS);
    assertThat("ms4" + " pod", domainPresenceInfo.getServerPod("ms4"), notNullValue());
  }

  @Test
  public void withMultipleClusters_differentClusterStartDifferently() {
    final String CLUSTER2 = "cluster2";

    configureCluster(CLUSTER).withMaxConcurrentStartup(0);
    configureCluster(CLUSTER2).withMaxConcurrentStartup(1);

    addWlsCluster(CLUSTER, "ms1", "ms2");
    addWlsCluster(CLUSTER2, "ms3", "ms4");

    Collection<ServerStartupInfo> serverStartupInfos = createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2");
    serverStartupInfos.addAll(createServerStartupInfosForCluster(CLUSTER2, "ms3", "ms4"));
    invokeStepWithServerStartupInfos(serverStartupInfos);

    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), notNullValue());
    schedulePod("ms1", "Node1");
    schedulePod("ms3", "Node2");
    k8sTestSupport.setTime(100, TimeUnit.MILLISECONDS);
    assertThat("ms2" + " pod", domainPresenceInfo.getServerPod("ms2"), notNullValue());
    assertThat("ms4" + " pod", domainPresenceInfo.getServerPod("ms4"), nullValue());
//    makePodReady("ms3");
//    k8sTestSupport.setTime(10, TimeUnit.SECONDS);
//    assertThat("ms4" + " pod", domainPresenceInfo.getServerPod("ms4"), notNullValue());
  }

  @Test
  public void maxClusterConcurrentStartup_doesNotApplyToNonClusteredServers() {
    domain.getSpec().setMaxClusterConcurrentStartup(1);

    addWlsServers("ms3", "ms4");

    invokeStepWithServerStartupInfos(createServerStartupInfos("ms3", "ms4"));

    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), notNullValue());
    schedulePod("ms3", "Node2");
    k8sTestSupport.setTime(200, TimeUnit.MILLISECONDS);
    assertThat("ms3" + " pod", domainPresenceInfo.getServerPod("ms3"), notNullValue());
  }

  @NotNull
  private Collection<ServerStartupInfo> createServerStartupInfosForCluster(String clusterName, String... servers) {
    Collection<ServerStartupInfo> serverStartupInfos = new ArrayList<>();
    Arrays.asList(servers).stream().forEach(server ->
            serverStartupInfos.add(
                new ServerStartupInfo(configSupport.getWlsServer(clusterName, server),
                    clusterName,
                    domain.getServer(server, clusterName))
            )
    );
    return serverStartupInfos;
  }

  @NotNull
  private Collection<ServerStartupInfo> createServerStartupInfos(String... servers) {
    Collection<ServerStartupInfo> serverStartupInfos = new ArrayList<>();
    Arrays.asList(servers).stream().forEach(server ->
        serverStartupInfos.add(
            new ServerStartupInfo(configSupport.getWlsServer(server),
                null,
                domain.getServer(server, null))
        )
    );
    return serverStartupInfos;
  }

  private void invokeStepWithServerStartupInfos(Collection<ServerStartupInfo> startupInfos) {
    ManagedServerUpIteratorStep step = new ManagedServerUpIteratorStep(startupInfos, nextStep);
    k8sTestSupport.runSteps(step);
  }

  private ClusterConfigurator configureCluster(String clusterName) {
    return configurator.configureCluster(clusterName);
  }

  private void addWlsServers(String... serverNames) {
    Arrays.asList(serverNames).forEach(serverName -> addWlsServer(serverName));
  }

  private void addWlsServer(String serverName) {
    configSupport.addWlsServer(serverName, 8001);
  }

  private void addWlsCluster(String clusterName, String... serverNames) {
    configSupport.addWlsCluster(clusterName, serverNames);
  }
}