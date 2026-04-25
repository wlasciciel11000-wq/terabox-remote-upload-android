# TeraBox Remote Upload Error -1 Analysis and Fix

## Problem Diagnosis

The Android application is receiving **error -1 (unknown error)** when attempting to add a remote upload task via the TeraBox API endpoint:

```
https://www.terabox.com/rest/2.0/services/cloud_dl?method=add_task
```

### Root Causes Identified:

1. **Incorrect API Endpoint**: The application uses `www.terabox.com` but newer API versions require `1024terabox.com` (as seen in Pahadi10/terabox-upload-tool).

2. **Missing Required Parameters**: The current implementation is missing critical parameters:
   - `dp-logid`: Dynamic parameter for logging/tracking
   - `clienttype`: Should be `5` for remote operations (not `0`)
   - `t` (timestamp): Added but may need better formatting

3. **Incorrect URL Structure**: The API expects parameters in the query string, not just in the POST body.

4. **Missing or Incorrect Headers**: Some headers may be missing or incorrectly formatted.

5. **Cookie Issues**: The `jsToken` extraction may be failing or the token may have expired.

## Solution Based on TeraboxUploaderCLI Analysis

The Pahadi10/terabox-upload-tool library uses:
- Base URL: `https://www.1024terabox.com` (not `www.terabox.com`)
- Proper parameter encoding in query strings
- Correct `clienttype` values for different operations
- Proper `dp-logid` generation

## Required Changes to MainActivity.java

### Change 1: Update API Endpoint and Parameters

**Current Code (Lines 191-197):**
```java
long timestamp = System.currentTimeMillis();
String apiUrl = "https://www.terabox.com/rest/2.0/services/cloud_dl?method=add_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=0"
        + "&jsToken=" + jsToken
        + "&t=" + timestamp;
```

**Fixed Code:**
```java
// Generate dp-logid similar to TeraboxUploaderCLI
String dpLogId = generateDpLogId();

String apiUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=add_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=5"  // Changed from 0 to 5 for remote operations
        + "&jsToken=" + jsToken
        + "&dp-logid=" + dpLogId;  // Added dp-logid
```

### Change 2: Add dp-logid Generation Method

Add this method to the MainActivity class:

```java
private String generateDpLogId() {
    // Generate a random hex string similar to TeraboxUploaderCLI
    Random random = new Random();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
        sb.append(String.format("%02x", random.nextInt(256)));
    }
    return sb.toString().toUpperCase();
}
```

### Change 3: Update Form Body Parameters

**Current Code (Lines 201-204):**
```java
FormBody formBody = new FormBody.Builder()
        .add("save_path", "/")
        .add("source_url", url)
        .build();
```

**Fixed Code:**
```java
FormBody formBody = new FormBody.Builder()
        .add("source_url", url)  // source_url should be first
        .add("save_path", "/")
        .build();
```

### Change 4: Update Headers

**Current Code (Lines 206-214):**
```java
Request request = new Request.Builder()
        .url(apiUrl)
        .addHeader("Cookie", allCookies)
        .addHeader("User-Agent", USER_AGENT)
        .addHeader("Referer", "https://www.terabox.com/main")
        .addHeader("Origin", "https://www.terabox.com")
        .addHeader("X-Requested-With", "XMLHttpRequest")
        .post(formBody)
        .build();
```

**Fixed Code:**
```java
Request request = new Request.Builder()
        .url(apiUrl)
        .addHeader("Cookie", allCookies)
        .addHeader("User-Agent", USER_AGENT)
        .addHeader("Referer", "https://www.1024terabox.com/main")
        .addHeader("Origin", "https://www.1024terabox.com")
        .addHeader("Content-Type", "application/x-www-form-urlencoded")
        .post(formBody)
        .build();
```

### Change 5: Update fetchTaskList() Method

**Current Code (Lines 246-254):**
```java
String listUrl = "https://www.terabox.com/rest/2.0/services/cloud_dl?method=list_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=5"
        + "&jsToken=" + jsToken
        + "&need_report=1"
        + "&num=100"
        + "&page=1";
```

**Fixed Code:**
```java
String dpLogId = generateDpLogId();
String listUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=list_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=5"
        + "&jsToken=" + jsToken
        + "&dp-logid=" + dpLogId
        + "&need_report=1"
        + "&num=100"
        + "&page=1";
```

### Change 6: Update deleteTask() Method

**Current Code (Lines 305-310):**
```java
String delUrl = "https://www.terabox.com/rest/2.0/services/cloud_dl?method=cancel_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=0"
        + "&jsToken=" + jsToken;
```

**Fixed Code:**
```java
String dpLogId = generateDpLogId();
String delUrl = "https://www.1024terabox.com/rest/2.0/services/cloud_dl?method=cancel_task"
        + "&app_id=" + APP_ID 
        + "&web=1" 
        + "&channel=dubox" 
        + "&clienttype=5"
        + "&jsToken=" + jsToken
        + "&dp-logid=" + dpLogId;
```

## Summary of Changes

1. ✅ Changed base URL from `www.terabox.com` to `www.1024terabox.com`
2. ✅ Added `dp-logid` parameter generation
3. ✅ Updated `clienttype` from `0` to `5` for remote operations
4. ✅ Fixed header references to use new domain
5. ✅ Added `Content-Type` header for POST requests
6. ✅ Ensured `source_url` is the first parameter in FormBody
7. ✅ Applied fixes to all API endpoints (add_task, list_task, cancel_task)

## Expected Result

After applying these fixes, the remote upload task should be added successfully with `errno=0` instead of `errno=-1`.
