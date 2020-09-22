// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kubernetes.client.openapi.models.V1EnvVar;
import io.kubernetes.client.openapi.models.V1LocalObjectReference;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1SecretList;
import io.kubernetes.client.openapi.models.V1SecretReference;
import io.kubernetes.client.openapi.models.V1ServiceAccount;
import io.kubernetes.client.openapi.models.V1ServiceAccountList;
import oracle.weblogic.domain.AdminServer;
import oracle.weblogic.domain.Cluster;
import oracle.weblogic.domain.Configuration;
import oracle.weblogic.domain.Domain;
import oracle.weblogic.domain.DomainSpec;
import oracle.weblogic.domain.Model;
import oracle.weblogic.domain.ServerPod;
import oracle.weblogic.kubernetes.actions.impl.OperatorParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.HelmParams;
import oracle.weblogic.kubernetes.actions.impl.primitive.Kubernetes;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.ExecCommand;
import oracle.weblogic.kubernetes.utils.ExecResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static oracle.weblogic.kubernetes.TestConstants.ADMIN_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.DEFAULT_EXTERNAL_REST_IDENTITY_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.DOMAIN_API_VERSION;
import static oracle.weblogic.kubernetes.TestConstants.K8S_NODEPORT_HOST;
import static oracle.weblogic.kubernetes.TestConstants.MANAGED_SERVER_NAME_BASE;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.MII_BASIC_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_CHART_DIR;
import static oracle.weblogic.kubernetes.TestConstants.OPERATOR_RELEASE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.REPO_SECRET_NAME;
import static oracle.weblogic.kubernetes.TestConstants.WLS_DOMAIN_TYPE;
import static oracle.weblogic.kubernetes.actions.TestActions.createServiceAccount;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteDomainCustomResource;
import static oracle.weblogic.kubernetes.actions.TestActions.deleteServiceAccount;
import static oracle.weblogic.kubernetes.actions.TestActions.getOperatorImageName;
import static oracle.weblogic.kubernetes.actions.TestActions.getServiceNodePort;
import static oracle.weblogic.kubernetes.actions.TestActions.installOperator;
import static oracle.weblogic.kubernetes.actions.TestActions.listSecrets;
import static oracle.weblogic.kubernetes.actions.TestActions.scaleClusterWithRestApi;
import static oracle.weblogic.kubernetes.actions.TestActions.uninstallOperator;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.checkHelmReleaseStatus;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.operatorIsReady;
import static oracle.weblogic.kubernetes.assertions.TestAssertions.operatorRestServiceRunning;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkPodReadyAndServiceExists;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.checkServiceDoesNotExist;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDockerRegistrySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createDomainAndVerify;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createExternalRestIdentitySecret;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.createSecretWithUsernamePassword;
import static oracle.weblogic.kubernetes.utils.CommonTestUtils.dockerLoginAndPushImageToRegistry;
import static oracle.weblogic.kubernetes.utils.ExecCommand.exec;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;



