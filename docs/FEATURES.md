# WebUI parity checklist

The first release is a complete vertical workflow. The remaining WebUI features will be added in later milestones rather than represented by empty buttons.

| Feature | First-release implementation |
| --- | --- |
| Server sign-in and saved connection | Implemented; one server, LAN/VPN HTTP or HTTPS |
| Unrecognized file search, pagination, selection | Implemented |
| Server-side AVDump and live progress | Implemented, with REST reconciliation and explicit unknown outcomes |
| AniDB anime selection | Implemented; filename-derived title autofill and automatic search, with manual title/ID entry |
| Native Mass Add common fields | Implemented; values/choices read from current form |
| Advanced video/audio/subtitle options | Implemented; live options, repeatable tracks |
| Phone templates and defaults | Implemented; separate from desktop browser cookies |
| Native per-file episode/version/date review | Implemented after website Next |
| Official AniDB final submit | User-operated in WebView; live acceptance requires validation |
| Draft and interrupted-job recovery | Implemented; no automatic replay of writes |
| Shoko recognition rechecks | Implemented; separate from reported submission |
| Multiple saved servers | Later |
| Manual linking and unlinking | Next milestone |
| Ignored files and restore | Next milestone |
| Dashboard and collection browsing | Later |
| Series/episode details and watch-state management | Later |
| Import-folder management and other file utilities | Later |
| Server queue, logs, settings, and maintenance | Later |

Before each later milestone, compare the installed stable WebUI's current routes/actions to this checklist and split features into independently testable workflows. Development-server APIs require a separate compatibility adapter; this release uses stable 5.3.3 contracts.
