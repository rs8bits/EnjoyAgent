#!/usr/bin/env python3
"""Generate a byte-exact, deterministic UTF-8 text fixture for ingestion benchmarks."""

import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bytes", type=int, default=65_536)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.bytes <= 0:
        raise SystemExit("--bytes must be positive")

    paragraph = (
        "EnjoyAgent performance fixture: transactional outbox, idempotent consumer, "
        "Caffeine L1, Redis L2, and Redisson single-flight.\n"
    ).encode("utf-8")
    content = (paragraph * ((args.bytes // len(paragraph)) + 1))[: args.bytes]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_bytes(content)
    print(f"generated={args.output} bytes={len(content)}")


if __name__ == "__main__":
    main()
