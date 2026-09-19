package com.campusdeal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_upload_asset")
public class UploadAsset {
    @TableId(value = "asset_id", type = IdType.INPUT)
    private String assetId;
    private Long ownerUserId;
    private String relativePath;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private String status;
    private LocalDateTime createTime;
    private LocalDateTime deleteTime;
}
