#!/usr/bin/env python3
"""Parses the log files produced by ../run_all.sh (in this same directory)
and renders them as matplotlib figures, plus a couple of CSV summaries.

Log formats it understands (see TeacherServer.java / socket_learn.ml /
LearnerClient.java for where these lines actually get printed):

  target=<name>          states=<n> queries=<n> time=<s>s          (teacher side, direction A)
  target=<name> algo=<a> alphabet=<n> states=<n> time=<s>s          (learner side, either direction)
  agree  target=<name> algo=<a> ours_states=<n> learnlib_states=<n>
         learnlib_queries=<n> learnlib_time=<s>s agree=<true|false> (direction A only)

Run after run_all.sh has populated this directory with direction_a_*.log /
direction_b_*.log files:

    python3 plot_results.py
"""

import re
import sys
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import pandas as pd

RESULTS_DIR = Path(__file__).resolve().parent
ALGOS = ["lstar", "kv", "ttt"]
ALGO_COLORS = {"lstar": "#3a6fd8", "kv": "#349a57", "ttt": "#d1584f"}

TEACHER_LINE = re.compile(
    r"^target=(?P<target>\S+)\s+states=(?P<states>\d+)\s+queries=(?P<queries>\d+)\s+time=(?P<time>[\d.]+)s"
)
LEARNER_LINE = re.compile(
    r"^target=(?P<target>\S+)\s+algo=(?P<algo>\S+)\s+alphabet=(?P<alphabet>\d+)\s+"
    r"states=(?P<states>\d+)\s+time=(?P<time>[\d.]+)s"
)
AGREE_LINE = re.compile(
    r"^agree\s+target=(?P<target>\S+)\s+algo=(?P<algo>\S+)\s+ours_states=(?P<ours_states>\d+)\s+"
    r"learnlib_states=(?P<learnlib_states>\d+)\s+learnlib_queries=(?P<learnlib_queries>\d+)\s+"
    r"learnlib_time=(?P<learnlib_time>[\d.]+)s\s+agree=(?P<agree>true|false)"
)

# random_n<size>_a<alphabet>; the 3 hand-built targets (alternating, ends_in_01,
# mod3) don't match and are treated as alphabet=2 by convention (that's what
# they all are -- see Corpus.java / socket_teach.ml).
RANDOM_NAME = re.compile(r"random_n(?P<size>\d+)_a(?P<alphabet>\d+)")


def alphabet_of(target: str) -> int:
    m = RANDOM_NAME.match(target)
    return int(m.group("alphabet")) if m else 2


def parse_direction_a():
    """Returns one row per (algo, target): our own numbers plus LearnLib's,
    run locally against the same target right after, plus whether they agreed."""
    rows = []
    for algo in ALGOS:
        path = RESULTS_DIR / f"direction_a_{algo}_teacher.log"
        if not path.exists():
            continue
        ours = {}
        agreement = {}
        for line in path.read_text().splitlines():
            m = TEACHER_LINE.match(line)
            if m:
                ours[m["target"]] = m
                continue
            m = AGREE_LINE.match(line)
            if m:
                agreement[m["target"]] = m
        for target, m in ours.items():
            a = agreement.get(target)
            rows.append(
                {
                    "algo": algo,
                    "target": target,
                    "alphabet": alphabet_of(target),
                    "states": int(m["states"]),
                    "ours_queries": int(m["queries"]),
                    "ours_time": float(m["time"]),
                    "learnlib_states": int(a["learnlib_states"]) if a else None,
                    "learnlib_queries": int(a["learnlib_queries"]) if a else None,
                    "learnlib_time": float(a["learnlib_time"]) if a else None,
                    "agree": (a["agree"] == "true") if a else None,
                }
            )
    return pd.DataFrame(rows).sort_values(["algo", "states"])


def parse_direction_b():
    rows = []
    for algo in ALGOS:
        path = RESULTS_DIR / f"direction_b_{algo}_learner.log"
        if not path.exists():
            continue
        for line in path.read_text().splitlines():
            m = LEARNER_LINE.match(line)
            if m:
                rows.append(
                    {
                        "algo": algo,
                        "target": m["target"],
                        "alphabet": int(m["alphabet"]),
                        "states": int(m["states"]),
                        "time": float(m["time"]),
                    }
                )
    return pd.DataFrame(rows)


