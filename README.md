# Media Factory

Spring Boot app that:
- accepts uploads for basic and composition workflows
- generates MP4 output with FFmpeg
- uploads result to YouTube
- returns async `jobId` and supports polling with `GET /api/video/status/{jobId}`

## Prerequisites

- Java 17
- FFmpeg installed
- Google Cloud OAuth client credentials for YouTube Data API v3

## Configuration

The app supports these environment variables:

- `FFMPEG_PATH` (optional): absolute path to ffmpeg executable. If unset, app uses `ffmpeg` from `PATH`.
- `YOUTUBE_CREDENTIALS_PATH` (optional): path to OAuth client credentials JSON. If unset, app expects `secrets/credentials.json`.

Important server setting:
- `server.tomcat.max-part-count=100` is configured in `application.properties` to allow multipart requests with publishing fields (including tags and thumbnail).

Startup preflight checks verify FFmpeg and credentials path. If either is invalid, startup fails fast with a clear error.

## OAuth Setup

1. In Google Cloud Console, enable **YouTube Data API v3**.
2. Create OAuth client credentials (Desktop App or Web App).
3. Download the JSON and either:
   - save as `secrets/credentials.json`, or
   - set `YOUTUBE_CREDENTIALS_PATH` to its location.
4. If using a Web App OAuth client, include redirect URI:
   - `http://localhost:8888/oauth2callback`

## Run

```powershell
.\mvnw.cmd spring-boot:run
```

On first upload, a browser consent flow is triggered. Tokens are stored under `.youtube-tokens/`.

## API

### Legacy Basic Workflow (still supported)

`POST /api/video/generate`

Content-Type: `multipart/form-data`

Required fields:
- `image` (file, `image/*`)
- `audio` (file, `audio/*`)
- `duration` (integer seconds, `1` to `36000`)
- `title` (max `100` chars)
- `description` (max `5000` chars)

Optional publishing fields:
- `privacyStatus`: `PRIVATE | UNLISTED | PUBLIC` (case-insensitive, default `PRIVATE`)
- `tags`: repeated field (`-F "tags=tag1" -F "tags=tag2"`), max 20 unique tags, each max 50 chars
- `categoryId`: numeric string matching `^\d{1,3}$`
- `publishAt`: ISO-8601 UTC instant ending with `Z` (example `2026-02-20T18:30:00Z`), must be at least 5 minutes in future
- `thumbnail`: optional image file (`image/jpeg` or `image/png`)

### New Composition Workflow

`POST /api/video/compositions`

Content-Type: `multipart/form-data`

Required fields:
- `manifest` (JSON string)
- `audio` (file, `audio/*`) as single master audio track
- `title` (max `100` chars)
- `description` (max `5000` chars)
- one file part for each scene asset reference: `asset.<assetId>`

Optional publishing fields:
- `privacyStatus`, `tags`, `categoryId`, `publishAt`, `thumbnail` (same rules as `/api/video/generate`)

Manifest schema:
- `outputPreset` required: `LANDSCAPE_16_9 | PORTRAIT_9_16 | SQUARE_1_1`
- `scenes` required: min 1, max 50
- each scene requires: `assetId`, `type`
- `type` values: `IMAGE | VIDEO`
- `IMAGE` scenes require `durationSec` (`0.5` to `600`)
- `VIDEO` scenes support `clipStartSec` (default `0`) and optional `clipDurationSec` (`>0`)
- optional `motion`: `NONE | ZOOM_IN | ZOOM_OUT | PAN_LEFT | PAN_RIGHT`
- optional `visualEdit`:
  - `filter`: `NONE | GRAYSCALE | SEPIA | COOL | WARM`
  - `colorGrade`:
    - `brightness`: `-1.0` to `1.0` (default `0.0`)
    - `contrast`: `0.2` to `3.0` (default `1.0`)
    - `saturation`: `0.0` to `3.0` (default `1.0`)
  - optional `overlay`:
    - `hexColor`: `#RRGGBB`
    - `opacity`: `0.0` to `1.0` (default `0.25`, values `<=0` disable overlay)
- optional `caption`:
  - `text`
  - `startOffsetSec`
  - `endOffsetSec`
  - `position`: `TOP | CENTER | BOTTOM`
