helm install redis oci://registry-1.docker.io/bitnamicharts/redis   --set auth.password=FNsfJW3B1n -n gpu-service --create-namespace
helm install postgresql-ha     --set postgresql.password=czCqSinzp0    oci://registry-1.docker.io/bitnamicharts/postgresql-ha -n gpu-service
helm install minio oci://registry-1.docker.io/bitnamicharts/minio  --set auth.rootPassword=mhAH74T0Wo -n gpu-service



