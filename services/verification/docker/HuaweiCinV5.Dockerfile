# Manual, isolated Huawei-only private CIN OCR runtime.
# Never run this image with public ports or real IDs in CI.
FROM python:3.12-slim-bookworm
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OMP_NUM_THREADS=2 \
    OPENBLAS_NUM_THREADS=1 \
    TMPDIR=/work
RUN apt-get update -qq && apt-get install -y --no-install-recommends \
      ca-certificates curl libglib2.0-0 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
RUN python -m pip install --no-cache-dir --only-binary=:all: \
      "numpy==1.26.4" "opencv-python-headless==4.10.0.84" \
      "onnxruntime==1.23.2" "PyYAML==6.0.2"
RUN mkdir -p /opt/malak/models/arabic-v5 /work && \
    curl -fL --retry 3 --max-time 120 \
      https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.onnx \
      -o /opt/malak/models/arabic-v5/inference.onnx && \
    echo "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110  /opt/malak/models/arabic-v5/inference.onnx" | sha256sum -c - && \
    curl -fL --retry 3 --max-time 120 \
      https://huggingface.co/PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx/resolve/main/inference.yml \
      -o /opt/malak/models/arabic-v5/inference.yml && \
    echo "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6  /opt/malak/models/arabic-v5/inference.yml" | sha256sum -c -
COPY services/verification/ocr/cin_v5.py /opt/malak/ocr/cin_v5.py
COPY services/verification/ocr/cin_v5_vote.py /opt/malak/ocr/cin_v5_vote.py
COPY services/verification/ocr/cin_v5_date.py /opt/malak/ocr/cin_v5_date.py
COPY services/verification/ocr/cin_v5_digits.py /opt/malak/ocr/cin_v5_digits.py
COPY services/verification/ocr/cin_v5_region.py /opt/malak/ocr/cin_v5_region.py
COPY services/verification/ocr/test_cin_v5_region.py /opt/malak/ocr/test_cin_v5_region.py
COPY services/verification/ocr/test_cin_v5_numeric_preprocess.py /opt/malak/ocr/test_cin_v5_numeric_preprocess.py
COPY services/verification/ocr/cin_v5_eval_scope.py /opt/malak/ocr/cin_v5_eval_scope.py
COPY services/verification/ocr/test_cin_v5_exact_eval.py /opt/malak/ocr/test_cin_v5_exact_eval.py
COPY services/verification/test/test_cin_v5_digits.py /opt/malak/ocr/test_cin_v5_digits.py
COPY services/verification/test/test_cin_v5_date_votes.py /opt/malak/ocr/test_cin_v5_date_votes.py
COPY services/verification/ocr/test_cin_v5_date.py /opt/malak/ocr/test_cin_v5_date.py
COPY services/verification/ocr/cin_v5_socket.py /opt/malak/ocr/cin_v5_socket.py
RUN useradd -u 10001 -M -s /usr/sbin/nologin malak && \
    chown -R 10001:10001 /opt/malak /work && \
    chmod 0700 /work && \
    python -m py_compile /opt/malak/ocr/cin_v5.py /opt/malak/ocr/cin_v5_socket.py /opt/malak/ocr/cin_v5_date.py /opt/malak/ocr/cin_v5_digits.py /opt/malak/ocr/cin_v5_region.py /opt/malak/ocr/cin_v5_eval_scope.py && \
    python -m unittest discover -s /opt/malak/ocr -p 'test_cin_v5*.py' -v
USER 10001:10001
WORKDIR /opt/malak
# This image has no HTTP listener, no network at runtime and no document volume.
# The authorized private gateway must inject this worker behind a rate limit.
ENTRYPOINT ["python","-E","-s","/opt/malak/ocr/cin_v5.py"]
