#!/usr/bin/env python3
"""Render fault-drill TSV results as machine-readable JSON and Markdown."""

from __future__ import annotations

import argparse
import csv
import json
from datetime import datetime, timezone
from pathlib import Path


def read_metadata(path: Path) -> dict[str, str]:
    metadata: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        metadata[key] = value
    return metadata


def read_results(path: Path) -> list[dict[str, str]]:
    if not path.exists() or path.stat().st_size == 0:
        return []
    with path.open(newline="", encoding="utf-8") as source:
        reader = csv.DictReader(
            source,
            delimiter="\t",
            fieldnames=("scenario", "outcome", "summary", "evidence"),
        )
        return list(reader)


def markdown(metadata: dict[str, str], rows: list[dict[str, str]]) -> str:
    overall = "PASS" if rows and all(row["outcome"] == "PASS" for row in rows) else "FAIL"
    lines = [
        "# EnjoyAgent 故障演练执行报告",
        "",
        f"- 总体结果：**{overall}**",
        f"- 运行 ID：`{metadata.get('run_id', 'unknown')}`",
        f"- 开始时间（UTC）：`{metadata.get('started_at_utc', 'unknown')}`",
        f"- Git commit：`{metadata.get('git_commit', 'unknown')}`",
        f"- 工作树修改数：`{metadata.get('git_dirty_count', 'unknown')}`",
        f"- JAR SHA-256：`{metadata.get('jar_sha256', 'unknown')}`",
        "",
        "## 场景结果",
        "",
        "| 场景 | 结果 | 关键证据 | 证据文件 |",
        "|---|---|---|---|",
    ]
    for row in rows:
        summary = row["summary"].replace("|", "\\|")
        evidence = row["evidence"].replace("|", "\\|")
        lines.append(
            f"| `{row['scenario']}` | **{row['outcome']}** | {summary} | `{evidence}` |"
        )
    if not rows:
        lines.append("| `suite` | **FAIL** | 未产生场景结果 | `application.log` |")
    lines.extend(
        [
            "",
            "## 说明",
            "",
            "- 报告只把脚本断言通过的结果标记为 PASS；原始 HTTP、数据库、队列和 Mock 快照保存在同目录。",
            "- HTTP 408 是可控 Mock 明确返回的上游超时响应，用于稳定验证有限重试与 parking queue；它不等价于验证 TCP 黑洞下的客户端读超时配置。",
            "- 演练使用独立 RabbitMQ 拓扑和独立测试租户，不删除 Docker 数据卷。",
            "",
        ]
    )
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--results", required=True, type=Path)
    parser.add_argument("--json-output", required=True, type=Path)
    parser.add_argument("--markdown-output", required=True, type=Path)
    args = parser.parse_args()

    metadata = read_metadata(args.metadata)
    rows = read_results(args.results)
    payload = {
        "schema_version": 1,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "overall": "PASS" if rows and all(row["outcome"] == "PASS" for row in rows) else "FAIL",
        "metadata": metadata,
        "scenarios": rows,
    }
    args.json_output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    args.markdown_output.write_text(markdown(metadata, rows), encoding="utf-8")


if __name__ == "__main__":
    main()
