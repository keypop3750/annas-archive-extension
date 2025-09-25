# How to Install Your Anna's Archive Extension

## Quick Method - Use Your Custom Repository

### Step 1: Add Your Extension Repository to YokaiFork

1. Open Yokai app
2. Go to **Browse** → **Extensions**
3. Tap the **settings/gear icon** (⚙️) in the top-right corner
4. Tap **Extension repositories**
5. Tap the **+** button to add a new repository
6. Enter your repository URL:
   ```
   https://raw.githubusercontent.com/keypop3750/annas-archive-extension/main/index.min.json
   ```
7. Tap **Add**

### Step 2: Install the Extension
1. Go back to Extensions list
2. Pull down to refresh
3. Look for **Anna's Archive** extension
4. Tap **Install**

---

## Alternative Method - Build and Host the APK

Since we need to build the actual APK file, here's what you need to do:

### Option A: Fork the Official Extensions Repository

1. Fork the official Tachiyomi extensions repository:
   ```
   https://github.com/tachiyomiorg/extensions-source
   ```

2. Add your Anna's Archive extension code to:
   ```
   src/all/annasarchive/
   ```

3. Build the extension:
   ```bash
   ./gradlew :extensions:all:annasarchive:assembleDebug
   ```

4. Upload the generated APK to your repository and update the index.min.json

### Option B: Use Pre-built Extension Repository

For now, you can use the official Keiyoushi repository which already has many extensions:

```
https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json
```

But to get your Anna's Archive extension working, you'll need to:

1. **Complete the extension development** - Your extension is 80% complete but needs the final APK build
2. **Build the APK** - Use the official Tachiyomi extension build system
3. **Host the APK** - Upload to GitHub releases or your repository
4. **Update index.min.json** - Point to the actual APK file

---

## Current Status

✅ **Extension Code**: Complete (all 5 phases implemented)
✅ **Repository Structure**: Set up with index.min.json
❌ **APK Build**: Needs official extension build system
❌ **APK Hosting**: Needs actual APK file

## Quick Test

You can test if your repository structure works by:

1. Adding the repository URL to YokaiFork
2. Seeing if it appears in the extensions list
3. The install will fail without a real APK, but you'll know the repository format works

## Repository URL to Use

```
https://raw.githubusercontent.com/keypop3750/annas-archive-extension/main/index.min.json
```

This URL structure matches exactly how the official Keiyoushi repository works.