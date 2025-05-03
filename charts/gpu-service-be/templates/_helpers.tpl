{{- define "gpu-service-be.fullname" -}}
{{- if .Values.serviceAccount.name }}
{{- .Values.serviceAccount.name }}
{{- else }}
{{- printf "%s-%s" .Release.Name .Chart.Name }}
{{- end }}
{{- end }}