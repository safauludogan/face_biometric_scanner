package com.company.facelogin.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.company.facelogin.models.User;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class UserDao {

    private final DatabaseHelper dbHelper;

    public UserDao(DatabaseHelper dbHelper) {
        this.dbHelper = dbHelper;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public long insertUser(User user) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(DatabaseHelper.COL_FIRST_NAME, user.getFirstName());
        values.put(DatabaseHelper.COL_LAST_NAME, user.getLastName());
        values.put(DatabaseHelper.COL_FACE_EMBEDDING, user.getFaceEmbedding());
        values.put(DatabaseHelper.COL_CREATED_AT, user.getCreatedAt());
        return db.insert(DatabaseHelper.TABLE_USERS, null, values);
    }

    public List<User> getAllUsers() {
        List<User> users = new ArrayList<>();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                DatabaseHelper.TABLE_USERS, null,
                null, null, null, null, null);
        try {
            while (cursor.moveToNext()) {
                users.add(userFromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
        return users;
    }

    public User getUserById(long id) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.query(
                DatabaseHelper.TABLE_USERS, null,
                DatabaseHelper.COL_ID + "=?",
                new String[]{String.valueOf(id)},
                null, null, null);
        try {
            if (cursor.moveToFirst()) return userFromCursor(cursor);
        } finally {
            cursor.close();
        }
        return null;
    }

    // ── Embedding conversion (float[] ↔ byte[]) ───────────────────────────────

    public static byte[] embeddingToBytes(float[] embedding) {
        ByteBuffer buffer = ByteBuffer.allocate(embedding.length * Float.BYTES);
        buffer.order(ByteOrder.nativeOrder());
        for (float v : embedding) buffer.putFloat(v);
        return buffer.array();
    }

    public static float[] bytesToEmbedding(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.nativeOrder());
        float[] embedding = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < embedding.length; i++) embedding[i] = buffer.getFloat();
        return embedding;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private User userFromCursor(Cursor cursor) {
        User user = new User();
        user.setId(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_ID)));
        user.setFirstName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_FIRST_NAME)));
        user.setLastName(cursor.getString(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_LAST_NAME)));
        user.setFaceEmbedding(cursor.getBlob(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_FACE_EMBEDDING)));
        user.setCreatedAt(cursor.getLong(cursor.getColumnIndexOrThrow(DatabaseHelper.COL_CREATED_AT)));
        return user;
    }
}