- optional `transition`:
  - `type`: `CUT | CROSSFADE`
  - `transitionDurationSec` required only for `CROSSFADE` (`0.2` to `2.0`)
- first scene transition must be `CUT`
- total timeline duration must be `<= 36000` seconds

Output preset mapping:
- `LANDSCAPE_16_9` -> `1920x1080`
- `PORTRAIT_9_16` -> `1080x1920`
- `SQUARE_1_1` -> `1080x1080`

Validation rules:
- missing referenced `asset.<assetId>` returns `400 Bad Request`
- unknown extra asset parts are ignored
- `publishAt` requires `privacyStatus=PRIVATE`

Successful response for both submit endpoints: `202 Accepted`

```json
{
  "jobId": "2f8360f0-7bfd-40f3-9a3a-ce1e5f0f3a21",
  "state": "QUEUED",
  "message": "Video job accepted. Poll /api/video/status/{jobId} for progress."
}
```

### Check Job Status

`GET /api/video/status/{jobId}`

Successful response: `200 OK`

```json
{
  "jobId": "2f8360f0-7bfd-40f3-9a3a-ce1e5f0f3a21",
  "state": "COMPLETED",
  "message": "Video generated and uploaded successfully.",
  "createdAt": "2026-02-12T18:25:43.129Z",
  "updatedAt": "2026-02-12T18:26:07.984Z",
  "privacyStatus": "PRIVATE",
  "tags": ["music", "ambient"],
  "categoryId": "10",
  "publishAt": "2026-02-20T18:30:00Z",
  "youtubeVideoId": "abc123xyz",
  "youtubeVideoUrl": "https://www.youtube.com/watch?v=abc123xyz",
  "warningMessage": null
}
```

States: `QUEUED`, `PROCESSING`, `COMPLETED`, `FAILED`

## cURL Examples

### Basic upload (legacy endpoint)

```bash
curl -X POST "http://localhost:8080/api/video/generate" \
  -F "image=@/path/to/image.jpg" \
  -F "audio=@/path/to/audio.mp3" \
  -F "duration=60" \
  -F "title=My Test Video" \
  -F "description=Generated by Media Factory" \
  -F "privacyStatus=UNLISTED" \
  -F "tags=music" \
  -F "tags=chill" \
  -F "categoryId=10" \
  -F "thumbnail=@/path/to/thumb.jpg"
```

### Composition upload (new endpoint)

```bash
curl -X POST "http://localhost:8080/api/video/compositions" \
  -F 'manifest={
    "outputPreset":"PORTRAIT_9_16",
    "scenes":[
      {
        "assetId":"scene-1",
        "type":"IMAGE",
        "durationSec":3,
        "motion":"ZOOM_IN",
        "visualEdit":{
          "filter":"WARM",
          "colorGrade":{"brightness":0.08,"contrast":1.15,"saturation":1.2},
          "overlay":{"hexColor":"#2E1C12","opacity":0.12}
        },
        "transition":{"type":"CUT"},
        "caption":{"text":"Intro","startOffsetSec":0,"endOffsetSec":2,"position":"BOTTOM"}
      },
      {
        "assetId":"scene-2",
        "type":"VIDEO",
        "clipStartSec":1.0,
        "clipDurationSec":4.0,
        "transition":{"type":"CROSSFADE","transitionDurationSec":0.5}
      }
    ]
  }' \
  -F "asset.scene-1=@/path/to/cover.jpg" \
  -F "asset.scene-2=@/path/to/clip.mp4" \
  -F "audio=@/path/to/music.mp3" \
  -F "title=Composition Demo" \
  -F "description=Mixed media timeline" \
  -F "privacyStatus=PRIVATE"
```

Then poll:

```bash
curl "http://localhost:8080/api/video/status/<jobId>"
```

## Current Composition Limits

- One master audio track per job (`audio` field)
- No scene-level audio mixing in this phase
- Overlay currently supports full-frame color tint only (no per-scene image watermark layer yet)
- Jobs are stored in memory (not persisted)

## Tests

```powershell
.\mvnw.cmd test
```

`src/test/resources/application.properties` disables startup preflight checks to keep tests isolated from local FFmpeg/credential setup.
