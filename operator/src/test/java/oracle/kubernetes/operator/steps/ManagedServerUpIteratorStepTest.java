// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import com.meterware.simplestub.Memento;
import com.meterware.simplestub.StaticStubSupport;
import io.kubernetes.client.openapi.models.*;
import oracle.kubernetes.operator.KubernetesConstants;
import oracle.kubernetes.operator.LabelConstants;
import oracle.kubernetes.operator.PodAwaiterStepFactory;
import oracle.kubernetes.operator.ProcessingConstants;
import oracle.kubernetes.operator.helpers.*;
import oracle.kubernetes.operator.helpers.DomainPresenceInfo.ServerStartupInfo;
import oracle.kubernetes.operator.steps.ManagedServerUpIteratorStep.StartManagedServersStep;
import oracle.kubernetes.operator.utils.WlsDomainConfigSupport;
import oracle.kubernetes.operator.wlsconfig.WlsDomainConfig;
import oracle.kubernetes.operator.work.FiberTestSupport;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.operator.work.Step.StepAndPacket;
import oracle.kubernetes.operator.work.TerminalStep;
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

import static oracle.kubernetes.operator.ProcessingConstants.SERVER_SCAN;
import static oracle.kubernetes.operator.steps.ManagedServerUpIteratorStepTest.TestStepFactory.getServers;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.junit.MatcherAssert.assertThat;

public class ManagedServerUpIteratorStepTest {

  static final String ADMIN_SERVER = "ADMIN_SERVER";
  static final Integer ADMIN_PORT = 7001;
  private static final String DOMAIN = "domain";
  protected static final String DOMAIN_NAME = "domain1";
  private static final String NS = "namespace";
  private static final String UID = "uid1";
  protected static final String KUBERNETES_UID = "12345";
  private static final String ADMIN = "asName";
  private static final String CLUSTER = "cluster1";
  private static final String NON_CLUSTERED = "NonClustered";
  private static final boolean INCLUDE_SERVER_OUT_IN_POD_LOG = true;
  private static final String CREDENTIALS_SECRET_NAME = "webLogicCredentialsSecretName";
  private static final String STORAGE_VOLUME_NAME = "weblogic-domain-storage-volume";
  private static final String LATEST_IMAGE = "image:latest";
  private static final String VERSIONED_IMAGE = "image:1.2.3";
  private final Domain domain = createDomain();
  private WlsDomainConfig domainTopology;
  private final DomainConfigurator configurator = DomainConfiguratorFactory.forDomain(domain);
  private WlsDomainConfigSupport configSupport = new WlsDomainConfigSupport(DOMAIN);

  private Step nextStep = new TerminalStep();
  private FiberTestSupport testSupport = new FiberTestSupport();
  private KubernetesTestSupport k8sTestSupport = new KubernetesTestSupport();
  private List<Memento> mementos = new ArrayList<>();
  //private DomainPresenceInfo domainPresenceInfoServers = createDomainPresenceInfoWithServers();
  private DomainPresenceInfo domainPresenceInfo = createDomainPresenceInfoWithServers();
  //private final DomainPresenceInfo domainPresenceInfo = createDomainPresenceInfo(domain);
  private TestUtils.ConsoleHandlerMemento consoleHandlerMemento;

