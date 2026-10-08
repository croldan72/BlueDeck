package com.bluedeck.data.obd

import android.accounts.Account
import android.content.Context
import com.bluedeck.R
import com.bluedeck.data.obd.db.ObdDatabase
import com.bluedeck.data.repository.PreferencesManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObdDriveSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ObdDatabase,
    private val preferencesManager: PreferencesManager,
    private val csvExporter: ObdCsvExporter
) {

    private val syncMutex = Mutex()

    fun isConfigured(): Boolean {
        val clientId = context.getString(R.string.google_drive_web_client_id)
        return clientId.isNotBlank() && clientId != "YOUR_WEB_CLIENT_ID"
    }

    fun buildSignInClient() = GoogleSignIn.getClient(
        context,
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .apply {
                val clientId = context.getString(R.string.google_drive_web_client_id)
                if (clientId.isNotBlank() && clientId != "YOUR_WEB_CLIENT_ID") {
                    requestIdToken(clientId)
                    requestScopes(Scope(DriveScopes.DRIVE_FILE))
                }
            }
            .build()
    )

    suspend fun getSignedInAccountEmail(): String? = withContext(Dispatchers.IO) {
        GoogleSignIn.getLastSignedInAccount(context)?.email
    }

    suspend fun syncSession(sessionId: Long): Result<String> = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        runCatching {
            if (!preferencesManager.obdDriveSyncEnabled.first()) {
                error("Drive sync disabled")
            }

            if (!isConfigured()) {
                error("Google Drive not configured")
            }

            val signedInAccount = GoogleSignIn.getLastSignedInAccount(context)
                ?: error("Not signed in to Google")

            val androidAccount = signedInAccount.account
                ?: error("Google account is unavailable")

            val session = database.sessionDao().getById(sessionId)
                ?: error("Session not found")

            val drive = buildDriveService(androidAccount)
            val folderId = findOrCreateObdLogsFolder(drive)

            val samples = database.sampleDao().getForSession(sessionId)
            val mainFileId = uploadCsvIfMissing(
                drive = drive,
                folderId = folderId,
                fileName = csvExporter.sessionFileName(session),
                content = csvExporter.buildSessionCsv(session, samples)
            )

            val cellSnapshots = database.cellSnapshotDao().getAllForSession(sessionId)
            if (cellSnapshots.isNotEmpty()) {
                uploadCsvIfMissing(
                    drive = drive,
                    folderId = folderId,
                    fileName = csvExporter.cellsFileName(session),
                    content = csvExporter.buildCellsCsv(cellSnapshots)
                )
            }

            if (session.driveFileId != mainFileId) {
                database.sessionDao().update(session.copy(driveFileId = mainFileId))
            }

            preferencesManager.setObdDriveLastSyncAt(System.currentTimeMillis())
            mainFileId
        }
        }
    }

    private fun buildDriveService(account: Account): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_FILE)
        )
        credential.selectedAccount = account

        return Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName(context.getString(R.string.app_name))
            .build()
    }

    private fun findOrCreateObdLogsFolder(drive: Drive): String {
        val blueDeckFolderId = findFolder(
            drive = drive,
            name = "BlueDeck",
            parentId = "root"
        ) ?: createFolder(
            drive = drive,
            name = "BlueDeck",
            parentId = "root"
        )

        return findFolder(
            drive = drive,
            name = "OBD Logs",
            parentId = blueDeckFolderId
        ) ?: createFolder(
            drive = drive,
            name = "OBD Logs",
            parentId = blueDeckFolderId
        )
    }

    private fun findFolder(
        drive: Drive,
        name: String,
        parentId: String
    ): String? {
        val safeName = escapeDriveQueryValue(name)

        val query =
            "mimeType='application/vnd.google-apps.folder' and " +
                "name='$safeName' and " +
                "'$parentId' in parents and trashed=false"

        return drive.files()
            .list()
            .setQ(query)
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()
            .files
            ?.firstOrNull()
            ?.id
    }

    private fun createFolder(
        drive: Drive,
        name: String,
        parentId: String
    ): String {
        val metadata = File().apply {
            this.name = name
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentId)
        }

        return drive.files()
            .create(metadata)
            .setFields("id")
            .execute()
            .id
    }

    private fun uploadCsvIfMissing(
        drive: Drive,
        folderId: String,
        fileName: String,
        content: String
    ): String {
        val safeName = escapeDriveQueryValue(fileName)

        val existing = drive.files()
            .list()
            .setQ(
                "name='$safeName' and " +
                    "'$folderId' in parents and " +
                    "trashed=false"
            )
            .setSpaces("drive")
            .setFields("files(id,name)")
            .execute()
            .files
            ?.firstOrNull()

        if (existing?.id != null) {
            return existing.id
        }

        val metadata = File().apply {
            name = fileName
            parents = listOf(folderId)
            mimeType = "text/csv"
        }

        return drive.files()
            .create(
                metadata,
                ByteArrayContent(
                    "text/csv",
                    content.toByteArray(Charsets.UTF_8)
                )
            )
            .setFields("id")
            .execute()
            .id
    }

    private fun escapeDriveQueryValue(value: String): String =
        value.replace("\\", "\\\\").replace("'", "\\'")
}

