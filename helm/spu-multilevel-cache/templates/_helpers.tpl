{{/*
通用模板辅助函数
*/}}

{{/*
Chart 全名 — 优先使用 Release Name
*/}}
{{- define "spu-cache.fullname" -}}
{{- if contains .Chart.Name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{/*
Chart 名称
*/}}
{{- define "spu-cache.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Chart 版本标签
*/}}
{{- define "spu-cache.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
通用标签
*/}}
{{- define "spu-cache.labels" -}}
helm.sh/chart: {{ include "spu-cache.chart" . }}
{{ include "spu-cache.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: spu-multilevel-cache
environment: {{ .Values.global.environment | default "dev" }}
{{- end }}

{{/*
Selector 标签
*/}}
{{- define "spu-cache.selectorLabels" -}}
app.kubernetes.io/name: {{ include "spu-cache.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: spu-cache-service
{{- end }}

{{/*
ServiceAccount 名称
*/}}
{{- define "spu-cache.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "spu-cache.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
命名空间
*/}}
{{- define "spu-cache.namespace" -}}
{{- default .Release.Namespace .Values.global.namespace }}
{{- end }}

{{/*
镜像完整路径
*/}}
{{- define "spu-cache.image" -}}
{{- printf "%s:%s" .Values.image.repository (.Values.image.tag | default .Chart.AppVersion) }}
{{- end }}

{{/*
ConfigMap 名称
*/}}
{{- define "spu-cache.configMapName" -}}
{{- printf "%s-config" (include "spu-cache.fullname" .) }}
{{- end }}

{{/*
Secret 名称
*/}}
{{- define "spu-cache.secretName" -}}
{{- printf "%s-secret" (include "spu-cache.fullname" .) }}
{{- end }}
