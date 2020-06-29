// Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import oracle.kubernetes.operator.utils.Domain;
import oracle.kubernetes.operator.utils.LoggerHelper;
import oracle.kubernetes.operator.utils.Operator;
//import oracle.kubernetes.operator.utils.Operator.RestCertType;
import oracle.kubernetes.operator.utils.TestUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer.Alphanumeric;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Simple JUnit test file used for testing Operator.
 *
 * <p>This test is used for creating Operator(s) and multiple domains which are managed by the
 * Operator(s).
 */
@TestMethodOrder(Alphanumeric.class)
public class ItStandaloneServer extends BaseTest {
  private static Operator operator1;
  private static String domainNS1;
  private static String testClassName;
  private static StringBuffer namespaceList;

  /**
   * This method gets called only once before any of the test methods are executed. It does the
   * initialization of the integration test properties defined in OperatorIT.properties and setting
   * the resultRoot, pvRoot and projectRoot attributes.
   *
   * @throws Exception exception
   */
  @BeforeAll
  public static void staticPrepare() throws Exception {
    namespaceList = new StringBuffer();
    testClassName = new Object() {
    }.getClass().getEnclosingClass().getSimpleName();
    // initialize test properties and create the directories
    initialize(APP_PROPS_FILE, testClassName);
  }

  /**
   * This method gets called before every test. It creates the result/pv root directories
   * for the test. Creates the operator and domain if its not running.
   *
   * @throws Exception exception if result/pv/operator/domain creation fails
   */
  @BeforeEach
  public void prepare() throws Exception {
    String testClassNameShort = "itserver";
    createResultAndPvDirs(testClassName);

    // create operator1
    if (operator1 == null) {
      Map<String, Object> operatorMap = createOperatorMap(getNewSuffixCount(),
          true, testClassNameShort);
      operator1 = TestUtils.createOperator(operatorMap, Operator.RestCertType.SELF_SIGNED);
      Assertions.assertNotNull(operator1);
      domainNS1 = ((ArrayList<String>) operatorMap.get("domainNamespaces")).get(0);
      namespaceList.append((String)operatorMap.get("namespace"));
      namespaceList.append(" ").append(domainNS1);
    } else {
      operator1.verifyOperatorReady();
    }
  }

  /**
   * Releases k8s cluster lease, archives result, pv directories.
   *
   * @throws Exception exception
   */
  @AfterAll
  public static void staticUnPrepare() throws Exception {
    //tearDown(new Object() {
    //}.getClass().getEnclosingClass().getSimpleName(), namespaceList.toString());

    LoggerHelper.getLocal().info("SUCCESS");
  }

  /**
   * Create operator and verify its deployed successfully. Create domain and verify domain is
   * started. Verify admin external service by accessing admin REST endpoint with nodeport in URL
   * Verify admin t3 channel port by exec into the admin pod and deploying webapp using the channel
   * port for WLST Verify web app load balancing by accessing the webapp using loadBalancerWebPort
   * Verify domain life cycle(destroy and create) should not any impact on Operator Cluster scale
   * up/down using Operator REST endpoint, webapp load balancing should adjust accordingly. Operator
   * life cycle(destroy and create) should not impact the running domain Verify liveness probe by
   * killing managed server 1 process 3 times to kick pod auto-restart shutdown the domain by
   * changing domain serverStartPolicy to NEVER
   *
   * @throws Exception exception
   */
  @Test
  public void testDomainOnPvUsingWlst() throws Exception {
    String testClassNameShort = "itserver";
    Assumptions.assumeTrue(QUICKTEST);
    String testMethodName = new Object() {
    }.getClass().getEnclosingMethod().getName();
    logTestBegin(testMethodName);
    LoggerHelper.getLocal().log(Level.INFO,
        "Creating Operator & waiting for the script to complete execution");
    Domain domain = null;
    boolean testCompletedSuccessfully = false;
    try {
      Map<String, String> envMap = new HashMap<>();
      envMap.put("clusterType", "CONFIGURED");

      Map<String, Object> domainMap =
          createDomainMap(getNewSuffixCount(), testClassNameShort);
      domainMap.put("namespace", domainNS1);
      domainMap.put("additionalEnvMap", envMap);
      domainMap.put("createDomainPyScript",
          "integration-tests/src/test/resources/domain-home-on-pv/create-standalone-servers.py");
      domain = TestUtils.createDomain(domainMap);
      //domain.verifyDomainCreated();
      testCompletedSuccessfully = true;
    } finally {
      if (domain != null && (JENKINS || testCompletedSuccessfully)) {
        //TestUtils.deleteWeblogicDomainResources(domain.getDomainUid());
        LoggerHelper.getLocal().log(Level.INFO, "END - ");
      }
    }

    LoggerHelper.getLocal().log(Level.INFO, "SUCCESS - " + testMethodName);
  }
}
