#!/usr/bin/env python3
# /// script
# dependencies = ["anthropic"]
# ///
"""
Diagnostic script: test the Anthropic API the same way the server does.
Run: python3 scripts/test-ai-estimate.py <your-anthropic-api-key> [image-path]
"""
import sys
import json
import base64
import os

try:
    import anthropic
except ImportError:
    print("Installing anthropic SDK…")
    os.system(f"{sys.executable} -m pip install anthropic --quiet")
    import anthropic


def test(api_key: str, image_path: str | None = None):
    client = anthropic.Anthropic(api_key=api_key)

    # --- Build content blocks (mirrors HttpAnthropicGateway) ---
    content = []

    text = "classic dry martini, 1 green olive"
    content.append({"type": "text", "text": text})
    print(f"Text: {text}")

    if image_path:
        with open(image_path, "rb") as f:
            data = base64.standard_b64encode(f.read()).decode()
        mime = "image/jpeg" if image_path.lower().endswith(".jpg") or image_path.lower().endswith(".jpeg") else "image/png"
        content.append({
            "type": "image",
            "source": {"type": "base64", "media_type": mime, "data": data},
        })
        print(f"Image: {image_path} ({mime})")

    system_prompt = (
        "You are a nutritionist AI. Estimate the total calorie content of the described "
        "or shown meal. Return only a JSON object with a required 'kcal' integer (1–9999) "
        "and an optional 'explanation' string. If you include an explanation, keep it to "
        "one short sentence starting with the calorie count, e.g. '350 kcal — typical "
        "cocktail with one spirit measure and mixer.'"
    )

    # --- Approach 1: output_config with json_schema (what the server does) ---
    print("\n=== Approach 1: output_config / json_schema ===")
    try:
        resp = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=100,
            system=system_prompt,
            messages=[{"role": "user", "content": content}],
            output_config={
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
            },
        )
        print(f"stop_reason : {resp.stop_reason}")
        print(f"usage       : input={resp.usage.input_tokens} output={resp.usage.output_tokens}")
        text_out = next((b.text for b in resp.content if b.type == "text"), None)
        print(f"raw output  : {text_out!r}")
        parsed = json.loads(text_out)
        print(f"parsed      : {parsed}")
    except anthropic.APIStatusError as e:
        print(f"API error {e.status_code}: {e.message}")
        print(f"body: {e.body}")
    except Exception as e:
        print(f"error: {type(e).__name__}: {e}")

    # --- Approach 2: plain prompt asking for JSON (simpler fallback) ---
    print("\n=== Approach 2: plain JSON-in-prompt (no output_config) ===")
    try:
        resp2 = client.messages.create(
            model="claude-opus-4-8",
            max_tokens=100,
            system=(
                "You are a nutritionist AI. Estimate calories in the described or shown meal. "
                'Respond ONLY with valid JSON: {"kcal": <integer 1-9999>} '
                'or optionally {"kcal": <integer>, "explanation": "<one sentence starting with the kcal count>"}. '
                "No other text."
            ),
            messages=[{"role": "user", "content": content}],
        )
        print(f"stop_reason : {resp2.stop_reason}")
        print(f"usage       : input={resp2.usage.input_tokens} output={resp2.usage.output_tokens}")
        text_out2 = next((b.text for b in resp2.content if b.type == "text"), None)
        print(f"raw output  : {text_out2!r}")
        parsed2 = json.loads(text_out2)
        print(f"parsed      : {parsed2}")
    except anthropic.APIStatusError as e:
        print(f"API error {e.status_code}: {e.message}")
        print(f"body: {e.body}")
    except Exception as e:
        print(f"error: {type(e).__name__}: {e}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 scripts/test-ai-estimate.py <ANTHROPIC_API_KEY> [image.jpg]")
        sys.exit(1)
    key = sys.argv[1]
    img = sys.argv[2] if len(sys.argv) > 2 else None
    test(key, img)
