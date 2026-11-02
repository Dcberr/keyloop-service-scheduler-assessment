#!/usr/bin/env python3
"""Validate the OpenAPI document and actual HTTP response samples (no network calls)."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit
import yaml
from jsonschema import Draft202012Validator, FormatChecker
from openapi_spec_validator import validate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("samples", type=Path)
    parser.add_argument("--spec", type=Path, default=Path(__file__).resolve().parents[2] / "docs/openapi.yaml")
    args = parser.parse_args()
    spec = yaml.safe_load(args.spec.read_text())
    validate(spec)

    def expand(value, stack=()):
        if isinstance(value, list):
            return [expand(v, stack) for v in value]
        if not isinstance(value, dict):
            return value
        if "$ref" in value:
            reference = value["$ref"]
            if not reference.startswith("#/") or reference in stack:
                raise ValueError("Only acyclic local references are supported by this response checker")
            target = spec
            for part in reference[2:].split("/"):
                target = target[part.replace("~1", "/").replace("~0", "~")]
            return {**expand(target, stack + (reference,)),
                    **expand({k: v for k, v in value.items() if k != "$ref"}, stack)}
        return {k: expand(v, stack) for k, v in value.items()}

    samples = json.loads(args.samples.read_text())
    if not isinstance(samples, list) or not samples:
        raise ValueError("Expected a nonempty list of actual HTTP samples")
    coverage, successful, checked, framework = set(), set(), 0, 0
    for sample in samples:
        method, status = sample["method"].lower(), str(sample["status"])
        actual = urlsplit(sample["path"]).path
        matched = None
        for path, item in spec["paths"].items():
            pattern = re.sub(r"\{[^}]+\}", r"[^/]+", path)
            if re.fullmatch("/api/v1" + pattern, actual) or re.fullmatch(pattern, actual):
                if method in item:
                    matched = path, item[method]
                    break
        headers = {k.lower(): v[0] if isinstance(v, list) else v for k, v in sample["headers"].items()}
        assert headers.get("x-request-id"), f"Missing correlation header: {method} {actual}"
        if int(status) >= 400:
            assert sample["body"]["status"] == int(status)
            assert sample["body"]["requestId"] == headers["x-request-id"]
        if matched is None:
            assert int(status) >= 400, f"Unexpected success outside documented operations: {method} {actual}"
            assert headers.get("content-type", "").split(";")[0] == "application/problem+json"
            Draft202012Validator(expand(spec["components"]["schemas"]["Problem"]),
                                 format_checker=FormatChecker()).validate(sample["body"])
            framework += 1
            continue
        path, operation = matched
        assert status in operation["responses"] or int(status) >= 400, f"Undocumented success: {method} {path} {status}"
        response = expand(operation["responses"].get(status, operation["responses"].get("default")))
        assert response is not None, f"Undocumented status {status}: {method} {path}"
        media_type = headers.get("content-type", "").split(";")[0]
        assert media_type in response["content"], f"Unexpected content type {media_type}"
        Draft202012Validator(response["content"][media_type]["schema"],
                             format_checker=FormatChecker()).validate(sample["body"])
        for key, description in response.get("headers", {}).items():
            # These contract headers are emitted on every relevant response.
            assert key.lower() in headers, f"Missing {key}: {method} {path} {status}"
            Draft202012Validator(description.get("schema", {}), format_checker=FormatChecker()).validate(headers[key.lower()])
        checked += 1
        coverage.add((method, path))
        if int(status) < 300:
            successful.add((method, path))
    print(f"OpenAPI valid; {checked} operation responses and {framework} routing/method-error responses schema-valid.")
    print(f"Operations observed: {len(coverage)}; operations with successful responses: {len(successful)}.")
    expected = {(method, path) for path, item in spec["paths"].items() for method in item if method in ("get", "post")}
    assert successful == expected, f"Missing successful operation samples: {expected - successful}"


if __name__ == "__main__":
    main()
