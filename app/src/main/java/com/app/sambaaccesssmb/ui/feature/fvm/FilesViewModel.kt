package com.app.sambaaccesssmb.ui.feature.fvm

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.sambaaccesssmb.SMBAccess
import com.app.sambaaccesssmb.ui.feature.fvm.FileState.Downloading
import com.app.sambaaccesssmb.ui.feature.fvm.FileState.Error
import com.app.sambaaccesssmb.ui.feature.fvm.FileState.Loading
import com.app.sambaaccesssmb.ui.feature.fvm.FileState.Success
import com.app.sambaaccesssmb.utils.DirUtil
import com.app.sambaaccesssmb.utils.capitalizeFirst
import com.app.sambaaccesssmb.utils.itemCount
import dagger.hilt.android.lifecycle.HiltViewModel
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.FileOutputStream
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor() : ViewModel() {

    private val sampleFolderName = "Sample"
    var selectedSmbFile: SmbFile? = null

    val fileCursor: StateFlow<FileState> = flow<FileState> {
        runCatching {
            val smbFiles = SMBAccess.getSmbConnectionInstance().rootSMBFile.listFiles()
            val subList = smbFiles[1].listFiles() { file -> file.name.contains(sampleFolderName) && file.isDirectory }

            val firstSmbFolder = subList[0].listFiles().filterNot { it.name.startsWith(".") }
            val smbItems = firstSmbFolder.sortedByDescending { it.createTime() }.map { smbFile ->
                val isDirectory = smbFile.isDirectory
                val itemCountInside = smbFile.itemCount(isDirectory)
                Locus(smbFile.name.capitalizeFirst().dropLast(1), isDirectory, itemCountInside, smbFile)
            }.toList()
            emit(Success(smbItems))
        }.getOrElse {
            emit(Error)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Loading,
        )

    val displaySmbFile: StateFlow<FileState> = flow<FileState> {
        runCatching {
            selectedSmbFile?.let { smbItem ->
                DirUtil.getTempFile(smbItem.name)?.let { tempFile ->
                    var downloadedBytes = 0L
                    val tempFilePath = tempFile.path
                    val smbFileSize = smbItem.length()
                    val fileInputStream = smbItem.inputStream
                    val inputStream = BufferedInputStream(fileInputStream)
                    val fileOutputStream = FileOutputStream(tempFile)
                    val buffer = ByteArray(1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fileOutputStream.write(buffer, 0, bytesRead)
                        if (bytesRead > 0) {
                            downloadedBytes += bytesRead
                            if (downloadedBytes > 0) {
                                val progress = downloadedBytes.div(smbFileSize) * 100
                                emit(Downloading(progress.toFloat()))
                            }
                        }
                    }
                    inputStream.close()
                    fileOutputStream.close()
                    emit(FileState.DownloadCompleted(tempFilePath))
                }
            }
        }.getOrElse {
            Timber.d(it, it.localizedMessage.orEmpty())
            emit(Error)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = Loading,
        )
}

data class Locus(var fileName: String, val isDirectory: Boolean, val itemCount: String = "", val originalFile: SmbFile)

sealed interface FileState {
    data class Success(val smbFiles: List<Locus>) : FileState
    data class Downloading(val progress: Float) : FileState
    data class DownloadCompleted(val filePath: String) : FileState
    object Error : FileState
    object Loading : FileState
}
