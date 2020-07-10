// Copyright (c) 2017, 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.steps;

import oracle.kubernetes.operator.logging.LoggingFacade;
import oracle.kubernetes.operator.logging.LoggingFactory;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;
import oracle.kubernetes.weblogic.domain.model.Domain;

public class DomainPresenceStep extends Step {

  private static final LoggingFacade LOGGER = LoggingFactory.getLogger("Operator", "Operator");

  private DomainPresenceStep(Step domainUpSteps) {
    super(domainUpSteps);
  }

  /**
   * {@link Step} that creates domain presence.
   *
   * @param dom Domain
   * @param domainUpSteps domain up step
   * @param managedServerStep managed server step
   * @return Step for domain presence
   */  
  public static DomainPresenceStep createDomainPresenceStep(
      Domain dom, Step domainUpSteps, Step managedServerStep) {
    LOGGER.info("DEBUG: domain is " + dom.toString());
    LOGGER.info("DEBUG: dom.isShuttingDown() is " + dom.isShuttingDown());
    return new DomainPresenceStep(dom.isShuttingDown() ? managedServerStep : domainUpSteps);
  }

  @Override
  public NextAction apply(Packet packet) {
    return doNext(packet);
  }
}
