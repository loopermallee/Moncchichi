#!/usr/bin/env python3
"""Generate the public README from CONTEXT_ENGINEERING.md."""

from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

CONTEXT_PATH = Path("CONTEXT_ENGINEERING.md")
README_PATH = Path("README.md")


def extract_current_phase(markdown: str) -> dict[str, str | list[tuple[str, str, str, str]]]:
    """Extract the current phase information and milestone rows."""
    section = re.search(
        r"## ⚙️ ACTIVE DEVELOPMENT CONTEXT(.*?)## 🧩 CODEX TASK ZONE",
        markdown,
        re.S,
    )
    current_phase = section.group(1) if section else ""
    phase_title = re.search(r"CURRENT_PHASE:\s*(.*)", current_phase)
    phase_objective = re.search(r"PHASE_OBJECTIVE:\s*(.*)", current_phase)
    milestones = re.findall(r"\| (\d+) \| (.*?) \| (.*?) \| (.*?) \|", markdown)
    return {
        "phase_title": phase_title.group(1).strip() if phase_title else "Unknown",
        "phase_objective": phase_objective.group(1).strip() if phase_objective else "",
        "milestones": milestones,
    }


def extract_phase_history(markdown: str) -> list[tuple[str, str]]:
    """Extract condensed phase history from the summary section."""
    section = re.search(r"## 🧾 PHASE SUMMARY(.*?)(##|$)", markdown, re.S)
    if not section:
        return []

    lines = [line.strip() for line in section.group(1).splitlines() if line.strip().startswith("**")]
    history: list[tuple[str, str]] = []
    for line in lines:
        match = re.match(r"\*\*(.*?)\*\*.*?—\s*(.*)", line)
        if match:
            phase, description = match.groups()
            history.append((phase.strip(), description.strip()))
    return history


def extract_issue_history(markdown: str) -> list[tuple[str, str, str]]:
    """Extract up to five recent issues or commits from the progress notes."""
    table = re.findall(r"\| (\d{4}-\d{2}-\d{2}) \| (.*?) \| (.*?) \|", markdown)
    return table[-5:] if len(table) > 5 else table


def build_readme(data: dict[str, object]) -> str:
    """Construct the README content from extracted data."""
    now = datetime.now().strftime("%Y-%m-%d")
    phase: dict[str, object] = data["phase"]  # type: ignore[assignment]
    history: list[tuple[str, str]] = data["history"]  # type: ignore[assignment]
    issues: list[tuple[str, str, str]] = data["issues"]  # type: ignore[assignment]

    lines: list[str] = []

    lines.append("# 🧠 Moncchichi Hub")
    lines.append("*A companion control & AI interface for Even Realities G1 Smart Glasses.*\n")
    lines.append("## 📍 Project Overview")
    lines.append(
        "Moncchichi Hub connects the **Even Realities G1 Smart Glasses** with an **AI assistant** "
        "that delivers live telemetry, contextual replies, and on-device intelligence."
    )
    lines.append("It merges:")
    lines.append("- 🔗 **BLE Telemetry:** battery, firmware, and sensor data")
    lines.append("- 💬 **AI Assistant:** GPT-4o-mini for contextual help and automation")
    lines.append("- 🧱 **Offline Reliability:** cached responses when network is unavailable")
    lines.append("- 🧩 **Minimal UI:** optimized for hands-free and field use\n")

    lines.append("## ⚙️ Development Progress")
    lines.append(f"### Current Phase — {phase['phase_title']}")
    lines.append(f"**Objective:** {phase['phase_objective']}\n")
    lines.append("| # | Milestone | Status | Summary |")
    lines.append("|---|------------|--------|---------|")
    for milestone in phase["milestones"]:  # type: ignore[index]
        lines.append(f"| {milestone[0]} | {milestone[1]} | {milestone[2]} | {milestone[3]} |")

    lines.append("\n## 🧩 Phase History (Chronological Overview)")
    lines.append("| Major Phase | Highlights | Status |")
    lines.append("|--------------|-------------|---------|")
    for phase_history in history:
        lines.append(f"| {phase_history[0]} | {phase_history[1]} | ✅ |")

    lines.append("\n## 🧾 Issue History (latest 5)")
    lines.append("| Date | Summary | Status |")
    lines.append("|------|----------|---------|")
    for issue in issues:
        lines.append(f"| {issue[0]} | {issue[1]} | {issue[2]} |")

    lines.append(f"\n_Last synchronized: {now}_")
    return "\n".join(lines)


def main() -> None:
    markdown = CONTEXT_PATH.read_text(encoding="utf-8")
    phase = extract_current_phase(markdown)
    history = extract_phase_history(markdown)
    issues = extract_issue_history(markdown)
    readme_content = build_readme({"phase": phase, "history": history, "issues": issues})
    README_PATH.write_text(readme_content, encoding="utf-8")
    print("✅ README updated successfully from CONTEXT_ENGINEERING.md")


if __name__ == "__main__":
    main()
