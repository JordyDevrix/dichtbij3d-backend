package nl.dichtbij3d.backend.service

import io.minio.BucketExistsArgs
import io.minio.GetObjectArgs
import io.minio.MakeBucketArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.RemoveObjectArgs
import jakarta.annotation.PostConstruct
import nl.dichtbij3d.backend.config.StorageProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.util.UUID

/**
 * Blob storage abstraction. Prefers the S3 compatible MinIO service from docker-compose,
 * and transparently falls back to local disk so the app still runs without docker.
 */
@Service
class StorageService(private val props: StorageProperties) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var client: MinioClient? = null
    private val fallbackRoot: Path = Paths.get(props.fallbackDirectory).toAbsolutePath().normalize()

    @PostConstruct
    fun init() {
        Files.createDirectories(fallbackRoot)
        try {
            val candidate = MinioClient.builder()
                .endpoint(props.endpoint)
                .credentials(props.accessKey, props.secretKey)
                .build()
            val exists = candidate.bucketExists(BucketExistsArgs.builder().bucket(props.bucket).build())
            if (!exists) {
                candidate.makeBucket(MakeBucketArgs.builder().bucket(props.bucket).build())
            }
            client = candidate
            log.info("Blob storage: MinIO at {} (bucket '{}')", props.endpoint, props.bucket)
        } catch (ex: Exception) {
            log.warn(
                "MinIO unavailable ({}), falling back to local disk storage at {}",
                ex.message,
                fallbackRoot,
            )
            client = null
        }
    }

    fun store(file: MultipartFile, prefix: String): StoredObject {
        val original = sanitize(file.originalFilename ?: "file")
        val key = buildKey(prefix, original)
        file.inputStream.use { put(key, it, file.size, file.contentType ?: "application/octet-stream") }
        return StoredObject(key, original, file.size, file.contentType ?: "application/octet-stream")
    }

    fun put(key: String, stream: InputStream, size: Long, contentType: String) {
        val minio = client
        if (minio != null) {
            minio.putObject(
                PutObjectArgs.builder()
                    .bucket(props.bucket)
                    .`object`(key)
                    .stream(stream, size, -1)
                    .contentType(contentType)
                    .build()
            )
        } else {
            val target = resolveLocal(key)
            Files.createDirectories(target.parent)
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun read(key: String): InputStream? = try {
        val minio = client
        if (minio != null) {
            minio.getObject(GetObjectArgs.builder().bucket(props.bucket).`object`(key).build())
        } else {
            val path = resolveLocal(key)
            if (Files.exists(path)) Files.newInputStream(path) else null
        }
    } catch (ex: Exception) {
        log.debug("Object '{}' could not be read: {}", key, ex.message)
        null
    }

    fun size(key: String): Long? = try {
        val minio = client
        if (minio != null) {
            minio.statObject(io.minio.StatObjectArgs.builder().bucket(props.bucket).`object`(key).build()).size()
        } else {
            val path = resolveLocal(key)
            if (Files.exists(path)) Files.size(path) else null
        }
    } catch (ex: Exception) {
        null
    }

    /**
     * Attempts to read the object with fallback between adverts/ and listings/ prefixes
     * (handling legacy keys vs adblocker-friendly keys).
     * Returns a pair of InputStream and file size (if determinable).
     */
    fun readWithAlias(key: String): Pair<InputStream, Long?>? {
        val candidates = listOfNotNull(
            key,
            if (key.startsWith("listings/")) key.replaceFirst("listings/", "adverts/") else null,
            if (key.startsWith("adverts/")) key.replaceFirst("adverts/", "listings/") else null
        ).distinct()

        for (candidate in candidates) {
            val stream = read(candidate)
            if (stream != null) {
                return Pair(stream, size(candidate))
            }
        }
        return null
    }

    /**
     * Reads all bytes of the object, checking aliases if necessary.
     */
    fun readBytesWithAlias(key: String): ByteArray? {
        val (stream, _) = readWithAlias(key) ?: return null
        return stream.use { it.readBytes() }
    }


    fun delete(key: String) {
        try {
            val minio = client
            if (minio != null) {
                minio.removeObject(RemoveObjectArgs.builder().bucket(props.bucket).`object`(key).build())
            } else {
                Files.deleteIfExists(resolveLocal(key))
            }
        } catch (ex: Exception) {
            log.debug("Object '{}' could not be deleted: {}", key, ex.message)
        }
    }

    /** Public URL served by this service (keeps auth/CORS simple, works for MinIO and disk alike). */
    fun publicUrl(key: String?): String? = key?.let {
        val safeKey = if (it.startsWith("adverts/")) it.replaceFirst("adverts/", "listings/") else it
        val allowedPrefixes = listOf("avatars/", "listings/", "adverts/", "banner/", "banners/", "announcements/", "thumbnails/", "public/")
        if (allowedPrefixes.any { prefix -> safeKey.startsWith(prefix) }) {
            "/api/files/$safeKey"
        } else {
            null
        }
    }

    private fun resolveLocal(key: String): Path {
        val resolved = fallbackRoot.resolve(key).normalize()
        require(resolved.startsWith(fallbackRoot)) { "Invalid object key" }
        return resolved
    }

    private fun buildKey(prefix: String, fileName: String): String {
        val date = LocalDate.now()
        return "$prefix/${date.year}/${"%02d".format(date.monthValue)}/${UUID.randomUUID()}-$fileName"
    }

    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(120)
            .ifBlank { "file" }
}

data class StoredObject(
    val key: String,
    val fileName: String,
    val size: Long,
    val contentType: String,
)
