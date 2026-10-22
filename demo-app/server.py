#!/usr/bin/env python3
"""Runs the Brightcart Support Desk, an example application built on Continuum.

    python3 server.py

Needs Python 3.8+ and nothing else. Configure it first with
``python3 setup_continuum.py`` (see README.md).
"""
import sys

if sys.version_info < (3, 8):
    sys.exit("Python 3.8 or newer is needed.")

from brightcart import app, config  # noqa: E402

if __name__ == "__main__":
    app.serve(config.load())
