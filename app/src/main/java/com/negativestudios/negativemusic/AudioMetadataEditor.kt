package com.negativestudios.negativemusic

import android.content.Context
import android.net.Uri
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File
import java.io.FileOutputStream

object AudioMetadataEditor {
    fun write(
        context: Context,
        audioUri: Uri,
        title: String,
        artist: String,
        coverUri: Uri?
    ) {
        val resolver = context.contentResolver
        val extension = resolver.query(audioUri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getString(0).orEmpty()
                name.substringAfterLast('.', "mp3").lowercase()
            } else "mp3"
        } ?: "mp3"

        val audioTemp = File.createTempFile("negative_music_audio_", ".$extension", context.cacheDir)
        val coverTemp = if (coverUri != null) {
            File.createTempFile("negative_music_cover_", ".img", context.cacheDir)
        } else null

        try {
            resolver.openInputStream(audioUri)?.use { input ->
                audioTemp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("No se pudo leer el archivo de audio.")

            if (coverUri != null && coverTemp != null) {
                resolver.openInputStream(coverUri)?.use { input ->
                    coverTemp.outputStream().use { output -> input.copyTo(output) }
                } ?: error("No se pudo leer la portada.")
            }

            TagOptionSingleton.getInstance().setAndroid(true)
            val audioFile = AudioFileIO.read(audioTemp)
            val tag = audioFile.getTagOrCreateAndSetDefault()

            tag.setField(FieldKey.TITLE, title)
            tag.setField(FieldKey.ARTIST, artist)

            if (coverTemp != null) {
                val artwork = ArtworkFactory.createArtworkFromFile(coverTemp)
                tag.deleteArtworkField()
                tag.setField(artwork)
            }

            audioFile.commit()

            resolver.openFileDescriptor(audioUri, "rwt")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { output ->
                    audioTemp.inputStream().use { input -> input.copyTo(output) }
                }
            } ?: error("No se pudo abrir el archivo para escritura.")

        } finally {
            audioTemp.delete()
            coverTemp?.delete()
        }
    }
}
