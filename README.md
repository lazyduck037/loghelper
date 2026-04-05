# LogHelper
VibeCode - A lightweight, file-based Android logger with SLF4J-style `{}` placeholders, automatic file rotation, and remote enable/disable config.

## Features

- **SLF4J-style placeholders** — `{}` replaced in order, supports Collections, Maps, Arrays
- **FIFO ordering** — `Channel.UNLIMITED` + single writer coroutine, no log loss
- **Batched I/O** — entries accumulate in memory, one write per 1 KB batch
- **File rotation** — 5 MB per file, suffixed files on overflow (`mail_log_YYYYMMDD_1.log`, `_2.log`, …)
- **Daily rollover** — new file each day, 3-day retention (old files auto-deleted at startup)
- **Remote config** — fetch `{ "enabled": true }` from any URL to toggle logging remotely
- **Thread-safe** — `@Volatile isEnabled` + lock-free channel send
- **Zip logs** — pack all log files into a single zip for export/share

## Log Format

```
2024-03-22 10:30:01.123 [tid=12] D/TAG: message
```

---

## Setup

### 1. Add GitHub Packages repository

In your project `settings.gradle`:

```groovy
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("https://maven.pkg.github.com/lazyduck037/loghelper")
            credentials {
                username = findProperty("GITHUB_ACTOR") ?: System.getenv("GITHUB_ACTOR")
                password = findProperty("GITHUB_TOKEN") ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

Add to your `local.properties` (never commit this file):

```properties
GITHUB_ACTOR=your_github_username
GITHUB_TOKEN=ghp_your_personal_access_token
```

> The token needs the `read:packages` scope.

### 2. Add the dependency

```groovy
implementation "com.lazyduck:loghelper:1.0.1"
```

### 3. Initialize (Koin)

```kotlin
// Application.kt
val logModule = module {
    single {
        LogConfigManager(
            context = androidContext(),
            httpClient = OkHttpClient()
        )
    }
    single {
        LogHelper(
            context = androidContext(),
            configManager = get()
        )
    }
}

class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MyApp)
            modules(logModule)
        }

        // Enable logging (e.g. BuildConfig.DEBUG or a feature flag)
        LogHelper.setEnabled(BuildConfig.DEBUG)
    }

    override fun onTerminate() {
        super.onTerminate()
        LogHelper.close() // flushes remaining buffer
    }
}
```

### 4. Initialize (without Koin)

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val configManager = LogConfigManager(this, OkHttpClient())
        LogHelper(this, configManager).init(isEnable = BuildConfig.DEBUG)
    }
}
```

---

## Usage

```kotlin
// Simple message
LogHelper.d("User logged in")
LogHelper.i("Request started")
LogHelper.w("Retry attempt")
LogHelper.e("Upload failed")

// Explicit tag
LogHelper.d("Auth", "Token refreshed")
LogHelper.e("Network", "Timeout after {} ms", 5000)

// {} placeholders (SLF4J-style)
LogHelper.d("UserRepo", "Fetched {} users in {} ms", users.size, elapsed)
LogHelper.i("Cart", "Items: {}", listOf("apple", "banana"))
LogHelper.e("Api", "Request {} failed: {}", requestId, exception)

// Throwable as last arg → captured as cause
LogHelper.e("Upload", "Failed for id {}", fileId, IOException("disk full"))
```

### Control

```kotlin
// Toggle at runtime
LogHelper.setEnabled(true)
LogHelper.setEnabled(false)

// Sync enabled state from a remote endpoint
// Expects: { "enabled": true }
lifecycleScope.launch {
    LogHelper.syncRemoteConfig("https://your-host/log-config")
}

// Check current state
if (LogHelper.isEnabled()) { ... }
```

### Export logs

```kotlin
// Zip all log files and share
LogHelper.zipLogs { uri ->
    if (uri != null) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        startActivity(Intent.createChooser(intent, "Share logs"))
    }
}

// Clear all log files
LogHelper.clearLogFiles()
```

---

## Log files

Files are stored in the app's internal storage under `files/mail_logs/`:

```
mail_log_20240322.log          ← current day
mail_log_20240322_1.log        ← rotation (5 MB exceeded)
mail_log_20240321.log          ← yesterday
```

- Max **5 MB** per file before rotation
- Files older than **3 days** are deleted automatically on next app start

---

## Publishing (maintainers)

```bash
# Publish to GitHub Packages
./gradlew :loghelper:publishReleasePublicationToGitHubPackagesRepository

# Publish to local Maven (~/.m2) for local testing
./gradlew :loghelper:publishToMavenLocal
```

Requires `GITHUB_ACTOR` and `GITHUB_TOKEN` set in `local.properties` or environment variables.

---

## License

```
Copyright 2024 GHN

Licensed under the Apache License, Version 2.0
```
