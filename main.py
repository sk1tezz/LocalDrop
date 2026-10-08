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
    from pathlib import Path

    from localdrop import config
    from localdrop.system.single_instance import forward

    request = {"send": [str(Path(p).resolve()) for p in args.send]} if args.send else {"show": True}
    if forward(config.data_dir(args.profile), request):
        return 0

    from localdrop.app import App

    app = App(profile=args.profile, port=args.port, debug=args.debug)
    if args.send:
        app.pending_request = request
    app.run(start_hidden=args.hidden and not args.send)
    return 0


if __name__ == "__main__":
    sys.exit(main())