def plot_metric_vs_states(df: pd.DataFrame, ours_col: str, learnlib_col: str, ylabel: str, out_path: Path):
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5), sharey=True)
    for ax, alphabet in zip(axes, sorted(df["alphabet"].unique())):
        sub = df[df["alphabet"] == alphabet]
        for algo in ALGOS:
            s = sub[sub["algo"] == algo].sort_values("states")
            if s.empty:
                continue
            color = ALGO_COLORS[algo]
            ax.plot(s["states"], s[ours_col], "o-", color=color, label=f"{algo} (lstar-rocq)")
            ax.plot(s["states"], s[learnlib_col], "s--", color=color, alpha=0.6, label=f"{algo} (learnlib)")
        ax.set_xscale("log")
        ax.set_yscale("log")
        ax.set_xlabel("target states (minimal)")
        ax.set_title(f"alphabet size {alphabet}")
        ax.grid(True, which="both", linestyle=":", alpha=0.4)
    axes[0].set_ylabel(ylabel)
    axes[0].legend(fontsize=8, loc="upper left")
    fig.suptitle(f"{ylabel}: lstar-rocq (extracted) vs LearnLib's own implementation")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"wrote {out_path}")


def plot_query_overhead(df: pd.DataFrame, out_path: Path):
    d = df.dropna(subset=["learnlib_queries"]).copy()
    d["ratio"] = d["ours_queries"] / d["learnlib_queries"]

    fig, axes = plt.subplots(1, len(ALGOS), figsize=(14, 5), sharey=True)
    for ax, algo in zip(axes, ALGOS):
        sub = d[d["algo"] == algo].sort_values("states")
        ax.bar(range(len(sub)), sub["ratio"], color=ALGO_COLORS[algo])
        ax.axhline(1.0, color="black", linewidth=1, linestyle=":")
        ax.set_yscale("log")
        ax.set_xticks(range(len(sub)))
        ax.set_xticklabels(sub["target"], rotation=60, ha="right", fontsize=8)
        ax.set_title(algo)
        ax.grid(True, which="both", axis="y", linestyle=":", alpha=0.4)
    axes[0].set_ylabel("query count ratio (lstar-rocq / learnlib)")
    fig.suptitle("How many more membership+equivalence queries lstar-rocq needs vs LearnLib, per target")
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"wrote {out_path}")


def plot_direction_b_check(df: pd.DataFrame, out_path: Path):
    if df.empty:
        print("no direction B data to plot")
        return
    targets = sorted(df["target"].unique())
    fig, ax = plt.subplots(figsize=(7, 4.5))
    width = 0.25
    for i, algo in enumerate(ALGOS):
        sub = df[df["algo"] == algo].set_index("target").reindex(targets)
        offsets = [x + (i - 1) * width for x in range(len(targets))]
        ax.bar(offsets, sub["states"], width=width, label=algo, color=ALGO_COLORS[algo])
    ax.set_xticks(range(len(targets)))
    ax.set_xticklabels(targets)
    ax.set_ylabel("states LearnLib learned")
    ax.set_title("Direction B: LearnLib's own learners against our OCaml teacher")
    ax.legend()
    fig.tight_layout()
    fig.savefig(out_path, dpi=150)
    plt.close(fig)
    print(f"wrote {out_path}")


def main():
    a = parse_direction_a()
    b = parse_direction_b()

    if a.empty and b.empty:
        print("no log files found -- run ../run_all.sh first", file=sys.stderr)
        sys.exit(1)

    if not a.empty:
        a.to_csv(RESULTS_DIR / "direction_a_summary.csv", index=False)
        print(f"wrote {RESULTS_DIR / 'direction_a_summary.csv'}")
        print()
        print("== direction A: agreement between lstar-rocq and LearnLib's own implementation ==")
        print(a[["algo", "target", "states", "learnlib_states", "agree"]].to_string(index=False))
        if not a["agree"].all():
            print("\n!! at least one target disagreed -- see direction_a_summary.csv", file=sys.stderr)

        plot_metric_vs_states(a, "ours_queries", "learnlib_queries", "query count", RESULTS_DIR / "queries_vs_states.png")
        plot_metric_vs_states(a, "ours_time", "learnlib_time", "wall time (s)", RESULTS_DIR / "time_vs_states.png")
        plot_query_overhead(a, RESULTS_DIR / "query_overhead.png")

    if not b.empty:
        b.to_csv(RESULTS_DIR / "direction_b_summary.csv", index=False)
        print(f"wrote {RESULTS_DIR / 'direction_b_summary.csv'}")
        plot_direction_b_check(b, RESULTS_DIR / "direction_b_states.png")


if __name__ == "__main__":
    main()
