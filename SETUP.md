# GitHub Repository Setup Guide

This guide explains how to set up this Anna's Archive extension as a GitHub repository that can be used with Yokai.

## Quick Setup

1. **Create GitHub Repository**
   - Go to GitHub and create a new repository
   - Name it `annas-archive-yokai-extension` 
   - Make it public
   - Don't initialize with README (we have our own)

2. **Upload Files**
   - Upload all files from this directory to your GitHub repository
   - Make sure `index.min.json` is in the root directory

3. **Enable GitHub Pages** (Optional but recommended)
   - Go to Repository Settings → Pages
   - Set source to "Deploy from a branch"  
   - Select `main` branch and `/ (root)` folder
   - This will make your extension accessible via HTTPS

4. **Add to Yokai**
   - Copy this URL: `https://raw.githubusercontent.com/YOUR_USERNAME/annas-archive-yokai-extension/main/index.min.json`
   - Replace `YOUR_USERNAME` with your actual GitHub username
   - In Yokai: Extensions → Repository settings → Add repository URL

## Repository Structure

```
annas-archive-yokai-extension/
├── .github/
│   └── workflows/
│       └── build.yml              # GitHub Actions build workflow
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── src/
│   └── main/
│       ├── AndroidManifest.xml
│       └── java/
│           └── eu/kanade/tachiyomi/extension/all/annasarchive/
│               ├── AnnasArchiveFactory.kt
│               └── AnnasArchiveSource.kt
├── .gitignore                     # Git ignore file
├── build.gradle.kts              # Build configuration
├── gradlew                       # Gradle wrapper (Unix)
├── gradlew.bat                   # Gradle wrapper (Windows)
├── index.min.json                # Extension repository index (REQUIRED)
├── LICENSE                       # Apache 2.0 license
├── README.md                     # Extension documentation
├── REPOSITORY.md                 # Repository setup guide
└── settings.gradle.kts           # Gradle settings
```

## Key Files Explained

### `index.min.json`
This is the **most important file**. It tells Yokai about your extension:
- Must be in the root directory
- Contains extension metadata (name, version, download URL)
- Must be accessible via raw GitHub URL

### `AnnasArchiveSource.kt`
The main extension code that:
- Implements `BookCatalogueSource` interface
- Handles searching Anna's Archive
- Parses book results and details
- Provides download links

### GitHub Actions Workflow
The `.github/workflows/build.yml` file:
- Automatically builds APK when you push changes
- Creates releases when you tag versions
- Updates `index.min.json` with new versions

## Building the Extension

### Local Build (Advanced)
If you have Android development environment:
```bash
./gradlew assembleRelease
```

### GitHub Actions Build (Recommended)
1. Push changes to GitHub
2. Create a tag: `git tag v1.0.0`
3. Push tag: `git push origin v1.0.0`
4. GitHub Actions will build and create a release

## Using the Repository

Once set up, users can add your repository to Yokai:

1. **Repository URL**: `https://raw.githubusercontent.com/YOUR_USERNAME/annas-archive-yokai-extension/main/index.min.json`
2. In Yokai: Extensions → Repository settings → Add this URL
3. The extension will appear in their Extensions list
4. They can install it directly from Yokai

## Updating the Extension

To release a new version:

1. **Update Code**: Make changes to `AnnasArchiveSource.kt`
2. **Update Version**: Edit `build.gradle.kts` to increment `versionName` and `versionCode`
3. **Update Index**: Edit `index.min.json` to match new version
4. **Commit & Tag**: 
   ```bash
   git add .
   git commit -m "Update to v1.1.0"
   git tag v1.1.0
   git push origin main
   git push origin v1.1.0
   ```
5. **GitHub Actions**: Will automatically build and release

## Troubleshooting

### Extension Not Appearing
- Check that `index.min.json` is accessible via raw GitHub URL
- Verify JSON syntax is valid
- Ensure repository is public

### Build Failures
- Check GitHub Actions logs
- Ensure all required files are present
- Verify Kotlin syntax in source files

### Users Can't Install
- Make sure APK is properly built and uploaded
- Check that download URLs in `index.min.json` are correct
- Verify extension is compatible with user's Yokai version

## Security Notes

- Never commit signing keys or sensitive data
- Use GitHub Secrets for any sensitive build information
- Keep repository public for Yokai compatibility

## Example Repository URL

After setup, your repository URL will be:
`https://raw.githubusercontent.com/YOUR_USERNAME/annas-archive-yokai-extension/main/index.min.json`

Replace `YOUR_USERNAME` with your actual GitHub username.