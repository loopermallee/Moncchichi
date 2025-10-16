#!/usr/bin/env python3
import json, os, re, sys, datetime, pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
LEDGER = ROOT / "codex-progress.json"
README = ROOT / "README.md"
EVENT_PATH = os.environ.get("GITHUB_EVENT_PATH")

def load_json(path, default):
    if path.exists():
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    return default

def save_json(path, data):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")

def clamp(v, lo=0, hi=100): return max(lo, min(hi, v))

def derive_delta_from_keywords(title, body, commits_text):
    text = " ".join([title or "", body or "", commits_text or ""]).lower()
    if re.search(r"revert|rollback", text): return -2, "revert"
    if re.search(r"fix|hotfix|bug", text): return +2, "fix"
    if re.search(r"feat|add|implement", text): return +4, "feat"
    if re.search(r"perf|optimi", text): return +1, "perf"
    if re.search(r"refactor|cleanup", text): return 0, "refactor"
    if re.search(r"docs|ci|chore", text): return 0, "neutral"
    return 0, "neutral"

def extract_goal(body):
    if not body: return None
    m = re.search(r"(?im)^\s*goal\s*:\s*(.+)$", body)
    return m.group(1).strip() if m else None

def today_str():
    now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
    return now.strftime("%Y-%m-%d %H:%M SGT")

def ensure_issue_history_section(readme_text):
    if "🚧 Issue History" in readme_text:
        return re.sub(
            r"## 🚧 Issue History[\s\S]*?(?=##|$)",
            "## 🚧 Issue History\n_Auto-maintained by Codex on each merge._\n",
            readme_text,
        )
    return readme_text.strip() + "\n\n## 🚧 Issue History\n_Auto-maintained by Codex on each merge._\n"

def update_total_progress_line(readme_text, new_percent, trend_symbol):
    pattern = re.compile(r"(?im)^Total Progress:.*$")
    replacement = f"Total Progress: 🟩 ~{new_percent} % complete {trend_symbol} (auto-updated {today_str()})"
    if pattern.search(readme_text):
        return pattern.sub(replacement, readme_text)
    lines = readme_text.splitlines()
    insert_at = 1 if lines and lines[0].startswith("#") else 0
    lines.insert(insert_at, replacement)
    return "\n".join(lines) + "\n"

def append_issue_history_entry(readme_text, pr_number, title, delta, goal, keywords):
    header_pat = re.compile(r"(?im)^##\s*🚧 Issue History\s*$")
    parts = readme_text.splitlines()
    try:
        idx = next(i for i, l in enumerate(parts) if header_pat.match(l))
    except StopIteration:
        parts.append("## 🚧 Issue History")
        parts.append("_Auto-maintained by Codex on each merge._")
        idx = len(parts) - 2
    entry = f"- {today_str()} — PR #{pr_number}: **{title.strip()}** · delta `{delta:+d}%` · tag `{keywords}`" + (f" · goal: _{goal}_" if goal else "")
    parts.insert(idx + 2, entry)
    filtered = parts[:idx + 2] + parts[idx + 2:idx + 12]
    return "\n".join(filtered) + "\n"

def read_event():
    data = load_json(pathlib.Path(EVENT_PATH), {})
    pr = data.get("pull_request", {})
    title = pr.get("title", "")
    body = pr.get("body", "")
    number = pr.get("number") or pr.get("id") or 0
    return number, title, body

def main():
    number, title, body = read_event()
    ledger = load_json(LEDGER, {
        "project": "Moncchichi BLE Hub",
        "total_progress": 70,
        "history": []
    })

    delta, tag = derive_delta_from_keywords(title, body, commits_text=None)
    new_total = clamp(ledger.get("total_progress", 0) + delta)

    goal = extract_goal(body)
    trend = "🔺" if delta > 0 else ("🔻" if delta < 0 else "➖")

    readme_text = README.read_text(encoding="utf-8") if README.exists() else "# Moncchichi BLE Hub\n"
    readme_text = ensure_issue_history_section(readme_text)
    readme_text = update_total_progress_line(readme_text, new_total, trend)
    readme_text = append_issue_history_entry(readme_text, number, title or "No title", delta, goal, tag)
    README.write_text(readme_text, encoding="utf-8")

    ledger.update({
        "total_progress": new_total,
        "last_update": today_str(),
        "last_delta": delta,
        "last_keywords": tag,
        "last_goal": goal,
        "last_pr_number": number
    })
    ledger["history"].insert(0, {
        "when": today_str(),
        "pr": number,
        "title": title,
        "delta": delta,
        "keywords": tag,
        "goal": goal,
        "total_after": new_total
    })
    save_json(LEDGER, ledger)

if __name__ == "__main__":
    if not EVENT_PATH or not pathlib.Path(EVENT_PATH).exists():
        print("No GITHUB_EVENT_PATH; exiting without changes.")
        sys.exit(0)
    main()
