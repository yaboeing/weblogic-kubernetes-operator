// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.weblogic.kubernetes;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.List;
import oracle.weblogic.kubernetes.annotations.IntegrationTest;
import oracle.weblogic.kubernetes.annotations.Namespaces;
import oracle.weblogic.kubernetes.logging.LoggingFacade;
import oracle.weblogic.kubernetes.utils.DbUtils;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;


import static oracle.weblogic.kubernetes.TestConstants.JRF_BASE_IMAGE_NAME;
import static oracle.weblogic.kubernetes.TestConstants.JRF_BASE_IMAGE_TAG;
import static oracle.weblogic.kubernetes.TestConstants.KIND_REPO;
import static oracle.weblogic.kubernetes.TestConstants.OCR_REGISTRY;
import static oracle.weblogic.kubernetes.utils.ThreadSafeLogger.getLogger;
import static org.awaitility.Awaitility.with;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// Test to create model in image domain and verify the domain started successfully
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Test to create model in image domain and start the domain")
@IntegrationTest
public class ItJrfMiiDomain {

  private static final String RCUSCHEMAPREFIX = "jrfdomainmii";
  private static final String PDBNAME = "pdbmii";

      //"itdb-scan.subnet1.ephemeral.oraclevcn.com:1521/ITDB_fra1jq.subnet1.ephemeral.oraclevcn.com";

  private final String domainUid = "jrfdomain-mii";
  private static String fmwImage = JRF_BASE_IMAGE_NAME + ":" + JRF_BASE_IMAGE_TAG;
  private static boolean isUseSecret = true;

  private final String wlSecretName = domainUid + "-weblogic-credentials";

  private static String rcuNamespace = null;
  private static String opNamespace = null;
  private static String jrfDomainNamespace = null;
  private static LoggingFacade logger = null;

  // create standard, reusable retry/backoff policy
  private static final ConditionFactory withStandardRetryPolicy
      = with().pollDelay(2, SECONDS)
      .and().with().pollInterval(10, SECONDS)
      .atMost(5, MINUTES).await();

  /**
   * Start DB service and create RCU schema.
   * Assigns unique namespaces for operator and domains.
   * Pull FMW image if running tests in Kind cluster.
   * Installs operator.
   *
   * @param namespaces injected by JUnit
   */
  @BeforeAll
  public static void initAll(@Namespaces(1) List<String> namespaces) {
    logger = getLogger();
    logger.info("Assign a unique namespace for DB and RCU");
    assertNotNull(namespaces.get(0), "Namespace is null");
    rcuNamespace = namespaces.get(0);

    //determine if the tests are running in Kind cluster. if true use images from Kind registry
    if (KIND_REPO != null) {
      fmwImage = KIND_REPO + JRF_BASE_IMAGE_NAME.substring(OCR_REGISTRY.length() + 1)
          + ":" + JRF_BASE_IMAGE_TAG;
      isUseSecret = false;
    }

  }

  /**
   * Test to create model in image JRF domain and verify the domain started successfully
   */
  @Test
  @DisplayName("Create model in image JRF domain")
  public void testCreateJrfMiiDomain() {
    logger = getLogger();
    logger.info("Start DB and create RCU schema for namespace: {0}, RCU prefix: {1}, "
            + "fmwImage: {2} isUseSecret: {3}", rcuNamespace, RCUSCHEMAPREFIX, fmwImage, isUseSecret);
    assertDoesNotThrow(() -> DbUtils.setupPDBandRCUschema(PDBNAME, fmwImage, RCUSCHEMAPREFIX, rcuNamespace,
        isUseSecret), String.format("Failed to create RCU schema for prefix %s in the namespace %s with "
        + "dbUrl %s, isUseSecret %s", RCUSCHEMAPREFIX, rcuNamespace, isUseSecret));
  }

}
