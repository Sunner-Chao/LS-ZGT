from __future__ import annotations

from io import BytesIO
from typing import Any

import fitz  # PyMuPDF
from flask import Flask, jsonify, request


app = Flask(__name__)


@app.get("/health")
def health() -> Any:
    return jsonify({"status": "ok"})


@app.post("/api/parse")
def parse_pdf() -> Any:
    upload = request.files.get("file")
    if upload is None:
        # Keep 200 with "error" to match backend client's response contract.
        return jsonify({"error": "missing file field: file"})

    try:
        file_bytes = upload.read()
        if not file_bytes:
            return jsonify({"error": "empty file"})

        doc = fitz.open(stream=file_bytes, filetype="pdf")
        try:
            pages = []
            for page in doc:
                pages.append(page.get_text("text") or "")
            text = "\n".join(pages).strip()
        finally:
            doc.close()

        return jsonify(
            {
                "text": text,
                "pages": len(pages),
                "filename": upload.filename or "unknown.pdf",
            }
        )
    except Exception as exc:
        return jsonify({"error": f"parse failed: {exc}"})


@app.post("/parse")
def parse_pdf_compat() -> Any:
    # Backward-compatible alias.
    return parse_pdf()


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=8000, debug=False)
