# Copyright (c) 2018, 2020, Oracle Corporation and/or its affiliates.
# Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl.

{{- define "operator.clusterRoleBindingGeneral" }}
---
apiVersion: "rbac.authorization.k8s.io/v1"
{{- if (or .dedicated (eq (default "List" .domainNamespaceSelectionStrategy) "Dedicated")) }}
kind: "RoleBinding"
{{- else }}
kind: "ClusterRoleBinding"
{{- end }}
metadata:
  labels:
    weblogic.operatorName: {{ .Release.Namespace | quote }}
  {{- if (or .dedicated (eq (default "List" .domainNamespaceSelectionStrategy) "Dedicated")) }}
  name: "weblogic-operator-rolebinding-general"
  namespace: {{ .Release.Namespace | quote }}
  {{- else }}
  name: {{ list .Release.Namespace "weblogic-operator-clusterrolebinding-general" | join "-" | quote }}
  {{- end }}
roleRef:
  apiGroup: "rbac.authorization.k8s.io"
  {{- if (or .dedicated (eq (default "List" .domainNamespaceSelectionStrategy) "Dedicated")) }}
  kind: "Role"
  name: "weblogic-operator-role-general"
  {{- else }}
  kind: "ClusterRole"
  name: {{ list .Release.Namespace "weblogic-operator-clusterrole-general" | join "-" | quote }}
  {{- end }}
subjects:
- kind: "ServiceAccount"
  apiGroup: ""
  name: {{ .serviceAccount | quote }}
  namespace: {{ .Release.Namespace | quote }}
{{- end }}
