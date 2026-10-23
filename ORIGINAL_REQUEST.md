# Original User Request

## 2026-10-22T20:18:43Z

An Android music application (YRT) with artist and album search, dedicated artist/album profile pages, one-click bulk downloading of discographies/albums, and a modernized streaming-style frontend UI.

Working directory: `c:\Users\Mucahit\Documents\YRT`
Integrity mode: development

## Requirements

### R1. Artist & Album Search and Discovery
Users can type an artist name in a search bar to find matching artist profiles. Tapping an artist opens a dedicated artist screen that presents their top songs, albums, and discography fetched via YouTube / YouTube Music.

### R2. Album Details & Song Exploration
Users can view albums separately or browse an artist's albums in a dedicated grid/list view. Selecting an album displays its tracklist, artwork, release metadata, and allows playback or downloading.

### R3. One-Click Bulk Song & Album Downloading
Users can download all songs of an artist with a single click, or download an entire album at once. Songs are queued into the existing background download manager with real-time individual and aggregate progress tracking.

### R4. Modern Streaming Frontend UI
Upgrade the application UI to a modern streaming design (YouTube Music / Spotify aesthetic). Include dynamic artwork hero headers, modern card grids for albums, clean track rows with action menus, and a refined mini-player / full player interface.

## Acceptance Criteria

### Search & Discovery
- [ ] Searching for an artist query returns artist results with avatar/thumbnail and title.
- [ ] Selecting an artist navigates to an Artist Profile displaying top songs and a list/grid of albums.
- [ ] Selecting an album opens an Album View displaying the full album tracklist with durations.

### Bulk Downloading
- [ ] The artist screen provides a "Download All Songs" action that enqueues all discovered tracks into the download manager.
- [ ] The album screen provides a "Download Album" action that enqueues all album tracks into the download manager.
- [ ] Queued tracks download sequentially or with throttled concurrency without freezing the UI.
- [ ] Download progress, completion, and failure states are accurately reflected in the UI and downloaded files appear in the local library.

### Frontend Quality & Build Integrity
- [ ] The project builds successfully with `./gradlew assembleDebug`.
- [ ] All unit and verification tests pass via `./gradlew test`.
- [ ] Existing local music playback and download functionality continue to operate without regression.

## 2026-10-22T21:16:14Z

An Android music application (YRT) with artist and album search, dedicated artist/album profile pages, one-click bulk downloading of discographies/albums, and a modernized streaming-style frontend UI.

Working directory: `c:\Users\Mucahit\Documents\YRT`
Integrity mode: development

Note on progress: Milestone 1 (YouTube Music InnerTube network client `YouTubeMusicClient`, data models `RemoteModels.kt`, repository `YouTubeMusicRepository`, and `SongRepository` videoId indexing) is already implemented, verified, and passing unit tests. Proceed with Milestone 2 (Bulk Download Engine & Concurrency Subsystem), Milestone 3 (Search, Artist Profile, Album View, and Modern Streaming UI Redesign in Jetpack Compose), and final end-to-end integration.

## Requirements

### R1. Artist & Album Search and Discovery
Users can type an artist name in a search bar to find matching artist profiles. Tapping an artist opens a dedicated artist screen that presents their top songs, albums, and discography fetched via YouTube / YouTube Music.

### R2. Album Details & Song Exploration
Users can view albums separately or browse an artist's albums in a dedicated grid/list view. Selecting an album displays its tracklist, artwork, release metadata, and allows playback or downloading.

### R3. One-Click Bulk Song & Album Downloading
Users can download all songs of an artist with a single click, or download an entire album at once. Songs are queued into the existing background download manager with real-time individual and aggregate progress tracking.

### R4. Modern Streaming Frontend UI
Upgrade the application UI to a modern streaming design (YouTube Music / Spotify aesthetic). Include dynamic artwork hero headers, modern card grids for albums, clean track rows with action menus, and a refined mini-player / full player interface.

## Acceptance Criteria

### Search & Discovery
- [ ] Searching for an artist query returns artist results with avatar/thumbnail and title.
- [ ] Selecting an artist navigates to an Artist Profile displaying top songs and a list/grid of albums.
- [ ] Selecting an album opens an Album View displaying the full album tracklist with durations.

### Bulk Downloading
- [ ] The artist screen provides a "Download All Songs" action that enqueues all discovered tracks into the download manager.
- [ ] The album screen provides a "Download Album" action that enqueues all album tracks into the download manager.
- [ ] Queued tracks download sequentially or with throttled concurrency without freezing the UI.
- [ ] Download progress, completion, and failure states are accurately reflected in the UI and downloaded files appear in the local library.

### Frontend Quality & Build Integrity
- [ ] The project builds successfully with `./gradlew assembleDebug`.
- [ ] All unit and verification tests pass via `./gradlew test`.
- [ ] Existing local music playback and download functionality continue to operate without regression.
