FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    HF_HOME=/opt/rsvqa/model-cache

WORKDIR /opt/rsvqa/knowledge-service
COPY pyproject.toml ./
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.6.0
RUN mkdir -p app \
    && touch app/__init__.py \
    && pip install . \
    && rm -rf app
COPY app ./app
COPY eval ./eval
COPY scripts ./scripts
RUN pip install --no-deps .
RUN addgroup --system rsvqa && adduser --system --ingroup rsvqa rsvqa \
    && mkdir -p /opt/rsvqa/model-cache \
    && chown -R rsvqa:rsvqa /opt/rsvqa

USER rsvqa
EXPOSE 8010
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8010", "--no-access-log"]
