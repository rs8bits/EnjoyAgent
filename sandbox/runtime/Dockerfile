FROM python:3.12-slim
COPY requirements.txt /opt/sandbox/requirements.txt
RUN pip install --no-cache-dir -r /opt/sandbox/requirements.txt
RUN groupadd --gid 10001 sandbox && useradd --uid 10001 --gid 10001 --no-create-home sandbox
COPY runner.py /opt/sandbox/runner.py
COPY document_tools.py /opt/sandbox/document_tools.py
USER 10001:10001
WORKDIR /workspace
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 HOME=/tmp
ENTRYPOINT ["python", "-I", "-u", "/opt/sandbox/runner.py"]
