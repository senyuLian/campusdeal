package com.campusdeal.controller;

import com.campusdeal.dto.Result;
import com.campusdeal.entity.UploadAsset;
import com.campusdeal.exception.ValidationException;
import com.campusdeal.mapper.UploadAssetMapper;
import com.campusdeal.security.AuthorizationService;
import com.campusdeal.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.InvalidPathException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private UploadAssetMapper uploadAssetMapper;
    @Resource
    private AuthorizationService authorizationService;

    @Value("${campusdeal.upload.root:./data/uploads}")
    private String uploadRoot;
    @Value("${campusdeal.upload.max-size-bytes:5242880}")
    private long maxSizeBytes;
    @Value("${campusdeal.upload.allowed-content-types:image/jpeg,image/png,image/gif,image/webp}")
    private String allowedContentTypes;

    @PostMapping("/post")
    public Result uploadImage(@RequestParam("file") MultipartFile image) {
        authorizationService.requireAuthenticated();
        if (image == null || image.isEmpty()) {
            throw new ValidationException("上传文件不能为空");
        }
        if (image.getSize() > maxSizeBytes) {
            throw new ValidationException("上传文件超过大小限制");
        }

        String contentType = detectImageType(image);
        if (!allowedTypes().contains(contentType)) {
            throw new ValidationException("仅支持图片文件");
        }

        String assetId = UUID.randomUUID().toString().replace("-", "");
        String relative = buildRelativePath(assetId, extensionFor(contentType));
        Path target = safeResolve(relative, true);
        try {
            image.transferTo(target);
            UploadAsset asset = new UploadAsset();
            asset.setAssetId(assetId);
            asset.setOwnerUserId(UserHolder.getUser().getId());
            asset.setRelativePath(relative);
            asset.setContentType(contentType);
            asset.setSizeBytes(Files.size(target));
            asset.setSha256(sha256(target));
            asset.setStatus("ACTIVE");
            asset.setCreateTime(LocalDateTime.now());
            uploadAssetMapper.insert(asset);
            return Result.ok(java.util.Map.of("assetId", assetId, "path", "/" + relative.replace('\\', '/')));
        } catch (IOException e) {
            deleteQuietly(target);
            throw new ValidationException("文件保存失败");
        } catch (RuntimeException e) {
            deleteQuietly(target);
            throw e;
        }
    }

    @PostMapping("/delete")
    public Result deleteUpload(@RequestParam("assetId") String assetId) {
        return deleteById(assetId);
    }

    @DeleteMapping("/{assetId}")
    public Result deleteUploadByPath(@PathVariable String assetId) {
        return deleteById(assetId);
    }

    private Result deleteById(String assetId) {
        authorizationService.requireAuthenticated();
        if (assetId == null || !assetId.matches("[a-fA-F0-9]{32}")) {
            throw new ValidationException("资源标识不合法");
        }
        UploadAsset asset = uploadAssetMapper.selectById(assetId);
        if (asset == null || !"ACTIVE".equals(asset.getStatus())) {
            return Result.ok();
        }
        authorizationService.requireSameUser(asset.getOwnerUserId());
        Path target = safeResolve(asset.getRelativePath(), false);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new ValidationException("文件删除失败");
        }
        asset.setStatus("DELETED");
        asset.setDeleteTime(LocalDateTime.now());
        uploadAssetMapper.updateById(asset);
        return Result.ok();
    }

    private Set<String> allowedTypes() {
        return Arrays.stream(allowedContentTypes.split(","))
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private String detectImageType(MultipartFile image) {
        try (InputStream input = image.getInputStream()) {
            byte[] header = input.readNBytes(32);
            String detected;
            if (header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                    && (header[2] & 0xff) == 0xff) detected = "image/jpeg";
            else if (header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P'
                    && header[2] == 'N' && header[3] == 'G') detected = "image/png";
            else if (header.length >= 4 && header[0] == 'G' && header[1] == 'I'
                    && header[2] == 'F' && header[3] == '8') detected = "image/gif";
            else if (isWebpHeader(header, image.getSize())) detected = "image/webp";
            else detected = null;
            if (detected == null) throw new ValidationException("无法识别图片内容");
            // ImageIO decodes the formats supported by the runtime; WebP is
            // accepted only after its RIFF/WEBP signature check above.
            if (!"image/webp".equals(detected)) {
                try (InputStream decode = image.getInputStream()) {
                    BufferedImage decoded = ImageIO.read(decode);
                    if (decoded == null || decoded.getWidth() <= 0 || decoded.getHeight() <= 0) {
                        throw new ValidationException("无法解码图片内容");
                    }
                }
            }
            return detected;
        } catch (IOException ignored) {
            // Report a generic validation error below.
        }
        throw new ValidationException("无法识别图片内容");
    }

    private boolean isWebpHeader(byte[] header, long size) {
        if (size < 20 || header.length < 16) return false;
        boolean riff = header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F';
        boolean webp = header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        boolean chunk = (header[12] == 'V' && header[13] == 'P' && header[14] == '8'
                && (header[15] == ' ' || header[15] == 'L' || header[15] == 'X'));
        long riffLength = (header[4] & 0xffL) | ((header[5] & 0xffL) << 8)
                | ((header[6] & 0xffL) << 16) | ((header[7] & 0xffL) << 24);
        long chunkLength = (header[16] & 0xffL) | ((header[17] & 0xffL) << 8)
                | ((header[18] & 0xffL) << 16) | ((header[19] & 0xffL) << 24);
        return riff && webp && chunk && riffLength >= 12 && riffLength + 8 <= size
                && chunkLength > 0 && chunkLength + 20 <= size;
    }

    private String buildRelativePath(String assetId, String extension) {
        int hash = Math.abs(assetId.hashCode());
        return "blogs/" + (hash & 0xF) + "/" + ((hash >> 4) & 0xF)
                + "/" + assetId + extension;
    }

    private Path safeResolve(String relativePath, boolean createParent) {
        try {
            if (relativePath == null || relativePath.isBlank()
                    || Paths.get(relativePath).isAbsolute()
                    || relativePath.contains("..")) {
                throw new ValidationException("文件路径不合法");
            }
            Path root = Paths.get(uploadRoot).toAbsolutePath().normalize();
            if (createParent) {
                Files.createDirectories(root);
            }
            Path rootReal = root.toRealPath();
            Path target = rootReal.resolve(relativePath).normalize();
            if (!target.startsWith(rootReal)) {
                throw new ValidationException("文件路径越界");
            }
            Path parent = target.getParent();
            if (parent == null) {
                throw new ValidationException("文件路径不合法");
            }
            if (createParent) {
                Files.createDirectories(parent);
            }
            Path parentReal = parent.toRealPath();
            if (!parentReal.startsWith(rootReal) || Files.isSymbolicLink(target)) {
                throw new ValidationException("文件路径越界");
            }
            if (!createParent && Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && !target.toRealPath().startsWith(rootReal)) {
                throw new ValidationException("文件路径越界");
            }
            return target;
        } catch (InvalidPathException e) {
            throw new ValidationException("文件路径不合法");
        } catch (IOException e) {
            throw new ValidationException("文件路径不可用");
        }
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) >= 0;) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IOException("hash failed", e);
        }
    }

    private static void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
            log.warn("Failed to clean up temporary upload file");
        }
    }
}
