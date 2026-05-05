package com.paper.polish.util;

import com.paper.polish.config.MinioConfig;
import io.minio.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.*;
import java.nio.file.*;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class MinioUtil {

    private final MinioClient minioClient;
    private final MinioConfig minioConfig;

    @Value("${local.cache-dir:./cache/files}")
    private String cacheDir;

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
            Files.createDirectories(Paths.get(cacheDir));
            log.info("本地缓存目录: {}", cacheDir);
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

    public String uploadToLocal(String objectName, InputStream inputStream, String contentType) {
        try {
            Path localPath = Paths.get(cacheDir, objectName);
            Files.createDirectories(localPath.getParent());
            Files.copy(inputStream, localPath, StandardCopyOption.REPLACE_EXISTING);

            Path metaPath = Paths.get(cacheDir, objectName + ".meta");
            Files.writeString(metaPath, contentType);

            log.info("文件已缓存到本地: {}", objectName);
            return objectName;
        } catch (Exception e) {
            throw new RuntimeException("文件缓存到本地失败: " + e.getMessage(), e);
        }
    }

    @Scheduled(cron = "${local.sync-cron:0 */5 * * * ?}")
    public void syncLocalToMinio() {
        Path cachePath = Paths.get(cacheDir);
        if (!Files.exists(cachePath)) {
            return;
        }

        try (Stream<Path> files = Files.walk(cachePath)) {
            files.filter(p -> p.toString().endsWith(".meta"))
                    .forEach(metaFile -> {
                        try {
                            Path dataFile = Paths.get(metaFile.toString().replace(".meta", ""));
                            if (!Files.exists(dataFile)) {
                                Files.delete(metaFile);
                                return;
                            }

                            String objectName = cachePath.relativize(dataFile).toString().replace("\\", "/");
                            String contentType = Files.readString(metaFile);
                            long size = Files.size(dataFile);

                            try (InputStream is = Files.newInputStream(dataFile)) {
                                upload(objectName, is, size, contentType);
                            }

                            Files.delete(dataFile);
                            Files.delete(metaFile);
                            log.info("同步到 MinIO 完成: {}", objectName);
                        } catch (Exception e) {
                            log.error("同步文件失败: {}", metaFile, e);
                        }
                    });
        } catch (Exception e) {
            log.error("遍历缓存目录失败", e);
        }
    }

    public InputStream download(String objectName) {
        try {
            Path localPath = Paths.get(cacheDir, objectName);
            if (Files.exists(localPath)) {
                log.info("从本地缓存读取: {}", objectName);
                return Files.newInputStream(localPath);
            }

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
            Path localPath = Paths.get(cacheDir, objectName);
            if (Files.exists(localPath)) {
                return "/api/file/local/" + objectName;
            }

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

    public void deleteObject(String objectName) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .object(objectName)
                    .build());
            log.info("删除 MinIO 文件: {}", objectName);
        } catch (Exception e) {
            log.error("删除 MinIO 文件失败: {}", objectName, e);
        }
    }

    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanOldDocxFiles() {
        log.info("开始清理 MinIO 中的旧 docx 文件...");
        int deletedCount = 0;

        try {
            Iterable<Result<Item>> results = minioClient.listObjects(ListObjectsArgs.builder()
                    .bucket(minioConfig.getBucket())
                    .prefix("original/")
                    .recursive(true)
                    .build());

            for (Result<Item> result : results) {
                Item item = result.get();
                String objectName = item.objectName();

                if (objectName.endsWith(".docx")) {
                    deleteObject(objectName);
                    deletedCount++;
                }
            }
        } catch (Exception e) {
            log.error("清理旧文件失败", e);
        }

        log.info("清理完成，删除了 {} 个 docx 文件", deletedCount);
    }
}
