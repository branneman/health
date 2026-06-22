#!/usr/bin/env python3
# /// script
# dependencies = ["anthropic"]
# ///
"""
Diagnostic script: test the Anthropic API the same way the server does.

Usage:
  uv run scripts/test-ai-estimate.py <ANTHROPIC_API_KEY>            # text only
  uv run scripts/test-ai-estimate.py <ANTHROPIC_API_KEY> image.jpg  # image only
  uv run scripts/test-ai-estimate.py <ANTHROPIC_API_KEY> image.jpg --text  # text + image
"""
import sys
import json
import base64
import time

import anthropic

SYSTEM_PROMPT = (
    "You are a nutritionist AI. Estimate the total calorie content of the described "
    "or shown meal. Return only a JSON object with a required 'kcal' integer (1–9999) "
    "and an optional 'explanation' string. If you include an explanation, keep it to "
    "one short sentence starting with the calorie count, e.g. '350 kcal — typical "
    "cocktail with one spirit measure and mixer.'"
)

OUTPUT_CONFIG = {
    "format": {
        "type": "json_schema",
        "schema": {
            "type": "object",
            "properties": {
                "kcal": {"type": "integer"},
                "explanation": {"type": "string"},
            },
            "required": ["kcal"],
            "additionalProperties": False,
        },
    }
}


def call(client, label, content):
    print(f"\n=== {label} ===")
    t0 = time.monotonic()
    try:
        resp = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=100,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": content}],
            output_config=OUTPUT_CONFIG,
        )
        elapsed = time.monotonic() - t0
        print(f"time        : {elapsed:.1f}s")
        print(f"stop_reason : {resp.stop_reason}")
        print(f"usage       : input={resp.usage.input_tokens} output={resp.usage.output_tokens}")
        raw = next((b.text for b in resp.content if b.type == "text"), None)
        print(f"raw output  : {raw!r}")
        print(f"parsed      : {json.loads(raw)}")
    except anthropic.APIStatusError as e:
        print(f"time        : {time.monotonic() - t0:.1f}s")
        print(f"API error {e.status_code}: {e.message}")
        print(f"body        : {e.body}")
    except Exception as e:
        print(f"time        : {time.monotonic() - t0:.1f}s")
        print(f"error       : {type(e).__name__}: {e}")


def load_image(path):
    with open(path, "rb") as f:
        data = base64.standard_b64encode(f.read()).decode()
    mime = "image/jpeg" if path.lower().endswith((".jpg", ".jpeg")) else "image/png"
    kb = len(data) * 3 // 4 // 1024
    print(f"Image       : {path} ({mime}, ~{kb} KB base64-decoded)")
    return {"type": "image", "source": {"type": "base64", "media_type": mime, "data": data}}


TEXT_BLOCK = {"type": "text", "text": "classic dry martini, 1 green olive"}


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    key = sys.argv[1]
    args = sys.argv[2:]
    image_path = next((a for a in args if not a.startswith("--")), None)
    include_text = "--text" in args or image_path is None

    client = anthropic.Anthropic(api_key=key)

    if include_text and not image_path:
        print(f"Text        : {TEXT_BLOCK['text']}")
        call(client, "text only", [TEXT_BLOCK])

    if image_path and not include_text:
        img = load_image(image_path)
        call(client, "image only", [img])

    if image_path and include_text:
        print(f"Text        : {TEXT_BLOCK['text']}")
        img = load_image(image_path)
        call(client, "text + image", [TEXT_BLOCK, img])


if __name__ == "__main__":
    main()
