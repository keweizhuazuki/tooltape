# CI action maintenance

[English](CI_MAINTENANCE.md) | [简体中文](CI_MAINTENANCE.zh-CN.md)

Reviewed on 2026-10-10 after hosted runs warned about the Node 20 action runtime and deprecated setup-java v4. Application test versions remain Java 17/21 and Node 22; an action's internal runtime is separate from the language version it installs.

| Official release | Pinned commit |
| --- | --- |
| [checkout v7.0.1](https://github.com/actions/checkout/releases/tag/v7.0.1) | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| [setup-java v6.0.1](https://github.com/actions/setup-java/releases/tag/v6.0.1) | `de7274f081f381c8f8158605e0321c36c376e2e6` |
| [setup-node v7.1.0](https://github.com/actions/setup-node/releases/tag/v7.1.0) | `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| [upload-artifact v7.0.2](https://github.com/actions/upload-artifact/releases/tag/v7.0.2) | `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |
| [download-artifact v7.0.0](https://github.com/actions/download-artifact/releases/tag/v7.0.0) | `37930b1c2abaa49bbe596cd826c3c89aef350131` |

Release tags were resolved to commits through each official `actions/*` repository's Git refs API. Each pinned release's `action.yml` declares `node24`. Pins prevent a moving version tag from silently changing the executed code; they do not eliminate the need to review later releases.

## Compatibility and scope

- Hosted runners are used. Node 24 actions require a compatible runner; the [setup-node guidance](https://github.com/actions/setup-node#breaking-changes-in-v5) specifies at least 2.327.1.
- [Checkout v7](https://github.com/actions/checkout#whats-new) changes unsafe fork-checkout behavior for privileged triggers. Our workflows use ordinary `push`/`pull_request` (plus browser `workflow_dispatch`), not those privileged triggers. No unsafe opt-in is added.
- [Setup Java v6](https://github.com/actions/setup-java#v6) changes some publishing inputs and JDK verification behavior. These workflows use Temurin 17/21 and Maven caching, with no signing or publishing credentials.
- [Setup Node v7](https://github.com/actions/setup-node#whats-new-in-v7) removes a dummy registry token fallback. No registry authentication is configured here. Dependency-free viewer checks disable implicit package-manager caching; the browser job keeps its explicit npm lockfile cache.
- Artifact names, retention and scoped upload paths remain unchanged. Hidden files are not enabled. This is artifact upload for tests/builds, not a deployment.
- Workflow permission remains `contents: read`. Every checkout uses `persist-credentials: false`; no secrets, new tokens or write permissions are introduced.

## Future updates

1. Inspect the official release notes and resolve the exact tag to its commit; never copy a SHA from an unrelated repository or third-party snippet.
2. Check `action.yml`, runner requirements, inputs and cache/artifact behavior. Preserve least privilege and explicit upload paths.
3. Update the full SHA and human-readable version comment together.
4. Run all OS/JDK jobs, Maven tests, viewer tests and browser checks before merging. Verify the merged main run and download a checksummed JAR artifact afterward.

These steps are maintenance guidance, not a promise of automatic dependency updates or compatibility with self-hosted runners/GitHub Enterprise Server.

The optional Jupiter compatibility workflow adds pinned download-artifact only for checksummed build inputs from the same run, not other repositories or runs. Its official action.yml uses Node 24 and requires runner 2.327.1 or newer. Consumer jobs have no checkout and use independent temporary directories and Maven repositories.
