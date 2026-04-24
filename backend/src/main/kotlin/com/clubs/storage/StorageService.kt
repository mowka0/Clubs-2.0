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
     * Create the bucket on startup if it doesn't exist (MinIO ships empty) and
     * apply a public-read policy so uploaded avatars are reachable via <img src>.
     *
     * Failures here are logged but not fatal — backend must still start; the
     * admin sees the error, and uploads will fail cleanly until the bucket is
     * reachable.
     */
    @PostConstruct
    fun initBucket() {
        // Step 1: ensure bucket exists (idempotent).
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
                // Some S3-compatible servers return 404 without NoSuchBucketException — retry create.
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

        // Step 2: always (re)apply the public-read policy. Running this on every
        // startup is safe and idempotent — MinIO overwrites the previous policy.
        // Critical because a bucket pre-created without policy (e.g. older deploy)
        // would otherwise block anonymous GET with 403, breaking avatar rendering.
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
