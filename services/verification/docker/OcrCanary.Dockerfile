# Synthetic-only OCR runtime acceptance image; NO HTTPS listener and NO real documents.
# Must be built inside isolated staging after exact source review; never auto-deploy from a PR.
FROM python:3.12-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    TMPDIR=/work

RUN apt-get update -qq && apt-get install -y --no-install-recommends \
    tesseract-ocr tesseract-ocr-ara tesseract-ocr-fra \
    poppler-utils fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/* \
    && python -m pip install --no-cache-dir --only-binary=:all: Pillow==11.3.0

WORKDIR /opt/malak
COPY services/verification/ocr/ /opt/malak/ocr/
COPY services/verification/test/test_*.py /opt/malak/test/
RUN useradd -u 10001 -M -s /usr/sbin/nologin malak && \
    chown -R 10001:10001 /opt/malak && \
    mkdir -p /work && chown 10001:10001 /work && \
    chmod 0700 /work
USER 10001:10001

CMD ["/bin/sh","-ec","tesseract --list-langs 2>&1 | grep -qx 'ara' && tesseract --list-langs 2>&1 | grep -qx 'fra' && python -m unittest discover -v -s /opt/malak/test -p 'test_*.py'"]
