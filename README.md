# Anna's Archive Yokai Extension

A Tachiyomi/Yokai extension for accessing books from Anna's Archive.

## About Anna's Archive

Anna's Archive is a comprehensive shadow library that aggregates books, papers, comics, magazines, and other documents from various sources including Library Genesis, Z-Library, Internet Archive, and others.

## Features

- Search books by title, author, ISBN
- Filter by file format (PDF, EPUB, MOBI, etc.)
- Access book metadata and descriptions
- Direct download links to available mirrors
- Latest additions browsing

## Installation

1. Download the latest `.apk` file from the releases
2. Install it in Tachiyomi/Yokai through Extensions → Install APK
3. Enable the "Anna's Archive" source in your library

## Building

This extension uses Gradle for building:

```bash
./gradlew assembleRelease
```

The built APK will be available in `build/outputs/apk/release/`

## Repository Structure

- `src/main/java/` - Kotlin source code
- `src/main/AndroidManifest.xml` - Android manifest
- `build.gradle.kts` - Build configuration

## API Integration

This extension integrates with Anna's Archive's search API:
- Base URL: `https://annas-archive.li`
- Search endpoint: `/search`
- Book details: `/md5/{hash}`

## Disclaimer

This extension is for educational purposes only. Users are responsible for complying with their local laws regarding accessing copyrighted content.

## Contributing

Pull requests and issues are welcome. Please ensure your code follows the existing style and includes appropriate error handling.