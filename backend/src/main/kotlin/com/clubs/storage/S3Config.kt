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
    // Access key для S3-совместимого хранилища (дефолт — локальный MinIO)
    @Value("\${AWS_ACCESS_KEY_ID:minioadmin}")
    private val accessKeyId: String,

    // Secret key для S3-совместимого хранилища (дефолт — локальный MinIO)
    @Value("\${AWS_SECRET_ACCESS_KEY:minioadmin}")
    private val secretAccessKey: String,

    // Endpoint S3-совместимого хранилища (дефолт — локальный MinIO)
    @Value("\${S3_ENDPOINT_URL:http://localhost:9000}")
    private val endpointUrl: String,

    // Регион S3-хранилища
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
            .forcePathStyle(true) // Требуется для MinIO и S3-совместимых хранилищ
            .build()
    }
}
