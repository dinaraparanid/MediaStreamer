package com.paranid5.mediastreamer.utils.extensions

import android.content.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.bumptech.glide.Glide
import com.paranid5.mediastreamer.data.VideoMetadata
import com.paranid5.mediastreamer.presentation.MainActivity
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

fun Context.registerReceiverCompat(
    receiver: BroadcastReceiver,
    filter: IntentFilter,
) = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> registerReceiver(
        receiver,
        filter,
        Context.RECEIVER_NOT_EXPORTED
    )

    else -> registerReceiver(receiver, filter)
}

fun Context.registerReceiverCompat(
    receiver: BroadcastReceiver,
    vararg actions: String,
) = registerReceiverCompat(
    receiver = receiver,
    filter = IntentFilter().also { actions.forEach(it::addAction) }
)

fun Context.getImageBinaryData(url: String) =
    Glide.with(applicationContext)
        .asBitmap()
        .load(url)
        .submit()
        .get()
        .byteData

fun Context.getImageBinaryDataCatching(url: String) =
    kotlin.runCatching { getImageBinaryData(url) }

fun Context.setAudioTagsToFile(file: File, videoMetadata: VideoMetadata) =
    AudioFileIO.read(file).run {
        tagOrCreateAndSetDefault.run {
            setField(FieldKey.TITLE, videoMetadata.title)
            setField(FieldKey.ARTIST, videoMetadata.author)

            videoMetadata
                .covers
                .asSequence()
                .map { getImageBinaryDataCatching(it) }
                .firstOrNull { it.isSuccess }
                ?.getOrNull()
                ?.let { byteData ->
                    addField(
                        ArtworkFactory
                            .createArtworkFromFile(file)
                            .apply { binaryData = byteData }
                    )
                }

            commit()
        }
    }

fun Context.insertMediaFileToMediaStore(
    externalContentUri: Uri,
    absoluteFilePath: String,
    relativeFilePath: String,
    videoMetadata: VideoMetadata,
    mimeType: String,
): Uri? {
    val uri = applicationContext.contentResolver.insert(
        externalContentUri,
        ContentValues().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.MediaColumns.IS_PENDING, 1)

            put(MediaStore.MediaColumns.TITLE, videoMetadata.title)
            put(MediaStore.MediaColumns.ARTIST, videoMetadata.author)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                put(MediaStore.MediaColumns.AUTHOR, videoMetadata.author)

            put(MediaStore.MediaColumns.DURATION, videoMetadata.lenInMillis)
            put(MediaStore.MediaColumns.DATA, absoluteFilePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, videoMetadata.title)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeFilePath)
        }
    )

    return uri
}

fun Context.sendSetTagsBroadcast(
    filePath: String,
    isSaveAsVideo: Boolean,
    videoMetadata: VideoMetadata,
) = sendBroadcast(
    Intent(MainActivity.Broadcast_SET_TAGS).apply {
        putExtra(MainActivity.FILE_PATH_ARG, filePath)
        putExtra(MainActivity.IS_VIDEO_ARG, isSaveAsVideo)
        putExtra(MainActivity.VIDEO_METADATA_ARG, videoMetadata)
    }
)