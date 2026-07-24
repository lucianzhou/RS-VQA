FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1
WORKDIR /opt/rsvqa/model-service
COPY pyproject.toml ./
COPY app ./app
RUN pip install .
RUN addgroup --system rsvqa && adduser --system --ingroup rsvqa rsvqa \
    && chown -R rsvqa:rsvqa /opt/rsvqa
USER rsvqa
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
