// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nonnull;

import com.google.common.base.Strings;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ListMeta;
import oracle.kubernetes.operator.calls.CallResponse;
import oracle.kubernetes.operator.calls.RequestParams;
import oracle.kubernetes.operator.work.NextAction;
import oracle.kubernetes.operator.work.Packet;
import oracle.kubernetes.operator.work.Step;

abstract class ChunkedListSupport<L extends KubernetesListObject,R extends KubernetesObject> {

  private final ResponseStep<L> responseStep;
  private final List<R> resources = new ArrayList<>();
  private String continueToken = "";
  private String resourceVersion;

  abstract @Nonnull L createList(V1ListMeta meta, List<R> items);

  abstract @Nonnull Step createAsyncListStep(CallBuilder callBuilder, ResponseStep<L> responseStep);

  CallBuilder configureCallBuilder(CallBuilder callBuilder) {
    return callBuilder;
  }

  public ChunkedListSupport(ResponseStep<L> responseStep) {
    this.responseStep = responseStep;
  }

  Step createListStep() {
    return new NamespaceListStep();
  }

  ResponseStep<L> createResponseStep() {
    return new NamespaceListChunkedResponseStep();
  }

  boolean restartNeeded(String newResourceVersion) {
    try {
      return (resourceVersion != null && !resourceVersion.equals(newResourceVersion));
    } finally {
      resourceVersion = newResourceVersion;
    }
  }

  class SuccessContextUpdate {

    private final CallResponse<L> callResponse;

    public SuccessContextUpdate(CallResponse<L> callResponse) {
      this.callResponse = callResponse;
      continueToken = getMetadata().getContinue();

      if (restartNeeded(getMetadata().getResourceVersion())) {
        resources.clear();
      }
      resources.addAll(getItems(callResponse));
    }

    @SuppressWarnings("unchecked")
    private List<R> getItems(CallResponse<L> callResponse) {
      return (List<R>) callResponse.getResult().getItems();
    }

    @Nonnull
    private V1ListMeta getMetadata() {
      return callResponse.getResult().getMetadata();
    }

    @Nonnull
    public CallResponse<L> createSuccessResponse() {
      return CallResponse.createSuccess(getRequestParams(), createResult(), getStatusCode());
    }

    private RequestParams getRequestParams() {
      return callResponse.getRequestParams();
    }

    private L createResult() {
      return createList(getMetadata(), resources);
    }

    private int getStatusCode() {
      return callResponse.getStatusCode();
    }

    private boolean allItemsRetrieved() {
      return Strings.isNullOrEmpty(continueToken);
    }
  }

  class NamespaceListStep extends Step {

    @Override
    public NextAction apply(Packet packet) {
      return doNext(createAsyncListStep(createConfiguredCallBuilder(), createResponseStep()), packet);
    }
  }

  private CallBuilder createConfiguredCallBuilder() {
    return configureCallBuilder(new CallBuilder()).withContinue(continueToken);
  }

  class NamespaceListChunkedResponseStep extends ResponseStep<L> {

    @Override
    public NextAction onSuccess(Packet packet, CallResponse<L> callResponse) {
      SuccessContextUpdate update = new SuccessContextUpdate(callResponse);
      if (update.allItemsRetrieved()) {
        return responseStep.onSuccess(packet, update.createSuccessResponse());
      } else {
        return doNext(createListStep(), packet);
      }
    }

    @Override
    public NextAction onFailure(Packet packet, CallResponse<L> callResponse) {
      return responseStep.onFailure(packet, callResponse);
    }
  }

}