/**
 * Simple JUnit test file used for testing operator usability.
 * Use Helm chart to install operator(s)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test operator usability using Helm chart installation")
@IntegrationTest
class ItManageNs {

  private static String opNamespace = null;
  private static String op2Namespace = null;
  private static String domain1Namespace = null;
  private static String domain2Namespace = null;
  private static String domain3Namespace = null;

  // domain constants
  private final String domain1Uid = "managensdomain1";
  private final String domain2Uid = "managensdomain2";
  private final String domain3Uid = "managensdomain3";
  private final String clusterName = "cluster-1";
  private final int managedServerPort = 8001;
  private final int replicaCount = 2;
  private final String adminServerPrefix = "-" + ADMIN_SERVER_NAME_BASE;
  private final String managedServerPrefix = "-" + MANAGED_SERVER_NAME_BASE;
  private boolean isDomain1Running = false;
  private boolean isDomain2Running = false;
  private int replicaCountDomain1 = 2;
  private int replicaCountDomain2 = 2;
  private HelmParams opHelmParams1;
  private HelmParams opHelmParams2;

  // ingress host list
  private List<String> ingressHostList;
  private static LoggingFacade logger = null;
  private static org.awaitility.core.ConditionFactory withStandardRetryPolicy =
      with().pollDelay(2, SECONDS)
          .and().with().pollInterval(10, SECONDS)
          .atMost(5, MINUTES).await();

  /**
   * Get namespaces for operator, domain.
   *
   * @param namespaces list of namespaces created by the IntegrationTestWatcher by the
   *                   JUnit engine parameter resolution mechanism
   */
  @BeforeAll
  public static void initAll(@Namespaces(5) List<String> namespaces) {
    logger = getLogger();
    // get a unique operator namespace
    logger.info("Getting a unique namespace for operator");
    assertNotNull(namespaces.get(0), "Namespace list is null");
    opNamespace = namespaces.get(0);

    // get a unique domain namespace
    logger.info("Getting a unique namespace for WebLogic domain");
    assertNotNull(namespaces.get(1), "Namespace list is null");
    domain1Namespace = namespaces.get(1);

    // get a unique domain namespace
    logger.info("Getting a unique namespace for WebLogic domain 2");
    assertNotNull(namespaces.get(2), "Namespace list is null");
    domain2Namespace = namespaces.get(2);

    // get a unique domain namespace
    logger.info("Getting a unique namespace for WebLogic domain 3");
    assertNotNull(namespaces.get(3), "Namespace list is null");
    domain3Namespace = namespaces.get(3);

    // get a unique operator 2 namespace
    logger.info("Getting a unique namespace for operator 2");
    assertNotNull(namespaces.get(4), "Namespace list is null");
    op2Namespace = namespaces.get(4);
  }

  @AfterAll
  public void tearDownAll() {

    // Delete domain custom resource
    logger.info("Delete domain1 custom resource in namespace {0}", domain1Namespace);
    deleteDomainCustomResource(domain1Uid, domain1Namespace);
    logger.info("Deleted Domain Custom Resource " + domain1Uid + " from " + domain1Namespace);

    logger.info("Delete domain2 custom resource in namespace {0}", domain2Namespace);
    deleteDomainCustomResource(domain2Uid, domain2Namespace);
    logger.info("Deleted Domain Custom Resource " + domain2Uid + " from " + domain2Namespace);

    logger.info("Delete domain3 custom resource in namespace {0}", domain2Namespace);

    deleteDomainCustomResource(domain1Uid + "xoxoxo", "xoxoxo-" + domain1Namespace);
    logger.info("Deleted Domain Custom Resource " + domain1Uid + "xoxoxo from xoxoxo-" + domain1Namespace);
    deleteDomainCustomResource(domain2Uid + "xoxoxo", "xoxoxo-" + domain2Namespace);
    logger.info("Deleted Domain Custom Resource " + domain2Uid + "xoxoxo from xoxoxo-" + domain2Namespace);


    //delete operator
    uninstallOperator(opHelmParams1);
    uninstallOperator(opHelmParams2);

  }

  /**
   * Install the Operator successfully and verify it is deployed successfully.
   * Deploy a custom domain resource and verify all server pods in the domain were created and ready.
   * Uninstall operator helm chart.
   * Verify the states of all server pods in the domain were not changed.
   * Verify the access to managed server configuration via rest api in the domain after the operator was deleted.
   */
  @Test
  @DisplayName("install operator helm chart and domain, "
      + " using label namespace management")
  public void testNsManageByLabel() {
    //add label to domain namespace
    V1Namespace namespaceObject1 = assertDoesNotThrow(() -> Kubernetes.getNamespaceAsObject(domain1Namespace));
    assertNotNull(namespaceObject1, "Can't find namespace with name " + domain1Namespace);
    //add label to namespace
    Map<String,String> labels1 = new java.util.HashMap<>();
    labels1.put("weblogic-operator", OPERATOR_RELEASE_NAME);
    V1Namespace namespaceObjectWithLabel1 = namespaceObject1;
    namespaceObjectWithLabel1.getMetadata().setLabels(labels1);
    assertDoesNotThrow(() -> Kubernetes.replaceNamespace(namespaceObjectWithLabel1));

    V1Namespace namespaceObject2 = assertDoesNotThrow(() -> Kubernetes.getNamespaceAsObject(domain2Namespace));
    assertNotNull(namespaceObject2, "Can't find namespace with name " + domain2Namespace);
    //add label to namespace
    Map<String,String> labels2 = new java.util.HashMap<>();
    labels2.put("weblogic-operator", OPERATOR_RELEASE_NAME + "2");
    V1Namespace namespaceObjectWithLabel2 = namespaceObject2;
    namespaceObjectWithLabel2.getMetadata().setLabels(labels2);
    assertDoesNotThrow(() -> Kubernetes.replaceNamespace(namespaceObjectWithLabel2));

    // install and verify operator

    opHelmParams1 = installOperatorHelmChart(OPERATOR_RELEASE_NAME,
        opNamespace, "LabelSelector", "weblogic-operator=" + OPERATOR_RELEASE_NAME);
    if (!isDomain1Running) {
      logger.info("Installing and verifying domain1");
      assertTrue(createVerifyDomain(domain1Namespace, domain1Uid),
          "can't start or verify domain in namespace " + domain1Namespace);
      isDomain1Running = true;
    }
    int externalRestHttpsPort = getServiceNodePort(opNamespace, "external-weblogic-operator-svc");
    assertTrue(scaleClusterWithRestApi(domain1Uid, clusterName,3,
        externalRestHttpsPort,opNamespace, OPERATOR_RELEASE_NAME + "-sa"),
        "Domain1 " + domain1Namespace + " scaling operation failed");

    assertFalse(createVerifyDomain(domain2Namespace, domain2Uid),
        "operator can start or verify domain in namespace " + domain2Namespace);
  }

  @Test
  @DisplayName("install operator helm chart and domain, "
      + " using expression namespace management")
  public void testNsManageByExp() {
    //create domain namespace
    String domain1NS = "xoxoxo-" +  domain1Namespace;
    String domain2NS = "xoxoxo-" +  domain2Namespace;
    assertDoesNotThrow(() -> Kubernetes.createNamespace(domain1NS));
    assertDoesNotThrow(() -> Kubernetes.createNamespace(domain2NS));

    // install and verify operator

    opHelmParams2 = installOperatorHelmChart(OPERATOR_RELEASE_NAME,
        op2Namespace, "RegExp", "^xoxoxo");

    logger.info("Installing and verifying domain1");
    assertTrue(createVerifyDomain(domain1NS, domain1Uid + "xoxoxo"),
        "can't start or verify domain in namespace " + domain1NS);


    int externalRestHttpsPort = getServiceNodePort(op2Namespace, "external-weblogic-operator-svc");
    assertTrue(scaleClusterWithRestApi(domain1Uid + "xoxoxo", clusterName,3,
        externalRestHttpsPort,op2Namespace, OPERATOR_RELEASE_NAME + "-sa"),
        "Domain1 " + domain1NS + " scaling operation failed");

    assertTrue(createVerifyDomain(domain2Namespace + "xoxoxo", domain2Uid + "xoxoxo"),
        "operator can start or verify domain in namespace " + domain2NS);
    externalRestHttpsPort = getServiceNodePort(op2Namespace, "external-weblogic-operator-svc");
    assertTrue(scaleClusterWithRestApi(domain2Uid + "xoxoxo", clusterName,3,
        externalRestHttpsPort,op2Namespace, OPERATOR_RELEASE_NAME + "-sa"),
        "Domain2 " + domain2NS + " scaling operation failed");
  }

  private boolean createVerifyDomain(String domainNamespace, String domainUid) {

    // create and verify the domain
    logger.info("Creating and verifying model in image domain");
    try {
      createAndVerifyMiiDomain(domainNamespace, domainUid);
      return true;
    } catch (Exception ex) {
      logger.info("Failed to createVerifyDomain " + ex.getMessage());
      return false;
    }
  }

  /**
   * Create a model in image domain and verify the domain pods are ready.
   */
  private void createAndVerifyMiiDomain(String domainNamespace, String domainUid) {

    // get the pre-built image created by IntegrationTestWatcher
    String miiImage = MII_BASIC_IMAGE_NAME + ":" + MII_BASIC_IMAGE_TAG;

    // docker login and push image to docker registry if necessary
    dockerLoginAndPushImageToRegistry(miiImage);

    // create docker registry secret to pull the image from registry
    logger.info("Creating docker registry secret in namespace {0}", domainNamespace);
    createDockerRegistrySecret(domainNamespace);

    // create secret for admin credentials
    logger.info("Creating secret for admin credentials");
    String adminSecretName = "weblogic-credentials";
    createSecretWithUsernamePassword(adminSecretName, domainNamespace, "weblogic", "welcome1");

    // create encryption secret
    logger.info("Creating encryption secret");
    String encryptionSecretName = "encryptionsecret";
    createSecretWithUsernamePassword(encryptionSecretName, domainNamespace, "weblogicenc", "weblogicenc");

    // construct a list of oracle.weblogic.domain.Cluster objects to be used in the domain custom resource
    List<Cluster> clusters = new ArrayList<>();
    clusters.add(new Cluster()
        .clusterName(clusterName)
        .replicas(replicaCount)
        .serverStartState("RUNNING"));

    // create the domain CR
    Domain domain = new Domain()
        .apiVersion(DOMAIN_API_VERSION)
        .kind("Domain")
        .metadata(new V1ObjectMeta()
            .name(domainUid)
            .namespace(domainNamespace))
        .spec(new DomainSpec()
            .domainUid(domainUid)
            .domainHomeSourceType("FromModel")
            .image(miiImage)
            .addImagePullSecretsItem(new V1LocalObjectReference()
                .name(REPO_SECRET_NAME))
            .webLogicCredentialsSecret(new V1SecretReference()
                .name(adminSecretName)
                .namespace(domainNamespace))
            .includeServerOutInPodLog(true)
            .serverStartPolicy("IF_NEEDED")
            .serverPod(new ServerPod()
                .addEnvItem(new V1EnvVar()
                    .name("JAVA_OPTIONS")
                    .value("-Dweblogic.StdoutDebugEnabled=false"))
                .addEnvItem(new V1EnvVar()
                    .name("USER_MEM_ARGS")
                    .value("-Djava.security.egd=file:/dev/./urandom ")))
            .adminServer(new AdminServer()
                .serverStartState("RUNNING")
                .adminService(new oracle.weblogic.domain.AdminService()
                    .addChannelsItem(new oracle.weblogic.domain.Channel()
                        .channelName("default")
                        .nodePort(0))))
            .clusters(clusters)
            .configuration(new Configuration()
                .model(new Model()
                    .domainType(WLS_DOMAIN_TYPE)
                    .runtimeEncryptionSecret(encryptionSecretName))));

    // create model in image domain
    logger.info("Creating model in image domain {0} in namespace {1} using docker image {2}",
        domainUid, domainNamespace, miiImage);
    createDomainAndVerify(domain, domainNamespace);
    String adminServerPodName = domainUid + adminServerPrefix;
    // check that admin server pod exists in the domain namespace
    logger.info("Checking that admin server pod {0} exists in namespace {1}",
        adminServerPodName, domainNamespace);
    checkPodReadyAndServiceExists(adminServerPodName, domainUid, domainNamespace);

    // check for managed server pods existence in the domain namespace
    for (int i = 1; i <= replicaCount; i++) {
      String managedServerPodName = domainUid + managedServerPrefix + i;

      // check that the managed server pod exists
      logger.info("Checking that managed server pod {0} exists in namespace {1}",
          managedServerPodName, domainNamespace);
      checkPodReadyAndServiceExists(managedServerPodName, domainUid, domainNamespace);
    }
  }

  /**
   * Install WebLogic operator and wait up to five minutes until the operator pod is ready.
   * Method is to test positive and negative testcases for operator helm install
   *
   * @param operNamespace the operator namespace in which the operator will be installed
   * @param opReleaseName the operator release name
   * @param domainNamespace the list of the domain namespaces which will be managed by the operator
   * @return the operator Helm installation parameters
   */
  private static HelmParams installOperatorHelmChart(String opReleaseName, String operNamespace,
                                                     String domainNsSelectionStrategy,
                                                     String domainNsSelector,
                                                     String... domainNamespace) {
    LoggingFacade logger = getLogger();

    HelmParams opHelmParams = new HelmParams().releaseName(opReleaseName)
        .namespace(operNamespace)
        .chartDir(OPERATOR_CHART_DIR);

    // Create a service account for the unique operNamespace
    logger.info("Creating service account");
    assertDoesNotThrow(() -> createServiceAccount(new V1ServiceAccount()
        .metadata(new V1ObjectMeta()
            .namespace(operNamespace)
            .name(opReleaseName + "-sa"))));
    logger.info("Created service account: {0}", opReleaseName + "-sa");


    // get operator image name
    String operatorImage = getOperatorImageName();
    assertFalse(operatorImage.isEmpty(), "operator image name can not be empty");
    logger.info("operator image name {0}", operatorImage);


    V1SecretList listSecrets = listSecrets(operNamespace);
    if (null != listSecrets) {
      for (V1Secret item : listSecrets.getItems()) {
        if (item.getMetadata().getName().equals(REPO_SECRET_NAME)) {
          break;
        }
      }
      // Create Docker registry secret in the operator namespace to pull the image from repository
      logger.info("Creating Docker registry secret in namespace {0}", operNamespace);
      createDockerRegistrySecret(operNamespace);
    }
    // map with secret
    Map<String, Object> secretNameMap = new HashMap<>();
    secretNameMap.put("name", REPO_SECRET_NAME);

    // operator chart values to override
    OperatorParams opParams = new OperatorParams()
        .helmParams(opHelmParams)
        .imagePullSecrets(secretNameMap)
        .domainNamespaces(java.util.Arrays.asList(domainNamespace))
        .serviceAccount(opReleaseName + "-sa");
    if (domainNsSelectionStrategy != null) {
      opParams.domainNamespaceSelectionStrategy(domainNsSelectionStrategy);
      if (domainNsSelectionStrategy.equalsIgnoreCase("LabelSelector")) {
        opParams.domainNamespaceLabelSelector(domainNsSelector);
      } else if (domainNsSelectionStrategy.equalsIgnoreCase("RegExp")) {
        opParams.domainNamespaceRegExp(domainNsSelector);
      }
    }

    // use default image in chart when repoUrl is set, otherwise use latest/current branch operator image
    if (opHelmParams.getRepoUrl() == null) {
      opParams.image(operatorImage);
    }

    // create externalRestIdentitySecret
    assertTrue(createExternalRestIdentitySecret(operNamespace,
        DEFAULT_EXTERNAL_REST_IDENTITY_SECRET_NAME + operNamespace),
        "failed to create external REST identity secret");
    opParams
        .externalRestEnabled(true)
        .externalRestHttpsPort(0)
        .externalRestIdentitySecret(DEFAULT_EXTERNAL_REST_IDENTITY_SECRET_NAME + operNamespace);


    // install operator
    logger.info("Installing operator in namespace {0}", operNamespace);

    assertTrue(installOperator(opParams),
        String.format("Failed to install operator in namespace %s ", operNamespace));
    logger.info("Operator installed in namespace {0}", operNamespace);

    // list Helm releases matching operator release name in operator namespace
    logger.info("Checking operator release {0} status in namespace {1}",
        opReleaseName, operNamespace);

    assertTrue(checkHelmReleaseStatus(opReleaseName, operNamespace, "deployed"),
        String.format("Operator release %s is not in %s status in namespace %s",
            opReleaseName, "deployed", operNamespace));
    logger.info("Operator release {0} status is {1} in namespace {2}",
        opReleaseName, "deployed", operNamespace);

    // wait for the operator to be ready
    logger.info("Wait for the operator pod is ready in namespace {0}", operNamespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for operator to be running in namespace {0} "
                    + "(elapsed time {1}ms, remaining time {2}ms)",
                operNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> operatorIsReady(operNamespace),
            "operatorIsReady failed with ApiException"));


    logger.info("Wait for the operator external service in namespace {0}", operNamespace);
    withStandardRetryPolicy
        .conditionEvaluationListener(
            condition -> logger.info("Waiting for operator external service in namespace {0} "
                    + "(elapsed time {1}ms, remaining time {2}ms)",
                operNamespace,
                condition.getElapsedTimeInMS(),
                condition.getRemainingTimeInMS()))
        .until(assertDoesNotThrow(() -> operatorRestServiceRunning(operNamespace),
            "operator external service is not running"));

    return opHelmParams;
  }

  /**
   * Executes the given command and return error message if command failed.
   * @param command the command to execute
   * @return Error message string for failed command or null if no failure
   */
  private static String createNS(String command) {
    getLogger().info("Running command - \n" + command);
    ExecResult result = null;
    try {
      result = ExecCommand.exec(command, true);
      getLogger().info("The command returned exit value: "
          + result.exitValue() + " command output: "
          + result.stderr() + "\n" + result.stdout());
      if (result.exitValue() != 0) {
        getLogger().info("Command failed with errors " + result.stderr() + "\n" + result.stdout());
        return result.stderr();
      }
    } catch (Exception e) {
      getLogger().info("Got exception, command failed with errors " + e.getMessage());
      return result.stderr();
    }
    return null;
  }

  private void cleanUpSA(String namespace) {
    V1ServiceAccountList sas = Kubernetes.listServiceAccounts(namespace);
    if (sas != null) {
      for (V1ServiceAccount sa : sas.getItems()) {
        String saName = sa.getMetadata().getName();
        deleteServiceAccount(saName, namespace);
        checkServiceDoesNotExist(saName, namespace);
      }
    }
  }

  /*
   * Verify the server MBEAN configuration through rest API.
   * @param managedServer name of the managed server
   * @returns true if MBEAN is found otherwise false
   **/
  private boolean checkManagedServerConfiguration(String domainNamespace, String domainUid) {
    ExecResult result = null;
    String adminServerPodName = domainUid + adminServerPrefix;
    String managedServer = "managed-server1";
    int adminServiceNodePort = getServiceNodePort(domainNamespace, adminServerPodName + "-external", "default");
    StringBuffer checkCluster = new StringBuffer("status=$(curl --user weblogic:welcome1 ");
    checkCluster.append("http://" + K8S_NODEPORT_HOST + ":" + adminServiceNodePort)
        .append("/management/tenant-monitoring/servers/")
        .append(managedServer)
        .append(" --silent --show-error ")
        .append(" -o /dev/null")
        .append(" -w %{http_code});")
        .append("echo ${status}");
    logger.info("checkManagedServerConfiguration: curl command {0}", new String(checkCluster));
    try {
      result = exec(new String(checkCluster), true);
    } catch (Exception ex) {
      logger.info("Exception in checkManagedServerConfiguration() {0}", ex);
      return false;
    }
    logger.info("checkManagedServerConfiguration: curl command returned {0}", result.toString());
    if (result.stdout().equals("200")) {
      return true;
    } else {
      return false;
    }
  }
}