package com.paper.polish.util;

import com.paper.polish.config.MinioConfig;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioUtil {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @PostConstruct
    public void init() {
        try {
            String bucket = minioConfig.getBucket();
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .build());
                log.info("创建 MinIO bucket: {}", bucket);
            }
        } catch (Exception e) {
            log.error("MinIO 初始化失败: ", e);
        }
    }

    public String upload(String objectName, InputStream inputStream, long size, String contentType) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectName)
                    .stream(inputStream, size, -1)
                    .contentType(contentType)
                    .build());
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("文件上传到 MinIO 失败: " + e.getMessage(), e);
        }
    }

    public InputStream download(String objectName) {
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("从 MinIO 下载文件失败: " + e.getMessage(), e);
        }
    }

    public String getPresignedUrl(String objectName) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(minioConfig.getBucket())
                    .object(objectName)
                    .expiry(60 * 60 * 24)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("获取下载链接失败: " + e.getMessage(), e);
        }
    }

    public void copyObject(String sourceObject, String targetObject) {
        try {
            minioClient.copyObject(CopyObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(targetObject)
                    .source(CopySource.builder()
                            .bucket(minioConfig.getBucket())
                            .object(sourceObject)
                            .build())
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("复制文件失败: " + e.getMessage(), e);
        }
    }
}
