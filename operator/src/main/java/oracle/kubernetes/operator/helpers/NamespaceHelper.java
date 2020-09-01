// Copyright (c) 2020, Oracle Corporation and/or its affiliates.
// Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

package oracle.kubernetes.operator.helpers;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nonnull;

import com.google.common.base.Strings;
import io.kubernetes.client.openapi.models.V1ListMeta;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import oracle.kubernetes.operator.work.Step;

import static oracle.kubernetes.operator.helpers.HelmAccess.getHelmVariable;

/**
 * Operations for dealing with namespaces.
 */
public class NamespaceHelper {
  public static final String DEFAULT_NAMESPACE = "default";

  private static final String operatorNamespace = computeOperatorNamespace();

  private static String computeOperatorNamespace() {
    return Optional.ofNullable(getHelmVariable("OPERATOR_NAMESPACE")).orElse(DEFAULT_NAMESPACE);
  }

  public static String getOperatorNamespace() {
    return operatorNamespace;
  }

  /**
   * Parse a string of namespace names and return them as a collection.
   * @param namespaceString a comma-separated list of namespace names
   */
  public static Collection<String> parseNamespaceList(String namespaceString) {
    Collection<String> namespaces
          = Stream.of(namespaceString.split(","))
          .filter(s -> !Strings.isNullOrEmpty(s))
          .map(String::trim)
          .collect(Collectors.toUnmodifiableList());
    
    return namespaces.isEmpty() ? Collections.singletonList(operatorNamespace) : namespaces;
  }

  /**
   * Creates and returns a step to return a list of all namespaces. If there are more namespaces than the
   * request limit, will make multiple requests.
   * @param responseStep the step to receive the final list response.
   * @param labelSelector an optional selector to include only certain namespaces
   */
  public static Step createNamespaceListStep(ResponseStep<V1NamespaceList> responseStep, String labelSelector) {
    return new NamespaceListContext(responseStep, labelSelector).createListStep();
  }

  static class NamespaceListContext extends ChunkedListContext<V1NamespaceList, V1Namespace> {
    private final String labelSelector;

    @Override
    @Nonnull List<V1Namespace> getItems(V1NamespaceList list) {
      return list.getItems();
    }

    @Override
    @Nonnull V1ListMeta getListMetadata(V1NamespaceList list) {
      return Objects.requireNonNull(list.getMetadata());
    }

    @Override
    @Nonnull V1NamespaceList createList(V1ListMeta meta, List<V1Namespace> items) {
      return new V1NamespaceList().metadata(meta).items(items);
    }

    @Override
    @Nonnull Step createAsyncListStep(CallBuilder callBuilder, ResponseStep<V1NamespaceList> responseStep) {
      return callBuilder.listNamespaceAsync(responseStep);
    }

    @Override
    CallBuilder configureCallBuilder(CallBuilder callBuilder) {
      return callBuilder.withLabelSelector(labelSelector);
    }

    public NamespaceListContext(ResponseStep<V1NamespaceList> responseStep, String labelSelector) {
      super(responseStep);
      this.labelSelector = labelSelector;
    }

    Step createListStep() {
      return new NamespaceListStep();
    }

  }
}
