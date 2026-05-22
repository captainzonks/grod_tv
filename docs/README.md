# grod_tv Documentation

| Document                          | Audience                | Contents                                                                 |
| --------------------------------- | ----------------------- | ------------------------------------------------------------------------ |
| [`design.md`](design.md)          | Architects / reviewers  | Long-form architecture rationale: why ExoPlayer + MergingMediaSource replaces ffmpeg, scope cuts, non-goals |
| [`architecture.md`](architecture.md) | Contributors          | Module map, DI strategy, critical gotchas (`OkHttpDataSource`, Dispatchers.Main, AV1 filter) |
| [`api.md`](api.md)                | Remote app authors      | Full HTTP wire protocol — endpoints, request/response shapes, auth, CORS  |
| [`settings.md`](settings.md)      | End users + contributors| DataStore keys, Settings screen, how to add a new setting                |
| [`build.md`](build.md)            | New contributors        | Toolchain setup, build/install/test commands, CI sketch                  |
| [`emulator.md`](emulator.md)      | Linux developers        | Android TV emulator quirks (DNS, audio, AVD paths, mDNS limits)          |
| [`release.md`](release.md)        | Maintainers             | Tag-driven release workflow, signing config, keystore management, disaster recovery |

Start with [`design.md`](design.md) for the "why," [`architecture.md`](architecture.md) for the "how," and [`api.md`](api.md) for the "what does it speak."
