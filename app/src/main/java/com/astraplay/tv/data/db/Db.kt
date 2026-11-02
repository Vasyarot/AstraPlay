package com.astraplay.tv.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Чистый SQLiteOpenHelper вместо Room: на минимальной прошивке экономим
 * и APK, и память (нет аннотаций, рефлексии и валидации на старте).
 */
class Db(context: Context) : SQLiteOpenHelper(context, "astraplay.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) = execSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 — схема только создается
    }

    private fun execSchema(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlists(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'url',
                sortOrder INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE channels(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                playlistId INTEGER NOT NULL,
                name TEXT NOT NULL,
                url TEXT NOT NULL,
                logo TEXT,
                grp TEXT,
                epgId TEXT,
                sortOrder INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_channels_pl ON channels(playlistId, grp, sortOrder)")
        db.execSQL(
            """CREATE TABLE favorites(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                itemId TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                poster TEXT,
                url TEXT,
                category TEXT NOT NULL DEFAULT '',
                addedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE UNIQUE INDEX idx_fav_uniq ON favorites(kind, itemId, category)")
        db.execSQL(
            """CREATE TABLE history(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                itemId TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                poster TEXT,
                url TEXT,
                viewedAt INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX idx_history_time ON history(viewedAt DESC)")
        db.execSQL(
            """CREATE TABLE positions(
                itemId TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                subtitle TEXT,
                poster TEXT,
                url TEXT NOT NULL,
                positionMs INTEGER NOT NULL DEFAULT 0,
                durationMs INTEGER NOT NULL DEFAULT 0,
                updatedAt INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE watched(
                itemId TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                markedAt INTEGER NOT NULL)"""
        )
    }
}
