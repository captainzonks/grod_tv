# grod-tv

Android TV companion app for [grod](https://github.com/captainzonks/grod).

**Status:** Design phase. No code yet.

Read [`docs/design.md`](docs/design.md) for the full architecture spec.

## Goal

A standalone Android TV app that replaces the laptop-hosted grod daemon. The TV
itself becomes the queue manager, Piped resolver, and media player — no laptop
needed. The existing `grod_remote` Flutter phone app talks to it unchanged via
the same HTTP API and mDNS discovery.

## Companion repos

- [`grod`](https://github.com/captainzonks/grod) — Rust CLI + laptop daemon
- `grod_remote` — Flutter phone remote app
