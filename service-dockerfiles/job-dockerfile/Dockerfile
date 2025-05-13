# FROM nvidia/cuda:12.8.1-cudnn-devel-ubuntu24.04
FROM nvidia/cuda:12.9.0-cudnn-devel-ubuntu24.04
# install utilities and python
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl jq ca-certificates python3 python3-pip && \
    rm -rf /var/lib/apt/lists/*

# allow pip to install packages despite PEP 668 restrictions
ENV PIP_BREAK_SYSTEM_PACKAGES=1

# install Python packages system-wide
RUN pip3 install --no-cache-dir papermill awscli ipykernel && \
    python3 -m ipykernel install --name python3 --display-name "Python 3"

# use bash entrypoint for job script
WORKDIR /workspace
ENTRYPOINT ["/bin/bash", "-c"]
CMD ["echo 'No command provided'; exit 1"]