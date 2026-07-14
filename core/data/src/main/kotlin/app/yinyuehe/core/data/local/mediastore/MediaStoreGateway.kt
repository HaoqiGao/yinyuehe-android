package app.yinyuehe.core.data.local.mediastore

internal interface MediaStoreGateway {
  suspend fun externalVolumeNames(): List<String>

  suspend fun readVolume(volumeName: String): List<MediaStoreAudio>
}
