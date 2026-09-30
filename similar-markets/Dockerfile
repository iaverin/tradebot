FROM python:3.13-slim-bookworm

# required for psycopg2
RUN apt update \
    && apt install -y --no-install-recommends \
        build-essential \
        libpq-dev \
    && apt clean \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:latest /uv /bin/uv
RUN useradd --no-create-home --gid root runner

ENV UV_PROJECT_ENVIRONMENT=/opt/venv \
    UV_NO_MANAGED_PYTHON=1 \
    UV_NO_CACHE=true \
    UV_LINK_MODE=copy

ENV PATH="/opt/venv/bin:$PATH"

WORKDIR /code

COPY pyproject.toml .
COPY uv.lock .

# RUN uv venv
RUN uv sync --all-extras --frozen --no-install-project

ENV HF_HOME="/code/model_cache"
RUN mkdir $HF_HOME

RUN uv run python -c "from sentence_transformers import SentenceTransformer; \
    SentenceTransformer('all-MiniLM-L6-v2')"

COPY --chown=runner:root . .

# RUN chmod -R g=u /code
RUN chmod +x migrate-and-start.sh
RUN chmod +x start-worker.sh

# RUN chmod -R g=u /code

USER runner

CMD [ "./migrate-and-start.sh" ]
