# Word card pictures (Pixabay)

## Where they appear
- `ui/WordImageView` shows the picture on the flashcard once the meaning is visible (not for phrases).
- Under the picture, «Pixabay · author» links to the page.
- `ReviewViewModel` prefetches pictures for the next 5 words.

## Components (`images/`)
- **`PixabayApi`:**
  - The key goes in the query string and is redacted in logs.
  - 400 → no retry; 429 → wait for `X-RateLimit-Reset`; 5xx or offline → 3 retries.
- **`RateLimiter`:** at most 90 requests per minute.
- **`ImageDownloader`:** saves to `filesDir/word_images/`. Accepts `image/*` only, ≤ 5 MB; writes a tmp file, then renames it.
- **`WordImageRepository`:**
  - Room `word_images` → file.
  - Search order: photo → illustration → search by the Russian translation → `not_found`, retried after 7 days.
  - Candidates are kept 24 h for «Другая картинка».
  - «Убрать картинку» marks the word `disabled`.
  - One in-flight load per word.

## Key and backup
- **Key:** `PIXABAY_API_KEY` in `android/local.properties` (gitignored) locally, and the `PIXABAY_API_KEY` secret in CI. It ends up in `BuildConfig`, so it is readable from the public APK.
- **Empty key:** works; cards say «Не задан PIXABAY_API_KEY в local.properties».
- **Backup:** `word_images` is a cache and is not in the backup.

## Tests
`WordImagesTest` covers normalisation, `PixabayApi` on MockWebServer, and the repository fallbacks and cache.
