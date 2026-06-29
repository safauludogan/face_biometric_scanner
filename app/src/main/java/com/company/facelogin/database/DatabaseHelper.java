package com.company.facelogin.database;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "facelogin.db";
    private static final int DATABASE_VERSION = 1;

    static final String TABLE_USERS = "users";
    static final String COL_ID             = "id";
    static final String COL_FIRST_NAME     = "firstName";
    static final String COL_LAST_NAME      = "lastName";
    static final String COL_FACE_EMBEDDING = "faceEmbedding";
    static final String COL_CREATED_AT     = "createdAt";

    private static final String SQL_CREATE_USERS =
            "CREATE TABLE IF NOT EXISTS " + TABLE_USERS + " (" +
            COL_ID             + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_FIRST_NAME     + " TEXT NOT NULL, " +
            COL_LAST_NAME      + " TEXT NOT NULL, " +
            COL_FACE_EMBEDDING + " BLOB, " +
            COL_CREATED_AT     + " INTEGER NOT NULL" +
            ")";

    public DatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(SQL_CREATE_USERS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_USERS);
        onCreate(db);
    }
}
