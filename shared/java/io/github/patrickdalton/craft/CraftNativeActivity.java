package io.github.patrickdalton.craft;

import com.google.androidgamesdk.GameActivity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.FileObserver;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The activity every Craft app runs in: a GameActivity (the Rust app draws everything; GameActivity
 * adds the soft keyboard's text input) plus the
 * operating-system services the desktop apps get from their platform.
 *
 * <p>Files never reach Rust as content:// URIs. A picked or received document is copied into the
 * app's cache and Rust gets that path; when the app later writes the copy (Save), a FileObserver
 * sees it and copies it back to the document. A "save as" target is an empty local path that is
 * copied to the chosen document as soon as the app has written it.
 */
public class CraftNativeActivity extends GameActivity {
    private static final String TAG = "CraftActivity";
    private static final int PICK_REQUEST = 0xC4AF;

    private static final int KIND_FILE = 0;
    private static final int KIND_FILES = 1;
    private static final int KIND_FOLDER = 2;
    private static final int KIND_SAVE = 3;

    private final Object pickLock = new Object();
    private volatile CountDownLatch pickLatch;
    private volatile int pickResultCode;
    private volatile Intent pickResultData;

    private final LinkedBlockingQueue<String> incoming = new LinkedBlockingQueue<>();
    /** Local copy (absolute path) to the document it is written back to. */
    private final Map<String, Uri> fileTargets = new ConcurrentHashMap<>();
    /** Local folder (absolute path) to the document tree its new files are copied into. */
    private final Map<String, Uri> folderTargets = new ConcurrentHashMap<>();
    /** Documents already created in a tree, by local file path, so a rewrite updates the same one. */
    private final Map<String, Uri> createdInTree = new ConcurrentHashMap<>();
    /** FileObservers stop when garbage collected: keep every one. */
    private final List<FileObserver> observers = new ArrayList<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicInteger sequence = new AtomicInteger();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        receive(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }

    // ---- Files sent by other apps ("Open with", "Share") ------------------------------------

    private void receive(Intent intent) {
        if (intent == null) return;
        final List<Uri> uris = new ArrayList<>();
        String action = intent.getAction();
        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        } else if (Intent.ACTION_SEND.equals(action)) {
            Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (uri != null) uris.add(uri);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        }
        if (uris.isEmpty()) return;
        final boolean writable = (intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        // Handled once: a configuration change must not open the files again.
        intent.setAction(null);
        io.execute(() -> {
            for (Uri uri : uris) {
                String path = importDocument(uri, writable);
                if (path != null) incoming.add(path);
            }
        });
    }

    /** Called from Rust's listener thread: blocks until another app sends a file. */
    public String craftNextIncoming() throws InterruptedException {
        return incoming.take();
    }

    // ---- Pickers (called from Rust worker threads, never the UI thread) ---------------------

    public String[] craftPick(int kind, String[] mimes, String name, String title) {
        synchronized (pickLock) {
            final CountDownLatch latch = new CountDownLatch(1);
            pickLatch = latch;
            pickResultCode = RESULT_CANCELED;
            pickResultData = null;
            final Intent intent = pickIntent(kind, mimes, name);
            runOnUiThread(() -> {
                try {
                    startActivityForResult(intent, PICK_REQUEST);
                } catch (ActivityNotFoundException e) {
                    Log.w(TAG, "no app can handle " + intent, e);
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new String[0];
            }
            Intent data = pickResultData;
            if (pickResultCode != RESULT_OK || data == null) return new String[0];
            try {
                return finishPick(kind, data, name, mimes);
            } catch (RuntimeException e) {
                Log.e(TAG, "picker result", e);
                return new String[0];
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // AppCompat requires the super call (lint fails the release build without it).
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_REQUEST) {
            pickResultCode = resultCode;
            pickResultData = data;
            CountDownLatch latch = pickLatch;
            if (latch != null) latch.countDown();
        }
    }

    private Intent pickIntent(int kind, String[] mimes, String name) {
        Intent intent;
        switch (kind) {
            case KIND_FOLDER:
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                return intent;
            case KIND_SAVE:
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(saveMime(mimes, name));
                if (name != null && !name.isEmpty()) intent.putExtra(Intent.EXTRA_TITLE, name);
                return intent;
            default:
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                if (mimes != null && mimes.length == 1) {
                    intent.setType(mimes[0]);
                } else {
                    intent.setType("*/*");
                    if (mimes != null && mimes.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
                }
                if (kind == KIND_FILES) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                return intent;
        }
    }

    private String[] finishPick(int kind, Intent data, String name, String[] mimes) {
        ContentResolver resolver = getContentResolver();
        switch (kind) {
            case KIND_FOLDER: {
                Uri tree = data.getData();
                if (tree == null) return new String[0];
                keepPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                File dir = freshDir("folder");
                folderTargets.put(dir.getAbsolutePath(), tree);
                watch(dir);
                return new String[] {dir.getAbsolutePath()};
            }
            case KIND_SAVE: {
                Uri doc = data.getData();
                if (doc == null) return new String[0];
                String chosen = displayName(doc);
                File file = new File(freshDir("save"), safeName(chosen != null ? chosen : name, "document"));
                fileTargets.put(file.getAbsolutePath(), doc);
                watch(file.getParentFile());
                return new String[] {file.getAbsolutePath()};
            }
            default: {
                List<Uri> uris = new ArrayList<>();
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                List<String> paths = new ArrayList<>();
                for (Uri uri : uris) {
                    boolean writable = keepPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                            || (data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
                    String path = importDocument(uri, writable);
                    if (path != null) paths.add(path);
                }
                return paths.toArray(new String[0]);
            }
        }
    }

    // ---- Copies and write-back -------------------------------------------------------------

    /** Copies a document into the cache; returns the copy's path, or null when it can't be read. */
    private String importDocument(Uri uri, boolean writable) {
        String name = safeName(displayName(uri), "document");
        File file = new File(freshDir("open"), name);
        try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
            if (in == null) return null;
            copy(in, out);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "couldn't read " + uri, e);
            return null;
        }
        if (writable) {
            fileTargets.put(file.getAbsolutePath(), uri);
            watch(file.getParentFile());
        }
        return file.getAbsolutePath();
    }

    @SuppressWarnings("deprecation")
    private void watch(final File dir) {
        final String dirPath = dir.getAbsolutePath();
        FileObserver observer = new FileObserver(dirPath, FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String relative) {
                if (relative == null) return;
                final File file = new File(dirPath, relative);
                io.execute(() -> written(file));
            }
        };
        observer.startWatching();
        synchronized (observers) {
            observers.add(observer);
        }
    }

    private void written(File file) {
        String path = file.getAbsolutePath();
        Uri target = fileTargets.get(path);
        if (target == null) {
            Uri tree = folderTargets.get(file.getParent());
            if (tree == null || file.getName().startsWith(".")) return;
            target = createdInTree.get(path);
            if (target == null) {
                target = createInTree(tree, file.getName());
                if (target == null) return;
                createdInTree.put(path, target);
            }
        }
        try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
            if (out == null) return;
            copy(in, out);
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "couldn't write back " + path + " to " + target, e);
        }
    }

    private Uri createInTree(Uri tree, String name) {
        try {
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            return DocumentsContract.createDocument(getContentResolver(), parent, mimeFor(name), name);
        } catch (Exception e) {
            Log.w(TAG, "couldn't create " + name + " in " + tree, e);
            return null;
        }
    }

    // ---- Clipboard (arboard shim) -------------------------------------------------------------

    public String craftClipboardGet() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        return text == null ? null : text.toString();
    }

    public void craftClipboardSet(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("text", text));
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private boolean keepPermission(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
            return true;
        } catch (SecurityException e) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // A one-time grant still works while the app runs.
            }
            return false;
        }
    }

    private File freshDir(String purpose) {
        File dir = new File(new File(getCacheDir(), "craft-" + purpose), Integer.toString(sequence.incrementAndGet()));
        if (!dir.isDirectory() && !dir.mkdirs()) Log.w(TAG, "couldn't create " + dir);
        return dir;
    }

    private String displayName(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getLastPathSegment();
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception e) {
            Log.w(TAG, "no name for " + uri, e);
        }
        return uri.getLastPathSegment();
    }

    private static String safeName(String name, String fallback) {
        if (name == null) return fallback;
        String cleaned = name.replace('/', '_').replace('\\', '_').trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) return fallback;
        return cleaned;
    }

    private static String saveMime(String[] mimes, String name) {
        if (mimes != null && mimes.length > 0 && !"*/*".equals(mimes[0])) return mimes[0];
        String guessed = mimeFor(name);
        return guessed != null ? guessed : "application/octet-stream";
    }

    private static String mimeFor(String name) {
        if (name == null) return "application/octet-stream";
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
        return mime != null ? mime : "application/octet-stream";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
    }
}
