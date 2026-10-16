package com.hector.epubreader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.UUID;

/** Signed-APK upgrade smoke probe. Run only on a disposable compatibility emulator. */
public class ReleaseUpgradeProbe extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { super.onCreate(args); arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            File database = getTargetContext().getDatabasePath("library.db");
            File marker = new File(getTargetContext().getFilesDir(), "upgrade-probe-id");
            if ("prepare".equals(arguments.getString("phase"))) {
                if (getTargetContext().getPackageManager().getPackageInfo("com.hector.epubreader", 0).versionCode != 3) throw new AssertionError("Expected published 1.2");
                String id = UUID.randomUUID().toString();
                File folder = new File(getTargetContext().getFilesDir(), "books/" + id);
                if (!folder.mkdirs()) throw new AssertionError("Cannot create fixture directory");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream source = getContext().getAssets().open("lumbre-demo.epub"); FileOutputStream output = new FileOutputStream(new File(folder, "book.epub"))) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = source.read(buffer)) >= 0) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
                }
                StringBuilder hash = new StringBuilder();
                for (byte b : digest.digest()) hash.append(String.format("%02x", b));
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                    if (db.getVersion() != 2) throw new AssertionError("Expected Room schema 2 before upgrade");
                    ContentValues book = new ContentValues();
                    book.put("id", id); book.put("title", "Signed upgrade fixture"); book.put("sourceUri", "upgrade-probe");
                    book.put("checksum", hash.toString()); book.put("chapterCount", 2); book.put("currentChapter", 1);
                    book.put("readingPosition", 0.4); book.put("progress", 0.7); book.put("dateAdded", 1); book.put("lastRead", 2);
                    book.put("readingTextOffset", 1234);
                    db.insertOrThrow("books", null, book);
                    ContentValues mark = new ContentValues();
                    mark.put("bookId", id); mark.put("chapter", 1); mark.put("position", 0.4); mark.put("title", "Upgrade bookmark"); mark.put("createdAt", 3); mark.put("textOffset", 1234);
                    db.insertOrThrow("bookmarks", null, mark);
                }
                try (FileOutputStream output = new FileOutputStream(marker)) { output.write(id.getBytes("UTF-8")); }
            } else {
                if (getTargetContext().getPackageManager().getPackageInfo("com.hector.epubreader", 0).versionCode != 4) throw new AssertionError("Expected candidate 1.3");
                String id = new String(java.nio.file.Files.readAllBytes(marker.toPath()), "UTF-8");
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    if (db.getVersion() != 5) throw new AssertionError("Application did not migrate to Room 5");
                    try (Cursor book = db.rawQuery("SELECT currentChapter,readingPosition,readingTextOffset,collection FROM books WHERE id=?", new String[]{id})) {
                        if (!book.moveToFirst() || book.getInt(0) != 1 || Math.abs(book.getDouble(1) - 0.4) > 0.001 || book.getInt(2) != 1234 || !book.getString(3).isEmpty()) throw new AssertionError("Reading data changed during upgrade");
                    }
                    try (Cursor marks = db.rawQuery("SELECT title,textOffset FROM bookmarks WHERE bookId=?", new String[]{id})) {
                        if (!marks.moveToFirst() || !"Upgrade bookmark".equals(marks.getString(0)) || marks.getInt(1) != 1234) throw new AssertionError("Bookmark changed during upgrade");
                    }
                    if (!new File(getTargetContext().getFilesDir(), "books/" + id + "/book.epub").isFile()) throw new AssertionError("Book file lost during upgrade");
                }
            }
            result.putString("passed", "true"); finish(Activity.RESULT_OK, result);
        } catch (Throwable error) { result.putString("failure", error.toString()); finish(Activity.RESULT_CANCELED, result); }
    }
}
