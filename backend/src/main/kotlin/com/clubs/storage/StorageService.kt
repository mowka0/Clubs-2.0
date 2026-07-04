package com.clubs.storage

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.PutBucketPolicyRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

@Service
class StorageService(
    private val s3Client: S3Client,

    @Value("\${s3.bucket:clubs-uploads}")
    private val bucket: String,

    @Value("\${s3.base-url:}")
    private val baseUrl: String
) {

    private val logger = LoggerFactory.getLogger(StorageService::class.java)

    /**
     * Создаёт бакет при старте, если его нет (MinIO поставляется пустым), и
     * применяет политику public-read, чтобы загруженные аватары были доступны через <img src>.
     *
     * Ошибки здесь логируются, но не фатальны — backend всё равно должен стартовать;
     * админ видит ошибку, а загрузки будут аккуратно падать, пока бакет не станет доступен.
     */
    @PostConstruct
    fun initBucket() {
        // Шаг 1: убеждаемся, что бакет существует (идемпотентно).
        var bucketReady = false
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            logger.info("S3 bucket exists: {}", bucket)
            bucketReady = true
        } catch (_: NoSuchBucketException) {
            try {
                s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                logger.info("S3 bucket created: {}", bucket)
                bucketReady = true
            } catch (e: Exception) {
                logger.error("Failed to create S3 bucket '{}': {}", bucket, e.message, e)
            }
        } catch (e: S3Exception) {
            if (e.statusCode() == 404) {
                // Некоторые S3-совместимые серверы возвращают 404 без NoSuchBucketException — повторяем попытку создания.
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
                    logger.info("S3 bucket created: {}", bucket)
                    bucketReady = true
                } catch (ce: Exception) {
                    logger.error("Failed to create S3 bucket '{}': {}", bucket, ce.message, ce)
                }
            } else {
                logger.error("Failed to probe S3 bucket '{}': {}", bucket, e.message, e)
            }
        }

        if (!bucketReady) return

        // Шаг 2: всегда (пере)применяем политику public-read. Запуск этого при каждом
        // старте безопасен и идемпотентен — MinIO перезаписывает предыдущую политику.
        // Критично, потому что бакет, созданный ранее без политики (например, старый деплой),
        // иначе блокировал бы анонимный GET с 403, ломая отображение аватаров.
        try {
            val policy = """{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::$bucket/*"}]}"""
            s3Client.putBucketPolicy(PutBucketPolicyRequest.builder().bucket(bucket).policy(policy).build())
            logger.info("S3 bucket public-read policy ensured: {}", bucket)
        } catch (e: Exception) {
            logger.error("Failed to set S3 bucket policy '{}': {}", bucket, e.message, e)
        }
    }

    fun uploadFile(fileBytes: ByteArray, path: String, contentType: String): String {
        val putRequest = PutObjectRequest.builder()
            .bucket(bucket)
            .key(path)
            .contentType(contentType)
            .build()

        s3Client.putObject(putRequest, RequestBody.fromBytes(fileBytes))
        logger.info("Uploaded file to S3: bucket={}, key={}", bucket, path)

        return "$baseUrl/$path"
    }

    fun deleteFile(path: String) {
        val deleteRequest = DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(path)
            .build()

        s3Client.deleteObject(deleteRequest)
        logger.info("Deleted file from S3: bucket={}, key={}", bucket, path)
    }
}
