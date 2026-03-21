package com.clubs.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

@Configuration
class S3Config(
    @Value("\${AWS_ACCESS_KEY_ID:minioadmin}")
    private val accessKeyId: String,

    @Value("\${AWS_SECRET_ACCESS_KEY:minioadmin}")
    private val secretAccessKey: String,

    @Value("\${S3_ENDPOINT_URL:http://localhost:9000}")
    private val endpointUrl: String,

    @Value("\${AWS_REGION:ru-1}")
    private val region: String
) {

    @Bean
    fun s3Client(): S3Client {
        val credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey)

        return S3Client.builder()
            .region(Region.of(region))
            .endpointOverride(URI.create(endpointUrl))
            .credentialsProvider(StaticCredentialsProvider.create(credentials))
            .forcePathStyle(true) // Required for MinIO and S3-compatible storages
            .build()
    }
}
