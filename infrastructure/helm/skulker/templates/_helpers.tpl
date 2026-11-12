{{- define "skulker.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 50 | trimSuffix "-" -}}
{{- end -}}

{{- define "skulker.fullname" -}}
{{- default .Release.Name .Values.fullnameOverride | trunc 50 | trimSuffix "-" -}}
{{- end -}}

{{- define "skulker.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | quote }}
app.kubernetes.io/name: {{ include "skulker.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "skulker.selectorLabels" -}}
app.kubernetes.io/name: {{ include "skulker.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "skulker.image" -}}
{{- if .digest -}}
{{- printf "%s:%s@%s" .repository .tag .digest -}}
{{- else -}}
{{- printf "%s:%s" .repository .tag -}}
{{- end -}}
{{- end -}}

{{- define "skulker.appEnvironment" -}}
- name: SERVER_ADDRESS
  value: "0.0.0.0"
- name: SKULKER_DATABASE_URL
  value: {{ printf "jdbc:postgresql://%s-postgres:5432/skulker" (include "skulker.fullname" .) | quote }}
- name: SKULKER_DATABASE_USERNAME
  value: skulker
- name: SPRING_FLYWAY_CONNECT_RETRIES
  value: "30"
- name: SPRING_FLYWAY_CONNECT_RETRIES_INTERVAL
  value: "2s"
- name: SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE
  value: "5"
- name: SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE
  value: "1"
- name: SKULKER_DB_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.existingSecret }}
      key: db-password
- name: SKULKER_RABBIT_HOST
  value: {{ printf "%s-rabbitmq" (include "skulker.fullname" .) | quote }}
- name: SKULKER_RABBIT_USERNAME
  value: skulker
- name: SKULKER_RABBIT_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.existingSecret }}
      key: rabbit-password
- name: SKULKER_LOCAL_JOBS_ENABLED
  value: "false"
- name: SKULKER_MESSAGING_ENABLED
  value: "true"
- name: SKULKER_MESSAGING_SCHEDULING_ENABLED
  value: "true"
- name: JAVA_TOOL_OPTIONS
  value: "-XX:MaxRAMPercentage=60 -XX:InitialRAMPercentage=10 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"
- name: SERVER_SHUTDOWN
  value: graceful
- name: SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE
  value: "70s"
- name: MANAGEMENT_ENDPOINT_HEALTH_GROUP_LIVENESS_INCLUDE
  value: livenessState
{{- end -}}