  private DomainPresenceInfo createDomainPresenceInfo(Domain domain) {
    return new DomainPresenceInfo(domain);
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

  private void defineDomainImage(String image) {
    configureDomain().withDefaultImage(image);
  }

  private DomainConfigurator configureDomain() {
    return DomainConfiguratorFactory.forDomain(domainPresenceInfo.getDomain());
  }

  private V1ObjectMeta createMetaData() {
    return new V1ObjectMeta().namespace(NS);
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
    mementos.add(TestStepFactory.install());
    mementos.add(k8sTestSupport.install());
    //testSupport.addDomainPresenceInfo(createDomainPresenceInfoWithServers("ms1","ms2"));
    WlsDomainConfigSupport configSupport = new WlsDomainConfigSupport(DOMAIN_NAME);
    configSupport.addWlsServer(ADMIN_SERVER, ADMIN_PORT);
    if (!ADMIN_SERVER.equals("ms1")) {
      configSupport.addWlsServer("ms1", 9001);
    }
    configSupport.setAdminServerName(ADMIN_SERVER);

    //testSupport.defineResources(domain);
    domainTopology = configSupport.createDomainConfig();
    testSupport
            .addToPacket(ProcessingConstants.DOMAIN_TOPOLOGY, domainTopology)
            .addToPacket(SERVER_SCAN, domainTopology.getServerConfig("ms1"))
            .addDomainPresenceInfo(domainPresenceInfo);
    testSupport.addComponent(
            ProcessingConstants.PODWATCHER_COMPONENT_NAME,
            PodAwaiterStepFactory.class,
            new PodHelperTestBase.PassthroughPodAwaiterStepFactory());
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

    testSupport.throwOnCompletionFailure();
  }

  @Test
  public void withConcurrencyOf1_bothClusteredServersScheduledSequentially() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(1);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER,"ms1", "ms2"));

    assertThat("ms1" + " pod", domainPresenceInfo.getServerPod("ms1"), notNullValue());
    //assertThat(getServers(), hasItem("ms2"));
    //assertThat(getServers().size(), equalTo(1));
  }

  @Test
  public void withConcurrencyOf0_bothClusteredServersScheduledConcurrently() {
    TestStepFactory.initializeStepMap();
    //testSupport.addDomainPresenceInfo(createDomainPresenceInfoWithServers());
    configureCluster(CLUSTER).withMaxConcurrentStartup(0);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER,"ms1", "ms2"));

    assertThat(getServers().size(), equalTo(0));
  }

  @Test
  public void withConcurrencyOf2_bothClusteredServersScheduledConcurrently() {
    TestStepFactory.initializeStepMap();
    configureCluster(CLUSTER).withMaxConcurrentStartup(2);
    addWlsCluster(CLUSTER, "ms1", "ms2");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2"));

    assertThat(getServers().size(), equalTo(0));
  }

  @Test
  public void withConcurrencyOf2_4clusteredServersScheduledIn2Groups() {
    configureCluster(CLUSTER).withMaxConcurrentStartup(2);
    addWlsCluster(CLUSTER, "ms1", "ms2", "ms3", "ms4");

    invokeStepWithServerStartupInfos(createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2", "ms3", "ms4"));

    //assertThat(getServers(), hasItem(Arrays.asList("ms1", "ms2", "ms3", "ms4")));
    //testSupport.setTime(RECHECK_SECONDS, TimeUnit.SECONDS);
    assertThat(getServers(), allOf(hasItem("ms3"), hasItem("ms4")));
    assertThat(getServers().size(), equalTo(2));
  }

  @Test
  public void withMultipleClusters_differentClusterStartDifferently() {
    final String CLUSTER2 = "cluster2";
    TestStepFactory.initializeStepMap();
    testSupport.addDomainPresenceInfo(createDomainPresenceInfoWithServers("ms1","ms2"));

    configureCluster(CLUSTER).withMaxConcurrentStartup(1);
    configureCluster(CLUSTER2).withMaxConcurrentStartup(0);

    addWlsCluster(CLUSTER, "ms1", "ms2");
    addWlsCluster(CLUSTER2, "ms3", "ms4");

    Collection<ServerStartupInfo> serverStartupInfos = createServerStartupInfosForCluster(CLUSTER, "ms1", "ms2");
    serverStartupInfos.addAll(createServerStartupInfosForCluster(CLUSTER2, "ms3", "ms4"));
    invokeStepWithServerStartupInfos(serverStartupInfos);

    assertThat(getServers(), hasItem("ms2"));
    assertThat(getServers(CLUSTER2).size(), equalTo(0));
  }

  @Test
  public void maxClusterConcurrentStartup_doesNotApplyToNonClusteredServers() {
    TestStepFactory.initializeStepMap();
    domain.getSpec().setMaxClusterConcurrentStartup(1);

    addWlsServers("ms3", "ms4");

    invokeStepWithServerStartupInfos(createServerStartupInfos("ms3", "ms4"));

    assertThat(getServers(NON_CLUSTERED).size(), equalTo(0));
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
    // configSupport.setAdminServerName(ADMIN);

    testSupport.addToPacket(
        ProcessingConstants.DOMAIN_TOPOLOGY, configSupport.createDomainConfig());
    testSupport.runSteps(step);
  }

  private ClusterConfigurator configureCluster(String clusterName) {
    return configurator.configureCluster(clusterName);
  }

  private void addWlsServers(String... serverNames) {
    Arrays.asList(serverNames).forEach(serverName -> addWlsServer(serverName));
  }

  private void addWlsServer(String serverName) {
    configSupport.addWlsServer(serverName);
  }

  private void addWlsCluster(String clusterName, String... serverNames) {
    configSupport.addWlsCluster(clusterName, serverNames);
  }

  static class TestStepFactory implements ManagedServerUpIteratorStep.NextStepFactory {

    private static TestStepFactory factory = new TestStepFactory();
    protected KubernetesTestSupport testSupport = new KubernetesTestSupport();
    final TerminalStep terminalStep = new TerminalStep();
    private static int staticServerCount = 0;
    private static Map<String,Step> nextMap = new ConcurrentHashMap<>();

    static void initializeStepMap() {
      initializeStepMap(0);
    }

    static void initializeStepMap(int serverCount) {
      staticServerCount = serverCount;
      nextMap.clear();
    }

    private static Memento install() throws NoSuchFieldException {
      return StaticStubSupport.install(ManagedServerUpIteratorStep.class, "NEXT_STEP_FACTORY",
              TestStepFactory.factory);
    }

    static Collection<Object> getServers() {
      return getServers(CLUSTER);
    }

    static Collection<Object> getServers(String clusterName) {
      Step next = nextMap.get(clusterName);
      if (next instanceof StartManagedServersStep) {
        return ((StartManagedServersStep)next).getStartDetails()
                .stream()
                .map(serverToStart -> getServerFromStepAndPacket(serverToStart)).collect(Collectors.toList());
      }
      return Collections.emptyList();
    }

    static Object getServerFromStepAndPacket(StepAndPacket startDetail) {
      //if (startDetail.step instanceof StartClusteredServersStep) {
      if (startDetail.step instanceof StartManagedServersStep) {
        Collection<StepAndPacket> serversToStart = ((StartManagedServersStep)startDetail.step).getServersToStart();
        return serversToStart.stream().map(serverToStart -> getServerFromStepAndPacket(serverToStart))
                .collect(Collectors.toList());
      }
      return startDetail.packet.get(ProcessingConstants.SERVER_NAME);
    }

    //@Override
    FiberTestSupport.StepFactory getStepFactory() {
      return PodHelper::createManagedPodStep;
    }

    @Override
    public Step startClusteredServersStep(Step step, Packet packet, Collection<StepAndPacket> serverDetails) {
      if (step instanceof StartManagedServersStep) {
        String clusterName = Optional.ofNullable(
                ((StartManagedServersStep)step).getClusterName()).orElse(NON_CLUSTERED);
        TestStepFactory.nextMap.put(clusterName, step);
      }
      WlsDomainConfigSupport configSupport = new WlsDomainConfigSupport(DOMAIN_NAME);
      DomainPresenceInfo info = packet.getSpi(DomainPresenceInfo.class);
      testSupport.addDomainPresenceInfo(info);
      WlsDomainConfig domainTopology = configSupport.createDomainConfig();
      testSupport
              .addToPacket(ProcessingConstants.DOMAIN_TOPOLOGY, domainTopology)
              .addToPacket(SERVER_SCAN, domainTopology.getServerConfig("ms1"));
      testSupport.runSteps(getStepFactory(), terminalStep);

      staticServerCount++;
      PodHelper.schedulePods(info, "ms" + staticServerCount);
      //PodHelper.makePodsReady(info, "ms" + serverCount);
      return step;
    }
  }

}
