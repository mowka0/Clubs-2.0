package com.clubs.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest

@Service
class StorageService(
    private val s3Client: S3Client,

    @Value("\${s3.bucket:clubs-uploads}")
    private val bucket: String,

    @Value("\${s3.base-url:}")
    private val baseUrl: String
) {

    private val logger = LoggerFactory.getLogger(StorageService::class.java)

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
