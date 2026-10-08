"""LocalDrop entry point."""
from __future__ import annotations

import argparse
import sys


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="LocalDrop")
    p.add_argument("--profile", help="separate data directory (for running two instances on one machine)")
    p.add_argument("--port", type=int, help="TCP/UDP port (default 53317)")
    p.add_argument("--hidden", action="store_true", help="start minimized to tray")
    p.add_argument("--debug", action="store_true")
    p.add_argument("--send", nargs="+", metavar="PATH", help="send files with LocalDrop")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    from localdrop.app import App

    app = App(profile=args.profile, port=args.port, debug=args.debug)
    app.run(start_hidden=args.hidden)
    return 0


if __name__ == "__main__":
    sys.exit(main())
