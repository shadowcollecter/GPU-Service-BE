helm install redis oci://registry-1.docker.io/bitnamicharts/redis   --set auth.password=FNsfJW3B1n -n gpu-service
helm install postgresql-ha     --set postgresql.password=czCqSinzp0    oci://registry-1.docker.io/bitnamicharts/postgresql-ha -n gpu-service

