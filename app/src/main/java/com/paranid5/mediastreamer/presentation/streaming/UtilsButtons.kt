package com.paranid5.mediastreamer.presentation.streaming

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.paranid5.mediastreamer.EXTERNAL_STORAGE_PERMISSION_QUEUE
import com.paranid5.mediastreamer.R
import com.paranid5.mediastreamer.StorageHandler
import com.paranid5.mediastreamer.utils.BroadcastReceiver
import com.paranid5.mediastreamer.presentation.LocalActivity
import com.paranid5.mediastreamer.presentation.ui.permissions.ExternalStorageDescriptionProvider
import com.paranid5.mediastreamer.presentation.ui.permissions.PermissionDialog
import com.paranid5.mediastreamer.presentation.ui.theme.LocalAppColors
import com.paranid5.mediastreamer.utils.extensions.openAppSettings
import com.paranid5.mediastreamer.video_cash_service.VideoCashResponse
import org.koin.androidx.compose.get
import org.koin.core.qualifier.named
import java.util.Queue

@Composable
fun UtilsButtons(modifier: Modifier = Modifier) =
    Row(modifier.fillMaxWidth()) {
        EqualizerButton(Modifier.weight(1F))
        RepeatButton(Modifier.weight(1F))
        LikeButton(Modifier.weight(1F))
        DownloadButton(Modifier.weight(1F))
    }

@Composable
private fun EqualizerButton(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current.value

    IconButton(modifier = modifier, onClick = { /*TODO Equalizer*/ }) {
        Icon(
            modifier = Modifier.size(30.dp),
            painter = painterResource(R.drawable.equalizer),
            contentDescription = stringResource(R.string.equalizer),
            tint = colors.primary
        )
    }
}

@Composable
private fun RepeatButton(
    modifier: Modifier = Modifier,
    storageHandler: StorageHandler = get(),
    streamingUIHandler: StreamingUIHandler = get()
) {
    val colors = LocalAppColors.current.value
    var isRepeating by remember { mutableStateOf(storageHandler.isRepeatingState.value) }

    BroadcastReceiver(action = Broadcast_IS_REPEATING_CHANGED) { _, intent ->
        isRepeating = intent!!.getBooleanExtra(IS_REPEATING_ARG, false)
    }

    IconButton(modifier = modifier, onClick = { streamingUIHandler.sendChangeRepeatBroadcast() }) {
        Icon(
            modifier = Modifier.size(30.dp),
            painter = painterResource(if (isRepeating) R.drawable.repeat else R.drawable.no_repeat),
            contentDescription = stringResource(R.string.change_repeat),
            tint = colors.primary
        )
    }
}

@Composable
private fun LikeButton(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current.value
    val isLiked by remember { mutableStateOf(false) }

    BroadcastReceiver(action = Broadcast_IS_REPEATING_CHANGED) { _, intent ->
        // TODO: favourite database
    }

    /** TODO: favourite database */
    IconButton(modifier = modifier, onClick = { /** TODO: favourite database */ }) {
        Icon(
            modifier = Modifier.size(30.dp),
            painter = painterResource(if (isLiked) R.drawable.like_filled else R.drawable.like),
            contentDescription = stringResource(R.string.favourites),
            tint = colors.primary
        )
    }
}

@Composable
private fun DownloadButton(modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current.value
    val activity = LocalActivity.current!!
    val isCashPropertiesDialogShownState = remember { mutableStateOf(false) }

    val externalStoragePermissionQueue = get<Queue<String>>(
        named(EXTERNAL_STORAGE_PERMISSION_QUEUE)
    )

    val externalStorageDescriptionProvider = get<ExternalStorageDescriptionProvider>()
    val notGrantedStoragePermissions = remember { mutableStateListOf<String>() }

    var areAllPermissionsGranted by remember {
        mutableStateOf(
            externalStoragePermissionQueue.all { permission ->
                ContextCompat.checkSelfPermission(activity, permission) ==
                        PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val filesPermissionsResultLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { permissionsToGranted ->
            permissionsToGranted
                .asSequence()
                .filter { (_, isGranted) -> isGranted }
                .forEach { (permission, _) ->
                    notGrantedStoragePermissions.remove(permission)
                }

            notGrantedStoragePermissions.addAll(
                permissionsToGranted
                    .asSequence()
                    .filter { (_, isGranted) -> !isGranted }
                    .filter { (permission, _) -> permission !in notGrantedStoragePermissions }
                    .map { (permission, _) -> permission }
            )

            areAllPermissionsGranted = notGrantedStoragePermissions.isEmpty()
        }
    )

    BroadcastReceiver(action = Broadcast_VIDEO_CASH_COMPLETED) { context, intent ->
        val status = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                intent!!.getSerializableExtra(VIDEO_CASH_STATUS, VideoCashResponse::class.java)!!
            else ->
                intent!!.getSerializableExtra(VIDEO_CASH_STATUS)!! as VideoCashResponse
        }

        onVideoCashCompleted(status, context!!)
    }

    Box(modifier) {
        IconButton(
            modifier = modifier,
            onClick = {
                filesPermissionsResultLauncher.launch(externalStoragePermissionQueue.toTypedArray())
                isCashPropertiesDialogShownState.value = true
            }
        ) {
            Icon(
                modifier = Modifier.size(30.dp),
                painter = painterResource(R.drawable.save_icon),
                contentDescription = stringResource(R.string.download_as_mp3),
                tint = colors.primary
            )
        }

        if (isCashPropertiesDialogShownState.value) {
            notGrantedStoragePermissions.forEach { permission ->
                PermissionDialog(
                    isDialogShownState = isCashPropertiesDialogShownState,
                    modifier = Modifier.align(Alignment.Center),
                    permissionDescriptionProvider = externalStorageDescriptionProvider,
                    isPermanentlyDeclined = when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                            !activity.shouldShowRequestPermissionRationale(permission)
                        else -> false
                    },
                    onGrantedClicked = {
                        filesPermissionsResultLauncher.launch(arrayOf(permission))
                        notGrantedStoragePermissions.removeFirst()
                    },
                    onGoToAppSettingsClicked = activity::openAppSettings
                )
            }

            if (areAllPermissionsGranted)
                CashPropertiesDialog(
                    isDialogShownState = isCashPropertiesDialogShownState,
                    modifier = Modifier.align(Alignment.Center)
                )
        }
    }
}

private fun onVideoCashCompleted(status: VideoCashResponse, context: Context) {
    val errorStringRes = context.getString(R.string.error)
    val successfulCashingStringRes = context.getString(R.string.video_cashed)

    Toast.makeText(
        context,
        when (status) {
            is VideoCashResponse.Error -> {
                val (httpCode, description) = status
                "$errorStringRes $httpCode: $description"
            }

            VideoCashResponse.Success -> successfulCashingStringRes
        },
        Toast.LENGTH_LONG
    ).show()
}